package com.gerenciador.eventos.admission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.admissionclient.api.AdmissionRequest;
import com.gerenciador.eventos.admissionclient.api.AdmissionResult;

/**
 * Regra do {@code POST /internal/admissions}: valida o pedido, conta a tentativa no evento e devolve o modo (issue #41). Desde a #43, no braço
 * {@code cas}, a MESMA chamada ao script do contador decide o modo do evento (gatilho de uso e histerese, ver {@link RpmCounter}) e, em modo
 * fila, enfileira a tentativa pela {@link FilaDeAdmissao} (#42) e devolve {@code queued} com o estado dela; o braço {@code naive} só conta e
 * devolve sempre {@code sync}. A decisão e o enfileirar são DUAS idas ao Redis (decisão; entrada na fila), só no modo fila: o caminho síncrono
 * continua em uma. Elas NÃO são atômicas entre si, e isso permite três sequências (nenhuma prende estado; a corretude é do CAS, e o reservation
 * deduplica pela chave): (1) A decide fila; B (uso &lt; 0,70, espera vazia) desliga o modo; A entra na espera e o {@code enfileirar.lua} religa o
 * modo, que fica ligado SEM uso &ge; 0,80 (com uso entre 0,70 e 0,80 segue em fila até a primeira decisão com uso &lt; 0,70 e espera vazia); (2) A decide
 * fila, o {@code enfileirar.lua} marca o modo, a publicação falha ({@link FilaDeAdmissao.NaoPublicadaException}, entrada desfeita) e a resposta é
 * {@code sync}, mas o modo fica ligado com a espera vazia (idem: sai na próxima decisão com uso &lt; 0,70); (3) o worker segura a mensagem (a espera
 * esvazia) e uma decisão com uso &lt; 0,70 desliga o modo; o requeue ({@code voltar.lua}) religa o modo, mantendo "mensagem esperando = modo fila".
 *
 * <p>Fail-open do lado do serviço: mensagem não publicada (broker bloqueado, sem rota, falha ao enviar; a entrada foi desfeita) = {@code sync}.
 * Chave de outro evento ({@link FilaDeAdmissao.ChaveReusadaException}, 422), limite por usuário e fila cheia (429) viram a resposta de negócio
 * no {@link InternalApiExceptionHandler}. Redis fora ou lento (timeout de 200 ms) PROPAGA a falha: o handler responde 503 e o fail-open é do
 * CLIENTE (o gateway segue síncrono). Devolver 200 {@code sync} aqui esconderia a falha do disjuntor do gateway, que nunca abriria e pagaria os
 * 200 ms do Redis travado em TODA reserva.</p>
 */
@Service
public class AdmissionService {

    private static final Logger log = LoggerFactory.getLogger(AdmissionService.class);
    private static final int MAX_CHAVE = 255;

    private final RpmCounter contador;
    private final FilaDeAdmissao fila;

    public AdmissionService(RpmCounter contador, FilaDeAdmissao fila) {
        this.contador = contador;
        this.fila = fila;
    }

    public AdmissionResult admit(AdmissionRequest pedido) {
        validar(pedido);
        if (!"cas".equals(pedido.path())) {
            return AdmissionResult.sync(contador.contar(pedido.eventId()));
        }
        RpmCounter.Decisao decisao = contador.decidir(pedido.eventId());
        if (!decisao.fila()) {
            return AdmissionResult.sync(decisao.rpm());
        }
        try {
            return AdmissionResult.queued(decisao.rpm(), fila.enfileirar(pedido).estado(), pedido.idempotencyKey());
        } catch (FilaDeAdmissao.NaoPublicadaException ex) {
            log.warn("[admission] {}: a reserva segue síncrona", ex.getMessage());
            return AdmissionResult.sync(decisao.rpm());
        }
    }

    /** RPM do evento (só leitura). Falha do Redis propaga (503). */
    public long rpm(long eventId) {
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId deve ser positivo");
        }
        return contador.rpm(eventId);
    }

    /** As regras do pedido (a da Idempotency-Key é a do reservation). Também usada por {@link FilaDeAdmissao#enfileirar}. */
    static void validar(AdmissionRequest p) {
        if (p == null) {
            throw new IllegalArgumentException("Pedido vazio");
        }
        if (p.eventId() == null || p.eventId() <= 0) {
            throw new IllegalArgumentException("eventId é obrigatório e deve ser positivo");
        }
        if (p.userId() == null || p.userId() <= 0) {
            throw new IllegalArgumentException("userId é obrigatório e deve ser positivo");
        }
        if (p.idempotencyKey() == null || p.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key é obrigatória");
        }
        if (p.idempotencyKey().length() > MAX_CHAVE) {
            throw new IllegalArgumentException("Idempotency-Key deve ter no máximo 255 caracteres");
        }
        if (!"cas".equals(p.path()) && !"naive".equals(p.path())) {
            throw new IllegalArgumentException("path deve ser cas ou naive");
        }
    }
}
