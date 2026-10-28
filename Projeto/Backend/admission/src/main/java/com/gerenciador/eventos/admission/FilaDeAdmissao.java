package com.gerenciador.eventos.admission;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.admissionclient.api.AdmissionRequest;
import com.gerenciador.eventos.admissionclient.api.AdmissionStatus;

/**
 * O caminho de ENFILEIRAR da fila de admissão (issue #42): grava o resultado {@code queued} + a espera + o limite por usuário no Redis (um
 * script), publica a mensagem com publisher confirms e {@code mandatory} e desfaz a entrada se a mensagem não foi publicada. É um método
 * de serviço: o gatilho que o liga ao {@code POST /internal/admissions} (e a resposta 202) é da #43.
 *
 * <p>Falhas: chave de outro evento = {@link ChaveReusadaException} (422); limite por usuário = {@link LimiteUsuarioException} (429); fila
 * cheia (nack do broker, {@code reject-publish}) = {@link FilaCheiaException} (429, desfaz); exceção antes do envio, broker bloqueado
 * ou mensagem sem rota ({@code returned}) = {@link NaoPublicadaException} (desfaz; o chamador segue síncrono). Envio feito mas SEM
 * confirmação no prazo = tratado como publicado (devolve {@code queued}): desfazer apagaria o resultado de uma mensagem que pode estar na
 * fila, e se ela não chegou o {@code queued} vence pelo {@code deadline} (lido como {@code timed_out}).</p>
 */
@Service
public class FilaDeAdmissao {

    private static final Logger log = LoggerFactory.getLogger(FilaDeAdmissao.class);

    /** A chave de idempotência já foi usada, por este usuário, com outro evento (422). */
    public static class ChaveReusadaException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ChaveReusadaException() {
            super("Idempotency-Key já usada em outra requisição (evento diferente)");
        }
    }

    /** Mensagens demais em espera para este usuário (429). */
    public static class LimiteUsuarioException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LimiteUsuarioException() {
            super("Muitas reservas na fila para este usuário");
        }
    }

    /** O broker recusou a publicação: a fila está cheia (429). */
    public static class FilaCheiaException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FilaCheiaException() {
            super("Fila de admissão cheia, tente mais tarde");
        }
    }

    /** A mensagem não foi publicada (broker fora, bloqueado ou sem rota): a entrada foi desfeita. */
    public static class NaoPublicadaException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NaoPublicadaException(String motivo, Throwable causa) {
            super("Mensagem não publicada: " + motivo, causa);
        }
    }

    /** {@code publicada} = false na repetição da chave (devolve o estado atual, sem publicar de novo). */
    public record ResultadoFila(boolean publicada, AdmissionStatus estado) { }

    private final AdmissionStore store;
    private final RabbitTemplate rabbit;
    private final AdmissionTopology topologia;
    private final BrokerBloqueado bloqueado;
    private final ObjectMapper json;
    private final Clock relogio;
    private final long ttlFilaMs;
    private final long esperaConfirmacaoMs;

    public FilaDeAdmissao(AdmissionStore store, RabbitTemplate rabbit, AdmissionTopology topologia, BrokerBloqueado bloqueado,
            ObjectMapper json, Clock relogio, @Value("${admission.queue.ttl-ms:30000}") long ttlFilaMs,
            @Value("${admission.publish.confirm-timeout-ms:200}") long esperaConfirmacaoMs) {
        this.store = store;
        this.rabbit = rabbit;
        this.topologia = topologia;
        this.bloqueado = bloqueado;
        this.json = json;
        this.relogio = relogio;
        this.ttlFilaMs = ttlFilaMs;
        this.esperaConfirmacaoMs = esperaConfirmacaoMs;
    }

    /** Enfileira a tentativa (ver a classe). Redis fora propaga ({@code DataAccessException}). */
    public ResultadoFila enfileirar(AdmissionRequest pedido) {
        AdmissionService.validar(pedido);
        if (!"cas".equals(pedido.path())) {
            // a mensagem não leva o braço e o worker reserva SEMPRE pelo CAS: enfileirar um pedido naive falsearia o braço de controle
            throw new IllegalArgumentException("a fila de admissão só aceita path=cas");
        }
        AdmissionMessage mensagem = new AdmissionMessage(pedido.userId(), pedido.eventId(), pedido.idempotencyKey(),
                UUID.randomUUID().toString(), relogio.millis() + ttlFilaMs);
        AdmissionStore.Entrada entrada = store.entrar(mensagem);
        switch (entrada.tipo()) {
            case OUTRO_EVENTO:
                throw new ChaveReusadaException();
            case LIMITE_USUARIO:
                throw new LimiteUsuarioException();
            case EXISTENTE:
                return new ResultadoFila(false, entrada.atual().efetivo(relogio.millis()).paraStatus());
            default:
                publicar(mensagem);
                return new ResultadoFila(true, ResultadoDaFila.queued(mensagem).paraStatus());
        }
    }

    /** O resultado da chave do usuário (um {@code queued} vencido vale como {@code timed_out}), ou vazio. */
    public Optional<AdmissionStatus> consultar(long userId, String idempotencyKey) {
        ResultadoDaFila r = store.resultado(userId, idempotencyKey);
        return Optional.ofNullable(r).map(x -> x.efetivo(relogio.millis()).paraStatus());
    }

    private void publicar(AdmissionMessage mensagem) {
        if (bloqueado.estaBloqueado()) {
            desfazer(mensagem);
            throw new NaoPublicadaException("o broker bloqueou a conexão", null);
        }
        CorrelationData correlacao = new CorrelationData(mensagem.attemptId());
        try {
            rabbit.send(topologia.exchange(), AdmissionTopology.rota(mensagem.eventId()), corpo(mensagem), correlacao);
        } catch (RuntimeException ex) {
            desfazer(mensagem);
            throw new NaoPublicadaException("falha ao enviar ao broker", ex);
        }
        try {
            CorrelationData.Confirm confirmacao = correlacao.getFuture().get(esperaConfirmacaoMs, TimeUnit.MILLISECONDS);
            if (!confirmacao.isAck() && confirmacao.getReason() == null) {
                // nack do BROKER (sem motivo do framework): é o reject-publish da fila cheia
                desfazer(mensagem);
                throw new FilaCheiaException();
            }
            if (confirmacao.isAck() && correlacao.getReturned() != null) {
                desfazer(mensagem);
                throw new NaoPublicadaException("sem rota para a mensagem (fila apagada?)", null);
            }
        } catch (TimeoutException | ExecutionException ex) {
            log.warn("[admission] sem confirmação do broker em {} ms: tratando como publicada ({})", esperaConfirmacaoMs, ex.toString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private Message corpo(AdmissionMessage mensagem) {
        try {
            return MessageBuilder.withBody(json.writeValueAsBytes(mensagem)).setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void desfazer(AdmissionMessage mensagem) {
        try {
            store.desfazer(mensagem);
        } catch (RuntimeException ex) {
            // o queued que sobrar vence sozinho pelo deadline; a espera sai pelo score
            log.warn("[admission] não consegui desfazer a entrada da tentativa {}: {}", mensagem.attemptId(), ex.toString());
        }
    }
}
