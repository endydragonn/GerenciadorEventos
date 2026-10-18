package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.reservation.api.EventCap;
import com.gerenciador.eventos.reservation.api.EventWallet;
import com.gerenciador.eventos.reservation.api.EventWalletApi;
import com.gerenciador.eventos.reservation.api.IdempotencyConflictException;
import com.gerenciador.eventos.reservation.api.MyWallet;
import com.gerenciador.eventos.catalog.api.EventApi;
import com.gerenciador.eventos.shared.DatabaseConnection;

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
 *   <li><b>{@code naive}</b> — lê a disponibilidade calculada sobre a projeção ASSÍNCRONA
 *       {@code event_availability} (consumer do tópico {@code reservation.wallet}) e insere em
 *       {@code walletevent} SEM tocar {@code eventcap}; OVERSELLA proporcional ao lag do
 *       consumer. Braço de controle do experimento de concorrência.</li>
 * </ul>
 *
 * <p><b>Event sourcing (issue #29).</b> {@code walletevent} é a projeção inline do stream
 * {@code reservation.wallet}: cada transição é um UPDATE condicional com {@code RETURNING version} e o evento
 * ({@link ReservationEvents}) é appendado SÓ quando ele afetou 1 linha, na mesma transação. {@code eventcap} + CAS
 * continuam relacionais e síncronos.</p>
 */
@Service
public class EventWalletService implements EventWalletApi {

    @Autowired
    private EventWalletRepository eventWalletRepository;
    @Autowired
    private MyWalletRepository myWalletRepository;
    @Autowired
    private EventApi eventApi;
    @Autowired
    private EventCapRepository eventCapRepository;
    @Autowired
    private EventAvailabilityRepository eventAvailabilityRepository;
    @Autowired
    private CapacityReserver capacityReserver;
    @Autowired
    private ReservationWriter reservationWriter;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired
    private ReservationEvents reservationEvents;
    @Autowired
    private DatabaseConnection databaseConnection;

    /** Caminho de leitura da reserva: {@code cas} (default) ou {@code naive}. */
    @Value("${reservation.readpath:cas}")
    private String readPath;

    /** Reserva com {@code Idempotency-Key} gerada no servidor (fica em {@code getIdempotencyKey()}). */
    public EventWallet addLink(EventWallet ew) {
        return addLink(ew, null);
    }

    /**
     * Cria uma reserva {@code pending}: debita a vaga em {@code eventcap} e grava a linha em
     * {@code walletevent}, tudo numa transação. Se o evento estiver lotado, nada é gravado.
     *
     * <p><b>Idempotência (issue #24).</b> A chave é gravada em {@code idempotency_key} na MESMA
     * transação do CAS e da reserva. Repetição com a mesma chave e o mesmo usuário/evento devolve o
     * resultado da primeira ({@code pending} + {@code expiresAt} salvo) sem tocar {@code eventcap};
     * o mesmo usuário com outro evento ⇒ {@link IdempotencyConflictException}. A chave tem escopo por
     * usuário (issue #129): outro usuário com a mesma chave é uma reserva independente. Reserva que falha faz rollback
     * e não deixa chave (a mesma chave pode ser repetida). Vale para os caminhos {@code cas} e
     * {@code naive}. Sem chave (null/em branco), o servidor gera uma.</p>
     */
    public EventWallet addLink(EventWallet ew, String idempotencyKey) {
        validateBasic(ew);
        boolean chaveDoCliente = idempotencyKey != null && !idempotencyKey.isBlank();
        String chave = normalizarChave(idempotencyKey);
        ew.setIdempotencyKey(chave);

        EventCap cap;
        try {
            cap = validarPreRequisitos(ew);
            // Caminho de leitura ingênuo (feature flag reservation.readpath=naive): lê o read
            // model defasado event_availability e reserva SEM CAS. Oversella proporcional ao lag
            // do read model — é o braço de controle do experimento da Fase 2, não um bug.
            if (naive()) validarDisponibilidadeNaive(ew.getEventId());
        } catch (IllegalArgumentException ex) {
            // Se é a repetição de uma reserva que já vale, as checagens acima ("Vínculo já
            // existente", "Evento lotado" no naive...) falham; a resposta certa é a salva.
            // Chave gerada pelo servidor nunca existe: não consulta (rejeição é o caminho quente
            // sob carga, e o naive não pode pagar um SELECT a mais que o cas).
            if (chaveDoCliente) {
                IdempotencyKeyRepository.Salvo salvo = idempotencyKeyRepository.find(chave, ew.getUserId());
                if (salvo != null) return resultadoSalvo(salvo, ew);
            }
            throw ex;
        }

        int maxTimeInLine = cap.getMaxTimeInLine() != null ? cap.getMaxTimeInLine() : 120;
        // micros: o Postgres guarda microssegundos; a repetição devolve o mesmo expiresAt da primeira
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(maxTimeInLine).truncatedTo(ChronoUnit.MICROS);

        // evento ilimitado (capacity = -1) e caminho ingênuo: reserva sem CAS
        IdempotencyKeyRepository.Salvo salvo =
                reservarNaTransacao(ew, chave, cap.isUnlimited() || naive(), expiresAt);
        if (salvo != null) return resultadoSalvo(salvo, ew);

        ew.setStatus("pending");
        ew.setExpiresAt(expiresAt);
        return ew;
    }

    private boolean naive() {
        return "naive".equalsIgnoreCase(readPath);
    }

    private static String normalizarChave(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return UUID.randomUUID().toString();
        if (idempotencyKey.length() > 255)
            throw new IllegalArgumentException("Idempotency-Key deve ter no máximo 255 caracteres");
        return idempotencyKey;
    }

    private EventCap validarPreRequisitos(EventWallet ew) {
        MyWallet wallet = myWalletRepository.findByUserId(ew.getUserId());
        if (wallet == null) throw new IllegalArgumentException("Carteira do usuário não encontrada");
        if (!eventApi.exists(ew.getEventId()))
            throw new IllegalArgumentException("Evento não encontrado");

        EventCap cap = eventCapRepository.findByEventId(ew.getEventId());
        if (cap == null) throw new IllegalArgumentException("Capacidade do evento não configurada");
        if (cap.getBuyTimeLimit() != null && LocalDateTime.now().isAfter(cap.getBuyTimeLimit()))
            throw new IllegalArgumentException("Janela de compra encerrada para este evento");

        if (eventWalletRepository.existsActive(ew.getUserId(), ew.getEventId()))
            throw new IllegalArgumentException("Vínculo já existente");
        return cap;
    }

    private void validarDisponibilidadeNaive(Long eventId) {
        Integer disp = eventAvailabilityRepository.findAvailable(eventId);
        // eventcap LEFT JOIN projeção: só é null sem eventcap (validarPreRequisitos já barra); guarda contra NPE
        if (disp == null)
            throw new IllegalArgumentException("Capacidade do evento não configurada");
        if (disp == 0)
            throw new IllegalArgumentException("Evento lotado");
        // disp > 0, ou disp == -1 (ilimitado): segue
    }

    /** Resultado da primeira reserva do usuário com a chave; outro evento ⇒ conflito (HTTP 422). */
    private EventWallet resultadoSalvo(IdempotencyKeyRepository.Salvo salvo, EventWallet ew) {
        if (!salvo.eventId().equals(ew.getEventId()))
            throw new IdempotencyConflictException(
                    "Idempotency-Key já usada em outra requisição (evento diferente)");
        ew.setStatus("pending");
        ew.setExpiresAt(salvo.expiresAt());
        return ew;
    }

    /**
     * Transação única da reserva, numa {@link Connection}: reivindica a chave → decrementa a
     * capacidade ({@link CapacityReserver}, pulado se {@code semCas}) → grava a reserva
     * ({@link ReservationWriter}; a versão sai da guarda) → appenda {@code SlotReserved} → commit. Qualquer falha
     * após o decremento faz rollback da conexão inteira: é o ponto de compensação (a vaga, a chave, a reserva e o
     * evento voltam juntos). O braço {@code naive} também appenda (só não toca {@code eventcap}), então a projeção
     * vê as reservas dos dois braços.
     *
     * @return {@code null} se reservou; o {@link IdempotencyKeyRepository.Salvo} existente se a
     *         chave já estava gravada (repetição, ou perdeu a corrida para outra com a mesma chave).
     */
    private IdempotencyKeyRepository.Salvo reservarNaTransacao(
            EventWallet ew, String chave, boolean semCas, LocalDateTime expiresAt) {
        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!idempotencyKeyRepository.claim(conn, chave, ew.getUserId(), ew.getEventId(), expiresAt)) {
                    // já existe (o INSERT esperou a transação concorrente terminar): nada foi tocado
                    IdempotencyKeyRepository.Salvo salvo = idempotencyKeyRepository.find(conn, chave, ew.getUserId());
                    conn.rollback();
                    if (salvo == null) throw new IllegalStateException("Idempotency-Key sumiu durante a reserva");
                    return salvo;
                }
                if (!semCas && !capacityReserver.tryReserve(conn, ew.getEventId()))
                    throw new IllegalArgumentException("Evento lotado");
                int versao = reservationWriter.reservePending(conn, ew.getUserId(), ew.getEventId(), expiresAt);
                // versao 0 = corrida: já havia reserva ativa. Conflito no append não deveria ocorrer (a versão sai
                // da guarda), mas tem a mesma resposta. Nos dois casos o rollback desfaz decremento e chave.
                if (versao == 0 || !reservationEvents.slotReserved(
                        conn, ew.getUserId(), ew.getEventId(), versao, expiresAt, chave))
                    throw new IllegalArgumentException("Vínculo já existente");
                conn.commit();
                return null;
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
        if (!confirmarNaTransacao(userId, eventId)) {
            String status = eventWalletRepository.currentStatus(userId, eventId);
            if ("confirmed".equals(status)) throw new IllegalArgumentException("Reserva já confirmada");
            if ("expired".equals(status)) throw new IllegalArgumentException("Reserva expirada — refaça a inscrição");
            throw new IllegalArgumentException("Nenhuma reserva pendente para confirmar");
        }
        EventWallet ew = new EventWallet(userId, eventId);
        ew.setStatus("confirmed");
        return ew;
    }

    /** Guarda {@code pending -> confirmed} + append de {@code ReservationConfirmed}, numa transação. */
    private boolean confirmarNaTransacao(Long userId, Long eventId) {
        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int versao = eventWalletRepository.confirm(conn, userId, eventId);
                if (versao > 0) reservationEvents.confirmed(conn, userId, eventId, versao);
                conn.commit();
                return versao > 0;
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao confirmar reserva: " + ex.getMessage(), ex);
        }
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
                int versao = eventWalletRepository.cancelIfActive(conn, userId, eventId);
                if (versao > 0) {
                    eventCapRepository.release(conn, eventId);
                    reservationEvents.cancelled(conn, userId, eventId, versao);
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
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
            if (!eventApi.exists(ew.getEventId())) sb.append("Evento não encontrado. ");
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
