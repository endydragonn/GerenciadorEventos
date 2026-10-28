package com.gerenciador.eventos.admission;

import java.io.IOException;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.admissionclient.api.AdmissionStatus;
import com.gerenciador.eventos.reservationclient.ReservationClient;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.reservationclient.api.IdempotencyConflictException;
import com.rabbitmq.client.Channel;

/**
 * O worker pool da fila de admissão (issue #42) e o listener da dead-letter.
 *
 * <p><b>Worker</b>: {@code @RabbitListener} na {@code admission.requests}, ack MANUAL, concorrência FIXA
 * ({@code admission.worker.concurrency}, default 32) e prefetch 1 ({@code spring.rabbitmq.listener.simple.prefetch}): é o teto de CAS
 * simultâneos no reservation (o {@code ReservationClient} do serviço tem pool = a concorrência do worker + a da dead-letter, que só faz {@code replay}, leitura). Cada mensagem: (0) sai da espera; se o
 * resultado da chave não é desta tentativa ou já é final, ack e fim (a mensagem já tem resultado gravado de uma resposta anterior: desvio
 * consciente do "ack só depois da resposta do reservation"); (1) vencida ({@code agora > deadline}) = reconcilia e ack; (2) senão chama
 * {@code POST /internal/reservations?path=cas} com a {@code Idempotency-Key} da mensagem: 201 = {@code pending}, 4xx de negócio =
 * {@code rejected}, ack. Reservation fora, timeout, 5xx, 401 ou QUALQUER exceção (Redis fora, bug): espera o backoff, volta à espera e
 * {@code nack} com requeue, de modo que nenhuma mensagem fica sem ack e nenhum worker fica preso; a mensagem volta à MESMA fila (o TTL
 * original vale) e o {@code deadline} do corpo garante o limite. Reentrega depois de um CAS que comitou é segura: o reservation devolve o
 * resultado salvo pela chave e não toca o {@code eventcap}.</p>
 *
 * <p><b>Dead-letter</b> ({@code admission.timed_out}): a mensagem morreu na fila (TTL). Reconcilia pelo {@code replay} do reservation: há
 * reserva com a chave (o CAS comitou e a resposta se perdeu) = {@code pending}; senão, ou com qualquer erro, {@code timed_out}. SEMPRE
 * dá ack (lado seguro: a reserva, se existir, aparece em "minhas reservas" e o job de expiração devolve a vaga).</p>
 */
@Component
@ConditionalOnProperty(name = "admission.worker.enabled", havingValue = "true", matchIfMissing = true)
public class AdmissionWorker {

    private static final Logger log = LoggerFactory.getLogger(AdmissionWorker.class);
    private static final int MAX_MOTIVO = 300;

    private final AdmissionStore store;
    private final ReservationClient reservation;
    private final ObjectMapper json;
    private final Clock relogio;
    private final long backoffMs;

    public AdmissionWorker(AdmissionStore store, ReservationClient reservation, ObjectMapper json, Clock relogio,
            @Value("${admission.retry-backoff-ms:500}") long backoffMs) {
        this.store = store;
        this.reservation = reservation;
        this.json = json;
        this.relogio = relogio;
        this.backoffMs = backoffMs;
    }

    @RabbitListener(id = "admission-worker", queues = "${admission.amqp.prefix:}admission.requests", ackMode = "MANUAL",
            concurrency = "${admission.worker.concurrency:32}")
    public void trabalhar(Message bruta, Channel canal) throws IOException {
        long tag = bruta.getMessageProperties().getDeliveryTag();
        AdmissionMessage mensagem = ler(bruta);
        if (mensagem == null) {
            canal.basicAck(tag, false); // ilegível: reentregar não conserta
            return;
        }
        try {
            processar(mensagem);
        } catch (Exception | Error ex) {
            // Error também: com ack manual o container não faz nack, e um Error relançado PARARIA o container inteiro (todos os workers, em
            // silêncio: o healthcheck é TCP). A mensagem volta, o erro vai para o log e o worker segue
            devolver(mensagem, tag, canal, ex);
            return;
        }
        canal.basicAck(tag, false);
    }

    @RabbitListener(id = "admission-timed-out", queues = "${admission.amqp.prefix:}admission.timed_out", ackMode = "MANUAL",
            concurrency = "${admission.dlx.concurrency:4}")
    public void deadLetter(Message bruta, Channel canal) throws IOException {
        AdmissionMessage mensagem = ler(bruta);
        if (mensagem != null) {
            try {
                store.sairDaEspera(mensagem);
                // só um queued DESTA tentativa precisa de reconciliação (ack perdido e depois TTL, ou tentativa nova: o replay seria à toa)
                if (queuedDaTentativa(mensagem, store.resultado(mensagem.userId(), mensagem.idempotencyKey()))) {
                    reconciliar(mensagem);
                }
            } catch (Exception ex) {
                // o queued que sobrar vence sozinho (deadline + carência) e vale como timed_out
                log.warn("[admission] falha ao reconciliar a dead-letter {}: {}", mensagem.attemptId(), ex.toString());
            }
        }
        canal.basicAck(bruta.getMessageProperties().getDeliveryTag(), false);
    }

    private void processar(AdmissionMessage m) {
        store.sairDaEspera(m);
        ResultadoDaFila atual = store.resultado(m.userId(), m.idempotencyKey());
        if (!queuedDaTentativa(m, atual)) {
            return;
        }
        if (relogio.millis() > m.deadline()) {
            reconciliar(m);
            return;
        }
        try {
            EventWallet reserva = reservation.addLink(new EventWallet(m.userId(), m.eventId()), m.idempotencyKey(), false);
            String expira = reserva.getExpiresAt() == null ? null : reserva.getExpiresAt().toString();
            store.concluir(m, atual.pending(expira));
        } catch (IllegalArgumentException | IdempotencyConflictException ex) {
            store.concluir(m, atual.rejected(motivo(ex)));
        }
    }

    private static boolean queuedDaTentativa(AdmissionMessage m, ResultadoDaFila atual) {
        return atual != null && m.attemptId().equals(atual.attemptId()) && AdmissionStatus.QUEUED.equals(atual.status());
    }

    /** Mensagem vencida ou dead-lettered: a reserva existe (replay) = pending; senão, ou com erro, timed_out. */
    private void reconciliar(AdmissionMessage m) {
        ResultadoDaFila atual = ResultadoDaFila.queued(m);
        ResultadoDaFila destino;
        try {
            EventWallet reserva = reservation.replay(new EventWallet(m.userId(), m.eventId()), m.idempotencyKey());
            destino = reserva == null ? atual.timedOut()
                    : atual.pending(reserva.getExpiresAt() == null ? null : reserva.getExpiresAt().toString());
        } catch (RuntimeException ex) {
            destino = atual.timedOut();
        }
        store.concluir(m, destino);
    }

    private void devolver(AdmissionMessage m, long tag, Channel canal, Throwable causa) {
        if (causa instanceof Error) {
            log.error("[admission] ERRO da JVM no worker: a mensagem {} volta à fila", m.attemptId(), causa);
        } else {
            log.warn("[admission] mensagem {} volta à fila ({}): {}", m.attemptId(), causa.getClass().getSimpleName(), causa.getMessage());
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        try {
            store.voltarParaEspera(m);
        } catch (RuntimeException ex) {
            log.warn("[admission] não consegui devolver a mensagem {} à espera: {}", m.attemptId(), ex.toString());
        }
        try {
            canal.basicNack(tag, false, true);
        } catch (IOException | RuntimeException ex) {
            log.error("[admission] nack da mensagem {} falhou: {}", m.attemptId(), ex.toString());
        }
    }

    private AdmissionMessage ler(Message bruta) {
        try {
            AdmissionMessage m = json.readValue(bruta.getBody(), AdmissionMessage.class);
            if (m.userId() == null || m.eventId() == null || m.idempotencyKey() == null || m.attemptId() == null) {
                throw new IllegalArgumentException("campo obrigatório ausente");
            }
            return m;
        } catch (IOException | RuntimeException ex) {
            log.error("[admission] mensagem ilegível descartada: {}", ex.toString());
            return null;
        }
    }

    private static String motivo(RuntimeException ex) {
        String texto = ex.getMessage() == null ? "Recusada" : ex.getMessage();
        return texto.length() > MAX_MOTIVO ? texto.substring(0, MAX_MOTIVO) : texto;
    }
}
