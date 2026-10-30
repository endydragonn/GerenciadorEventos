package com.gerenciador.eventos.gateway;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.gerenciador.eventos.admissionclient.api.AdmissionApi;
import com.gerenciador.eventos.admissionclient.api.AdmissionRequest;
import com.gerenciador.eventos.admissionclient.api.AdmissionResult;
import com.gerenciador.eventos.catalogclient.api.EventApi;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.reservationclient.api.EventWalletApi;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Roteamento da inscrição em evento (issue #33, Fase 7): o gateway confere o que é DELE (a existência do evento, que é do
 * {@code catalog}) e chama o serviço reservation, que é dono de {@code eventcap}/{@code walletevent}/{@code idempotency_key}.
 * A carteira do usuário NÃO é conferida desde a #36: ela nasce no mesmo INSERT do usuário (trigger), nenhuma rota apaga usuário
 * ou carteira e o {@code uid} do token vem assinado pelo identity (conferir viraria um salto HTTP por reserva). Quem escolhe o
 * fluxo do braço de reserva é o gateway (feature flag {@code reservation.readpath}, env {@code RESERVATION_READPATH}, lida na
 * subida):
 * <ul>
 *   <li><b>{@code cas}</b> (default) — UMA chamada HTTP: o CAS é atômico dentro do reservation.</li>
 *   <li><b>{@code naive}</b> — DUAS chamadas HTTP: {@code GET} da disponibilidade (projeção assíncrona) e depois
 *       {@code POST} da reserva sem CAS ({@code ReservationClient#addLink}). A ida e volta entre elas é a janela
 *       check-then-act do braço de controle do experimento. Oversella proporcional ao lag da projeção.</li>
 * </ul>
 * O {@code userId} que chega aqui já é o do token: o {@link BffEventWalletController} o tira do JWT (claim {@code uid}) e devolve
 * 403 se o corpo/query/path trouxer outro (issue #57). Ele cruza a rede como parâmetro explícito para a API interna do reservation.
 *
 * <p><b>Admission (issue #41).</b> Depois das validações (token, ids, evento existe) e ANTES do reservation, {@code addLink} registra a
 * tentativa no contador do evento do serviço {@code admission} ({@link AdmissionApi#admit}), nos DOIS braços (no {@code naive}, antes da
 * leitura de disponibilidade: a janela check-then-act dele não muda). É UMA ida HTTP com fail-open (admission fora ou lento => segue
 * síncrono, com log) e disjuntor, dentro do {@code AdmissionClient}. Sem {@code Idempotency-Key} na requisição, o gateway GERA um UUID e o
 * manda ao admission (a chave da mensagem no modo fila). Com o admission respondendo {@code sync}, o reservation é chamado exatamente como
 * antes da #41, com a chave DO CLIENTE (nula se ele não mandou): mandar sempre uma chave faria o serviço consultar {@code idempotency_key} em
 * toda rejeição de pré-requisito, um custo novo no caminho síncrono. A chave que volta no header da resposta é a que o reservation usou.
 *
 * <p><b>Modo fila (issue #43).</b> Acima do gatilho (80% de X por evento, braço {@code cas}) o admission já enfileirou a tentativa e responde
 * {@code queued}: o gateway lança {@link ReservaEnfileiradaException} (o controller faz o {@code 202}) e não chama o reservation; o
 * {@code naive} nunca enfileira. Só no FAIL-OPEN do braço {@code cas} (a chamada ao admission falhou, e ele pode ter publicado antes de o gateway
 * desistir) o reservation recebe a chave efetiva (a do cliente ou a gerada): se a mensagem também for processada, ele deduplica pela chave.
 * {@code AdmissionRecusadaException} (422/429) propaga e o controller a repassa.</p>
 *
 * <p>Ordem das mensagens de erro do {@code addLink}: evento (aqui) e depois as do reservation ("Capacidade do evento não
 * configurada", janela de compra, "Vínculo já existente", "Evento lotado"). No braço naive a disponibilidade
 * é lida ANTES de "Vínculo já existente", então um usuário que já tem a vaga num evento esgotado vê "Evento lotado".</p>
 */
@Component
public class EventWalletGateway {

    private final EventWalletApi reservation;
    private final EventApi eventApi;
    private final AdmissionApi admission;
    private final boolean naive;
    private final Cache<Long, Boolean> eventosExistentes = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public EventWalletGateway(EventWalletApi reservation, EventApi eventApi, AdmissionApi admission,
            @Value("${reservation.readpath:cas}") String readPath) {
        this.reservation = reservation;
        this.eventApi = eventApi;
        this.admission = admission;
        this.naive = "naive".equalsIgnoreCase(readPath);
    }

    /**
     * Existência do evento com cache POSITIVO de 30 s (issue #35: o {@code exists} vira um salto de rede quando o catalog sair do
     * processo). Só {@code true} entra no cache; {@code false} é reconsultado; falha do catalog propaga (nunca vira
     * {@code false}, ou seria "evento não existe"). Trade-off: um evento apagado ainda passa por aqui por até 30 s, e a reserva
     * é recusada pelo reservation ("Capacidade do evento não configurada", o {@code eventcap} sai no cancelamento).
     */
    private boolean eventoExiste(Long eventId) {
        if (eventosExistentes.getIfPresent(eventId) != null) {
            return true;
        }
        boolean existe = eventApi.exists(eventId);
        if (existe) {
            eventosExistentes.put(eventId, Boolean.TRUE);
        }
        return existe;
    }

    public EventWallet addLink(EventWallet ew, String idempotencyKey) {
        validateBasic(ew);
        if (!eventoExiste(ew.getEventId())) {
            throw new IllegalArgumentException("Evento não encontrado");
        }
        // A chave efetiva (a do cliente ou um UUID gerado aqui) vai ao admission: é a chave da mensagem no modo fila. Fail-open: um 400 dele
        // (chave longa) não falha a reserva; o reservation recusa a chave longa do cliente com o mesmo 400 de sempre.
        String chaveEfetiva = idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey;
        AdmissionResult admissao = admission.admit(new AdmissionRequest(ew.getEventId(), ew.getUserId(), chaveEfetiva, naive ? "naive" : "cas"));
        if (admissao.queued()) {
            throw new ReservaEnfileiradaException(EstadoDaReserva.de(admissao)); // 202: nenhuma reserva ainda (o worker do admission a faz)
        }
        // Com o admission respondendo sync, o reservation recebe a chave DO CLIENTE (nula se ele não mandou), como antes da #41: mandar sempre uma
        // chave faria o serviço consultar idempotency_key em toda rejeição de pré-requisito (custo novo no caminho síncrono). Só no fail-open do
        // braço cas (rpm < 0: a chamada ao admission falhou, e ele pode ter publicado a mensagem antes de o gateway desistir) vai a chave efetiva:
        // se a mensagem também for processada, o reservation deduplica pela mesma chave (nunca duas vagas). O naive nunca enfileira.
        boolean admissaoFalhou = !naive && admissao.rpm() < 0;
        return reservation.addLink(ew, admissaoFalhou ? chaveEfetiva : idempotencyKey, naive);
    }

    /**
     * Estado de uma tentativa enfileirada (issue #43), do usuário do token: vazio se a chave não existe PARA ELE (a chave é escopada pelo usuário
     * no admission, então "não existe" e "é de outro usuário" são o mesmo 404). O controller já validou o usuário (token) e a chave. Falha do admission propaga ({@code AdmissionUnavailableException}).
     */
    public Optional<EstadoDaReserva> estadoDaAdmissao(Long userId, String idempotencyKey) {
        return admission.result(userId, idempotencyKey).map(s -> EstadoDaReserva.de(s, idempotencyKey));
    }

    public EventWallet confirm(Long userId, Long eventId) {
        validateIds(userId, eventId);
        return reservation.confirm(userId, eventId);
    }

    public void removeLink(Long userId, Long eventId) {
        validateIds(userId, eventId);
        reservation.removeLink(userId, eventId);
    }

    public List<EventWallet> listByUser(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("userId inválido");
        return reservation.listByUser(userId);
    }

    public String getValidationErrors(EventWallet ew) {
        StringBuilder sb = new StringBuilder();
        boolean userOk = ew.getUserId() != null && ew.getUserId() > 0;
        boolean eventOk = ew.getEventId() != null && ew.getEventId() > 0;
        if (!userOk) sb.append("user_id é obrigatório e deve ser positivo. ");
        if (!eventOk) sb.append("event_id é obrigatório e deve ser positivo. ");
        if (eventOk && !eventoExiste(ew.getEventId())) sb.append("Evento não encontrado. ");
        if (userOk && eventOk && reservation.existsActive(ew.getUserId(), ew.getEventId())) {
            sb.append("Vínculo já existente. ");
        }
        return sb.toString().trim();
    }

    private static void validateBasic(EventWallet ew) {
        if (ew.getUserId() == null || ew.getUserId() <= 0)
            throw new IllegalArgumentException("user_id é obrigatório e deve ser positivo");
        if (ew.getEventId() == null || ew.getEventId() <= 0)
            throw new IllegalArgumentException("event_id é obrigatório e deve ser positivo");
    }

    private static void validateIds(Long userId, Long eventId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("userId inválido");
        if (eventId == null || eventId <= 0) throw new IllegalArgumentException("eventId inválido");
    }
}
