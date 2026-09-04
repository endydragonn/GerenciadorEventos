package com.gerenciador.eventos.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.POJO.EventCap;
import com.gerenciador.eventos.POJO.EventWallet;
import com.gerenciador.eventos.POJO.MyWallet;
import com.gerenciador.eventos.Repository.EventAvailabilityRepository;
import com.gerenciador.eventos.Repository.EventCapRepository;
import com.gerenciador.eventos.Repository.EventRepository;
import com.gerenciador.eventos.Repository.EventWalletRepository;
import com.gerenciador.eventos.Repository.MyWalletRepository;
import com.gerenciador.eventos.config.DatabaseConnection;

/**
 * Regras de inscrição/reserva em eventos.
 *
 * <p>A reserva é feita em duas etapas: {@link #addLink} debita a vaga em {@code eventcap}
 * (via UPDATE condicional atômico) e cria a linha {@code pending} com {@code expires_at};
 * {@link #confirm} efetiva ({@code pending -> confirmed}). O job
 * {@link ReservationExpiryService} devolve vagas de reservas {@code pending} vencidas.</p>
 *
 * <p>A checagem de capacidade em {@link #addLink} tem dois caminhos, selecionados pela
 * feature flag {@code reservation.readpath}:</p>
 * <ul>
 *   <li><b>{@code cas}</b> (default) — UPDATE condicional atômico em {@code eventcap};
 *       nunca oversella. Comportamento inalterado.</li>
 *   <li><b>{@code naive}</b> — lê o read model defasado {@code event_availability}
 *       (atualizado só pelo job {@link ReadModelRefreshService}) e insere em
 *       {@code walletevent} SEM tocar {@code eventcap}; OVERSELLA proporcional ao lag do
 *       read model. Braço de controle do experimento de concorrência da Fase 2.</li>
 * </ul>
 */
@Service
public class EventWalletService {

    @Autowired
    private EventWalletRepository eventWalletRepository;
    @Autowired
    private MyWalletRepository myWalletRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private EventCapRepository eventCapRepository;
    @Autowired
    private EventAvailabilityRepository eventAvailabilityRepository;
    @Autowired
    private DatabaseConnection databaseConnection;

    /** Caminho de leitura da reserva: {@code cas} (default) ou {@code naive}. */
    @Value("${reservation.readpath:cas}")
    private String readPath;

    /**
     * Cria uma reserva {@code pending}: debita a vaga em {@code eventcap} e grava a linha em
     * {@code walletevent}, tudo numa transação. Se o evento estiver lotado, nada é gravado.
     */
    public EventWallet addLink(EventWallet ew) {
        validateBasic(ew);

        MyWallet wallet = myWalletRepository.findByUserId(ew.getUserId());
        if (wallet == null) throw new IllegalArgumentException("Carteira do usuário não encontrada");
        if (eventRepository.findById(ew.getEventId()) == null)
            throw new IllegalArgumentException("Evento não encontrado");

        EventCap cap = eventCapRepository.findByEventId(ew.getEventId());
        if (cap == null) throw new IllegalArgumentException("Capacidade do evento não configurada");
        if (cap.getBuyTimeLimit() != null && LocalDateTime.now().isAfter(cap.getBuyTimeLimit()))
            throw new IllegalArgumentException("Janela de compra encerrada para este evento");

        if (eventWalletRepository.existsActive(ew.getUserId(), ew.getEventId()))
            throw new IllegalArgumentException("Vínculo já existente");

        int maxTimeInLine = cap.getMaxTimeInLine() != null ? cap.getMaxTimeInLine() : 120;
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(maxTimeInLine);

        // Caminho de leitura ingênuo (feature flag reservation.readpath=naive): lê o read
        // model defasado event_availability e insere SEM CAS. Oversella proporcional ao lag
        // do read model — é o braço de controle do experimento da Fase 2, não um bug.
        if ("naive".equalsIgnoreCase(readPath)) {
            Integer disp = eventAvailabilityRepository.findAvailable(ew.getEventId());
            if (disp == null)
                throw new IllegalArgumentException("Disponibilidade do evento ainda não calculada");
            if (disp == 0)
                throw new IllegalArgumentException("Evento lotado");
            // disp > 0, ou disp == -1 (ilimitado): segue — NUNCA chama eventCapRepository
            gravarReservaPendingSemCas(ew, expiresAt);
            ew.setStatus("pending");
            ew.setExpiresAt(expiresAt);
            return ew;
        }

        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // evento ilimitado (capacity = -1): reserva sempre permitida, sem CAS
                boolean reserved = cap.isUnlimited()
                        || eventCapRepository.tryReserve(conn, ew.getEventId());
                if (!reserved) {
                    conn.rollback();
                    throw new IllegalArgumentException("Evento lotado");
                }
                boolean created = eventWalletRepository.reservePending(
                        conn, ew.getUserId(), ew.getEventId(), expiresAt);
                if (!created) {
                    // corrida: já havia reserva ativa — desfaz o decremento
                    conn.rollback();
                    throw new IllegalArgumentException("Vínculo já existente");
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex instanceof RuntimeException ? (RuntimeException) ex
                        : new RuntimeException("Erro ao reservar: " + ex.getMessage(), ex);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao reservar: " + ex.getMessage(), ex);
        }

        ew.setStatus("pending");
        ew.setExpiresAt(expiresAt);
        return ew;
    }

    /**
     * Grava a reserva {@code pending} numa transação curta, SEM tocar o agregado
     * {@code eventcap} (sem CAS). Usado só pelo caminho de leitura ingênuo
     * ({@code reservation.readpath=naive}).
     */
    private void gravarReservaPendingSemCas(EventWallet ew, LocalDateTime expiresAt) {
        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                boolean created = eventWalletRepository.reservePending(
                        conn, ew.getUserId(), ew.getEventId(), expiresAt);
                if (!created) {
                    // Guarda defensiva intencional (espelha o caminho CAS): o existsActive
                    // acima já barra a duplicidade no fluxo normal; este ramo só é atingido
                    // numa corrida com outra inserção concorrente do mesmo par. Sem teste
                    // dedicado — não há como forçá-lo de forma determinística.
                    conn.rollback();
                    throw new IllegalArgumentException("Vínculo já existente");
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex instanceof RuntimeException ? (RuntimeException) ex
                        : new RuntimeException("Erro ao reservar: " + ex.getMessage(), ex);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao reservar: " + ex.getMessage(), ex);
        }
    }

    /** {@code pending -> confirmed}. */
    public EventWallet confirm(Long userId, Long eventId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("userId inválido");
        if (eventId == null || eventId <= 0) throw new IllegalArgumentException("eventId inválido");
        int rows = eventWalletRepository.confirm(userId, eventId);
        if (rows == 0) {
            String status = eventWalletRepository.currentStatus(userId, eventId);
            if ("confirmed".equals(status)) throw new IllegalArgumentException("Reserva já confirmada");
            if ("expired".equals(status)) throw new IllegalArgumentException("Reserva expirada — refaça a inscrição");
            throw new IllegalArgumentException("Nenhuma reserva pendente para confirmar");
        }
        EventWallet ew = new EventWallet(userId, eventId);
        ew.setStatus("confirmed");
        return ew;
    }

    /**
     * Cancela a inscrição e devolve a vaga ao evento, tudo numa transação.
     *
     * <p>O cancelamento é um UPDATE condicional único ({@code cancelIfActive}): a vaga só é
     * devolvida se ESTA chamada foi a que tirou a reserva do estado ativo
     * ({@code pending}/{@code confirmed}). Duas remoções concorrentes — ou uma corrida com o
     * job de expiração — não conseguem devolver a mesma vaga duas vezes (sem overselling).</p>
     *
     * <p>Se a reserva já estava {@code cancelled}/{@code expired} ou não existe, a operação é
     * um no-op silencioso (semântica idempotente de DELETE): não é erro cancelar de novo.</p>
     */
    public void removeLink(Long userId, Long eventId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("userId inválido");
        if (eventId == null || eventId <= 0) throw new IllegalArgumentException("eventId inválido");

        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int cancelada = eventWalletRepository.cancelIfActive(conn, userId, eventId);
                if (cancelada == 1) {
                    eventCapRepository.release(conn, eventId);
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao cancelar inscrição: " + ex.getMessage(), ex);
        }
    }

    public List<EventWallet> listByUser(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("userId inválido");
        return eventWalletRepository.findAllByUserId(userId);
    }

    public String getValidationErrors(EventWallet ew) {
        StringBuilder sb = new StringBuilder();
        if (ew.getUserId() == null || ew.getUserId() <= 0) sb.append("user_id é obrigatório e deve ser positivo. ");
        if (ew.getEventId() == null || ew.getEventId() <= 0) sb.append("event_id é obrigatório e deve ser positivo. ");
        if (ew.getUserId() != null && ew.getUserId() > 0) {
            if (myWalletRepository.findByUserId(ew.getUserId()) == null) sb.append("Carteira não encontrada. ");
        }
        if (ew.getEventId() != null && ew.getEventId() > 0) {
            if (eventRepository.findById(ew.getEventId()) == null) sb.append("Evento não encontrado. ");
        }
        if (ew.getUserId() != null && ew.getEventId() != null
            && ew.getUserId() > 0 && ew.getEventId() > 0
            && eventWalletRepository.existsActive(ew.getUserId(), ew.getEventId())) {
            sb.append("Vínculo já existente. ");
        }
        return sb.toString().trim();
    }

    private void validateBasic(EventWallet ew) {
        if (ew.getUserId() == null || ew.getUserId() <= 0)
            throw new IllegalArgumentException("user_id é obrigatório e deve ser positivo");
        if (ew.getEventId() == null || ew.getEventId() <= 0)
            throw new IllegalArgumentException("event_id é obrigatório e deve ser positivo");
    }
}
