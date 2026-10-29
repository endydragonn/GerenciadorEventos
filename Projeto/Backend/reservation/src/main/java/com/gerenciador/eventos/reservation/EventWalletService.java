package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.stockclient.StockClient;
import com.gerenciador.eventos.stockclient.api.EventCap;

/**
 * Regras de inscrição/reserva em eventos: o lado WALLET do split stock + wallet (issue #47).
 *
 * <p>A reserva é uma saga orquestrada por {@link ReservationSaga}: o stock segura a vaga (hold) e este serviço grava a reserva
 * {@code pending} com {@code expires_at}; {@link #confirm} efetiva ({@code pending -> confirmed}). O job
 * {@link ReservationExpiryService} expira as {@code pending} vencidas. Cancelar e expirar NÃO falam com o stock: appendam o evento com o
 * {@code holdId} e o consumer do stock devolve a vaga (idempotente por hold).</p>
 *
 * <p>Os dois caminhos de capacidade são escolhidos por quem CHAMA (o gateway decide pela feature flag {@code reservation.readpath} do
 * monólito e manda o modo no pedido): {@code cas} (o hold debita o {@code eventcap} no stock; nunca oversella) e {@code naive} (o hold NÃO
 * debita; a disponibilidade, da projeção ASSÍNCRONA {@code event_availability}, é lida na chamada anterior do gateway,
 * {@link #disponibilidade}, então a janela check-then-act inclui a rede; OVERSELLA proporcional ao lag do consumer, é o braço de
 * controle do experimento).</p>
 *
 * <p><b>Fronteira de serviço (issue #33).</b> Este serviço não enxerga {@code users}/{@code mywallet}/{@code event}: a carteira do
 * usuário e a existência do evento no catálogo são conferidas pelo gateway; a existência do {@code eventcap} (no stock) é o "evento
 * existe" ("Capacidade do evento não configurada").</p>
 *
 * <p><b>Event sourcing (issue #29).</b> {@code walletevent} é a projeção inline do stream {@code reservation.wallet}: cada transição é um
 * UPDATE condicional com {@code RETURNING version} e o evento ({@link ReservationEvents}) é appendado SÓ quando ele afetou 1 linha, na
 * mesma transação.</p>
 */
@Service
public class EventWalletService {

    @Autowired
    private EventWalletRepository eventWalletRepository;
    @Autowired
    private EventAvailabilityRepository eventAvailabilityRepository;
    @Autowired
    private ReservationSaga reservationSaga;
    @Autowired
    private StockClient stockClient;
    @Autowired
    private ReservationEvents reservationEvents;
    @Autowired
    private DatabaseConnection databaseConnection;

    /** Reserva no braço {@code cas} com {@code Idempotency-Key} gerada no servidor (fica em {@code getIdempotencyKey()}). */
    public EventWallet addLink(EventWallet ew) {
        return addLink(ew, null, false);
    }

    /** Reserva no braço {@code cas} com a {@code Idempotency-Key} informada. */
    public EventWallet addLink(EventWallet ew, String idempotencyKey) {
        return addLink(ew, idempotencyKey, false);
    }

    /**
     * Cria uma reserva {@code pending} pela saga ({@link ReservationSaga}): o stock segura a vaga e a reserva é gravada depois, com o
     * {@code expiresAt} calculado a partir do {@code maxTimeInLine} do stock. Se o evento estiver lotado, nada é gravado e a chave não é
     * consumida.
     *
     * <p><b>Idempotência (issues #24 e #47).</b> A chave é o log da saga ({@code idempotency_key}). Repetição com a mesma chave e o mesmo
     * usuário/evento devolve o resultado da primeira ({@code pending} + {@code expiresAt} salvo) sem tocar o stock; o mesmo usuário com
     * outro evento => {@link com.gerenciador.eventos.reservationclient.api.IdempotencyConflictException}. A chave tem escopo por usuário
     * (issue #129). Reserva que falha não consome a chave (a mesma chave pode ser repetida). Vale para os caminhos {@code cas} e
     * {@code naive}. Sem chave (null/em branco), o servidor gera uma.</p>
     *
     * @param naive {@code true} = braço de controle: o hold não debita (a checagem de disponibilidade é do chamador)
     * @throws SagaIndisponivelException 503: reserva em andamento ou não concluída; repetir com a mesma chave é seguro
     */
    public EventWallet addLink(EventWallet ew, String idempotencyKey, boolean naive) {
        validateBasic(ew);
        boolean chaveDoCliente = idempotencyKey != null && !idempotencyKey.isBlank();
        String chave = normalizarChave(idempotencyKey);
        ew.setIdempotencyKey(chave);
        return reservationSaga.reservar(ew, chave, chaveDoCliente, naive);
    }

    /**
     * Resultado salvo da reserva feita com a chave (a mesma resposta que a repetição de {@link #addLink} devolve), ou
     * {@code null} se a chave não existe ou a saga não está {@code reserved}. O gateway chama quando o braço naive vê o evento "lotado"
     * mas o cliente mandou a chave: a resposta certa de uma repetição é a primeira reserva, não "lotado". Chave de outro evento => conflito.
     */
    public EventWallet replay(Long userId, Long eventId, String idempotencyKey) {
        validateBasic(new EventWallet(userId, eventId));
        return reservationSaga.replay(userId, eventId, idempotencyKey);
    }

    /**
     * Disponibilidade DEFASADA do evento (braço naive): {@code capacity - ativos} da projeção assíncrona, {@code -1} =
     * ilimitado, {@code null} = o evento não tem {@code eventcap}. A capacidade vem do stock (GET do eventcap); nunca lê
     * {@code eventcap.quant}.
     * @throws SagaIndisponivelException 503 se o stock falhar (fora, timeout, 5xx): nunca 404, que o gateway traduziria em "Capacidade do
     *         evento não configurada" para um evento que tem capacidade
     */
    public Integer disponibilidade(Long eventId) {
        EventCap cap;
        try {
            cap = stockClient.findByEventId(eventId);
        } catch (RuntimeException ex) {
            throw new SagaIndisponivelException("Stock indisponível, tente novamente", ex);
        }
        return cap == null ? null : eventAvailabilityRepository.findAvailable(eventId, cap.getCapacity());
    }

    /** Existe reserva ativa ({@code pending}/{@code confirmed}) do usuário para o evento? */
    public boolean existsActive(Long userId, Long eventId) {
        return eventWalletRepository.existsActive(userId, eventId);
    }

    private static String normalizarChave(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return UUID.randomUUID().toString();
        if (idempotencyKey.length() > 255)
            throw new IllegalArgumentException("Idempotency-Key deve ter no máximo 255 caracteres");
        return idempotencyKey;
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
     * Cancela a inscrição, numa transação. A vaga NÃO é devolvida aqui (#47): o evento {@code ReservationCancelled} leva o
     * {@code holdId} e o consumer do stock a devolve, idempotente por hold (com o stock fora, o cancelamento responde igual e a
     * devolução espera no tópico).
     *
     * <p>O cancelamento é um UPDATE condicional único ({@code cancelIfActive}): o evento só é appendado
     * se ESTA chamada foi a que tirou a reserva do estado ativo
     * ({@code pending}/{@code confirmed}). Duas remoções concorrentes — ou uma corrida com o
     * job de expiração — não geram dois eventos (a vaga volta uma vez só).</p>
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
                EventWalletRepository.Transicao t = eventWalletRepository.cancelIfActive(conn, userId, eventId);
                if (t.aplicada()) {
                    reservationEvents.cancelled(conn, userId, eventId, t.versao(), t.holdId());
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

    private void validateBasic(EventWallet ew) {
        if (ew.getUserId() == null || ew.getUserId() <= 0)
            throw new IllegalArgumentException("user_id é obrigatório e deve ser positivo");
        if (ew.getEventId() == null || ew.getEventId() <= 0)
            throw new IllegalArgumentException("event_id é obrigatório e deve ser positivo");
    }
}
