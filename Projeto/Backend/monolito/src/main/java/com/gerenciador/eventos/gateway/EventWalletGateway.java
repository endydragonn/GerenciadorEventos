package com.gerenciador.eventos.gateway;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.gerenciador.eventos.catalogclient.api.EventApi;
import com.gerenciador.eventos.identityclient.api.MyWalletApi;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.reservationclient.api.EventWalletApi;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Roteamento da inscrição em evento (issue #33, Fase 7): o gateway confere o que é DELE (a carteira do usuário, que é do
 * {@code identity}, e a existência do evento, que é do {@code catalog}) e chama o serviço reservation, que é dono de
 * {@code eventcap}/{@code walletevent}/{@code idempotency_key}. Quem escolhe o fluxo do braço de reserva é o gateway
 * (feature flag {@code reservation.readpath}, env {@code RESERVATION_READPATH}, lida na subida):
 * <ul>
 *   <li><b>{@code cas}</b> (default) — UMA chamada HTTP: o CAS é atômico dentro do reservation.</li>
 *   <li><b>{@code naive}</b> — DUAS chamadas HTTP: {@code GET} da disponibilidade (projeção assíncrona) e depois
 *       {@code POST} da reserva sem CAS ({@code ReservationClient#addLink}). A ida e volta entre elas é a janela
 *       check-then-act do braço de controle do experimento. Oversella proporcional ao lag da projeção.</li>
 * </ul>
 * O {@code userId} que chega aqui já é o do token: o {@link BffEventWalletController} o tira do JWT e devolve 403 se o
 * corpo/query/path trouxer outro (issue #57). Ele cruza a rede como parâmetro explícito para a API interna do reservation.
 *
 * <p>Ordem das mensagens de erro do {@code addLink}: carteira, evento (aqui) e depois as do reservation ("Capacidade do
 * evento não configurada", janela de compra, "Vínculo já existente", "Evento lotado"). No braço naive a disponibilidade
 * é lida ANTES de "Vínculo já existente", então um usuário que já tem a vaga num evento esgotado vê "Evento lotado".</p>
 */
@Component
public class EventWalletGateway {

    private final EventWalletApi reservation;
    private final MyWalletApi myWalletApi;
    private final EventApi eventApi;
    private final boolean naive;
    private final Cache<Long, Boolean> eventosExistentes = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public EventWalletGateway(EventWalletApi reservation, MyWalletApi myWalletApi, EventApi eventApi,
            @Value("${reservation.readpath:cas}") String readPath) {
        this.reservation = reservation;
        this.myWalletApi = myWalletApi;
        this.eventApi = eventApi;
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
        if (!myWalletApi.walletExists(ew.getUserId())) {
            throw new IllegalArgumentException("Carteira do usuário não encontrada");
        }
        if (!eventoExiste(ew.getEventId())) {
            throw new IllegalArgumentException("Evento não encontrado");
        }
        return reservation.addLink(ew, idempotencyKey, naive);
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
        if (userOk && !myWalletApi.walletExists(ew.getUserId())) sb.append("Carteira não encontrada. ");
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
