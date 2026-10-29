package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.reservation.IdempotencyKeyRepository.Linha;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.reservationclient.api.IdempotencyConflictException;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.stockclient.StockClient;
import com.gerenciador.eventos.stockclient.api.HoldRequest;
import com.gerenciador.eventos.stockclient.api.HoldResponse;

/**
 * Saga da reserva (issue #47): o {@code reservation} é o lado wallet e ORQUESTRA o stock, que guarda a capacidade e o ledger de holds.
 * O estado da saga é a linha de {@code reservation.idempotency_key} (a reivindicação da chave é o início). Passos:
 * <ol>
 *   <li>pré-checagem ("Vínculo já existente", ou o resultado salvo se a chave do cliente já vale);</li>
 *   <li><b>tx1</b>: INSERT da chave com um {@code hold_id} novo em {@code reserving}, comitado antes de chamar o stock (se a chave já
 *       existe, lê o estado dela: ver {@link #abrir});</li>
 *   <li>chamada ao stock ({@code POST /internal/holds}) SEM nenhuma conexão nem transação aberta: o Supavisor está em transaction mode e uma
 *       transação do wallet esperando o stock prenderia uma conexão real do pool de que o próprio stock precisa;</li>
 *   <li><b>tx2</b>: CAS {@code reserving -> reserved} por (PK, hold_id), {@code reservePending} com o hold e {@code SlotReserved} com o
 *       {@code holdId}, no mesmo commit.</li>
 * </ol>
 * Toda falha depois do hold vira compensação ({@code release}), nunca rollback de um lado só. Detalhes e a tabela de falhas na spec
 * {@code docs/superpowers/specs/2026-10-29-saga-stock-wallet-design.md}. O reaper das órfãs (processo morto entre o hold e a tx2) é da
 * passada b.
 */
@Service
public class ReservationSaga {

    private static final Logger log = LoggerFactory.getLogger(ReservationSaga.class);
    /** Latência dos passos por reserva ({@code tx1_ms}, {@code stock_ms}, {@code tx2_ms}, {@code total_ms}); DEBUG, desligado por padrão. */
    private static final Logger timing = LoggerFactory.getLogger("saga.timing");

    static final String EM_ANDAMENTO = "Reserva em andamento, tente novamente";
    static final String NAO_CONCLUIDA = "Reserva não concluída, tente novamente";
    private static final long ESPERA_ENTRE_LEITURAS_MS = 50;

    @Autowired
    private EventWalletRepository eventWalletRepository;
    @Autowired
    private IdempotencyKeyRepository keys;
    @Autowired
    private ReservationEvents reservationEvents;
    @Autowired
    private DatabaseConnection databaseConnection;
    @Autowired
    private StockClient stockClient;

    /** Quanto o pedido espera outra saga da MESMA chave em {@code reserving}; abaixo do timeout de resposta dos chamadores (10 s). */
    @Value("${reservation.saga.wait-ms:8000}")
    private long esperaMs;

    /** Fim da tx2. */
    private enum Fim { OK, CAS_PERDIDO, VINCULO }

    /**
     * @param chave a {@code Idempotency-Key} (a do cliente, ou a gerada)
     * @param naive {@code true} = braço de controle: o hold não debita o {@code eventcap} no stock
     */
    public EventWallet reservar(EventWallet ew, String chave, boolean chaveDoCliente, boolean naive) {
        long t0 = System.nanoTime();
        Long userId = ew.getUserId();
        Long eventId = ew.getEventId();
        // 1. pré-checagem, fora de transação (como antes: a reserva ativa devolve o salvo se a chave do cliente já vale)
        if (eventWalletRepository.existsActive(userId, eventId)) {
            Linha salvo = chaveDoCliente ? keys.lerReservada(chave, userId) : null;
            if (salvo != null) {
                return resultadoSalvo(salvo, ew);
            }
            throw new IllegalArgumentException("Vínculo já existente");
        }
        // 2. tx1
        UUID holdId = UUID.randomUUID();
        Linha salvo = abrir(ew, chave, holdId);
        if (salvo != null) {
            return resultadoSalvo(salvo, ew);
        }
        long t1 = System.nanoTime();
        // 3. stock, sem conexão aberta
        HoldResponse hold = reservarNoStock(ew, chave, holdId, naive);
        long t2 = System.nanoTime();
        // micros: o Postgres guarda microssegundos; a repetição devolve o mesmo expiresAt da primeira
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(hold.maxTimeInLine()).truncatedTo(ChronoUnit.MICROS);
        // 4. tx2
        concluir(ew, chave, holdId, expiresAt);
        long t3 = System.nanoTime();
        if (timing.isDebugEnabled()) {
            timing.debug("tx1_ms={} stock_ms={} tx2_ms={} total_ms={}", (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000,
                    (t3 - t2) / 1_000_000, (t3 - t0) / 1_000_000);
        }
        ew.setStatus("pending");
        ew.setExpiresAt(expiresAt);
        return ew;
    }

    /** Resultado salvo da reserva com a chave (só linha {@code reserved}), ou {@code null}; outro evento => conflito (422). */
    public EventWallet replay(Long userId, Long eventId, String chave) {
        Linha salvo = keys.lerReservada(chave, userId);
        return salvo == null ? null : resultadoSalvo(salvo, new EventWallet(userId, eventId));
    }

    /** Resultado da primeira reserva do usuário com a chave; outro evento => conflito (HTTP 422). */
    private static EventWallet resultadoSalvo(Linha salvo, EventWallet ew) {
        if (!salvo.eventId().equals(ew.getEventId())) {
            throw new IdempotencyConflictException("Idempotency-Key já usada em outra requisição (evento diferente)");
        }
        ew.setStatus("pending");
        ew.setExpiresAt(salvo.expiresAt());
        return ew;
    }

    /**
     * tx1. @return {@code null} se ESTE pedido é o dono da saga (inseriu a chave ou reabriu uma {@code compensated} com o seu hold);
     *         senão a linha {@code reserved} a devolver. Chave em {@code reserving} de outro pedido: espera relendo a cada 50 ms, sem
     *         conexão presa, até {@code esperaMs} (linha que sumiu, porque o dono recebeu 400 e a apagou, volta ao INSERT; vencida => 503).
     *         {@code compensating} => 503 na hora (o fim depende do reaper e pode demorar). Outro evento => 422.
     */
    private Linha abrir(EventWallet ew, String chave, UUID holdId) {
        Long userId = ew.getUserId();
        long limite = System.nanoTime() + esperaMs * 1_000_000;
        while (true) {
            if (keys.iniciar(chave, userId, ew.getEventId(), holdId)) {
                return null;
            }
            Linha l = keys.ler(chave, userId);
            if (l != null) {
                if (!l.eventId().equals(ew.getEventId())) {
                    throw new IdempotencyConflictException("Idempotency-Key já usada em outra requisição (evento diferente)");
                }
                switch (l.status()) {
                    case IdempotencyKeyRepository.RESERVED -> {
                        return l;
                    }
                    case IdempotencyKeyRepository.COMPENSATING -> throw new SagaIndisponivelException(EM_ANDAMENTO);
                    case IdempotencyKeyRepository.COMPENSATED -> {
                        if (keys.reabrir(chave, userId, l.holdId(), holdId)) {
                            return null;
                        }
                    }
                    default -> esperar(chave, userId, limite);
                }
            }
            if (System.nanoTime() > limite) {
                throw new SagaIndisponivelException(EM_ANDAMENTO);
            }
        }
    }

    /** Relê a chave até ela sair de {@code reserving} (ou sumir); vencido o prazo => 503. */
    private void esperar(String chave, Long userId, long limite) {
        while (true) {
            try {
                Thread.sleep(ESPERA_ENTRE_LEITURAS_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SagaIndisponivelException(EM_ANDAMENTO, ex);
            }
            Linha l = keys.ler(chave, userId);
            if (l == null || !IdempotencyKeyRepository.RESERVING.equals(l.status())) {
                return;
            }
            if (System.nanoTime() > limite) {
                throw new SagaIndisponivelException(EM_ANDAMENTO);
            }
        }
    }

    /**
     * 400 do stock: apaga a linha (rejeição não consome a chave e não é compensação: não houve débito) e repassa o 400. 409 (lápide),
     * timeout, 5xx, conexão recusada ou 401: compensação inline e 503.
     */
    private HoldResponse reservarNoStock(EventWallet ew, String chave, UUID holdId, boolean naive) {
        try {
            HoldResponse hold = stockClient.hold(new HoldRequest(holdId, ew.getUserId(), ew.getEventId(),
                    naive ? HoldRequest.NAIVE : HoldRequest.CAS));
            // resposta vazia/inválida do stock: cai na compensação (o hold pode ter sido criado), nunca num NPE com a linha em reserving
            if (hold == null || hold.maxTimeInLine() <= 0) {
                throw new IllegalStateException("Resposta inválida do stock ao hold " + holdId);
            }
            return hold;
        } catch (IllegalArgumentException ex) {
            keys.apagarReserving(chave, ew.getUserId(), holdId);
            throw ex;
        } catch (RuntimeException ex) {
            compensarInline(ew, chave, holdId);
            throw new SagaIndisponivelException(NAO_CONCLUIDA, ex);
        }
    }

    private void concluir(EventWallet ew, String chave, UUID holdId, LocalDateTime expiresAt) {
        Fim fim;
        try {
            fim = tx2(ew, chave, holdId, expiresAt);
        } catch (RuntimeException ex) {
            // DESFECHO INCERTO: a tx2 pode ter comitado (ack perdido, conexão morta no commit, no setAutoCommit ou no close). Compensar
            // aqui liberaria o hold de uma reserva que existe. Não compensa: 503, e a linha fica para o reaper (reserving => ele compensa
            // depois de ORPHAN_MS; reserved => ninguém toca)
            log.warn("[Saga] erro na tx2 (hold {}): desfecho incerto, sem compensação inline; o reaper decide: {}", holdId, ex.toString());
            throw new SagaIndisponivelException(NAO_CONCLUIDA, ex);
        }
        switch (fim) {
            case OK -> { }
            case CAS_PERDIDO -> {
                // o reaper assumiu a saga, ou a linha já não é deste hold: o release (idempotente) é o que sobra a fazer
                liberarQuieto(ew, holdId);
                throw new SagaIndisponivelException(NAO_CONCLUIDA);
            }
            case VINCULO -> {
                compensarInline(ew, chave, holdId);
                throw new IllegalArgumentException("Vínculo já existente");
            }
        }
    }

    /**
     * tx2: CAS da saga + {@code reservePending} + {@code SlotReserved}, num commit. Só os retornos {@link Fim#CAS_PERDIDO} e
     * {@link Fim#VINCULO} têm desfecho conhecido (rollback explícito, nada comitou). Qualquer exceção, inclusive do próprio commit, do
     * {@code setAutoCommit} ou do {@code close}, propaga e é tratada por {@link #concluir} como desfecho incerto.
     */
    private Fim tx2(EventWallet ew, String chave, UUID holdId, LocalDateTime expiresAt) {
        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (keys.concluir(conn, chave, ew.getUserId(), holdId, expiresAt) == 0) {
                    conn.rollback();
                    return Fim.CAS_PERDIDO;
                }
                int versao = eventWalletRepository.reservePending(conn, ew.getUserId(), ew.getEventId(), expiresAt, holdId);
                // versao 0 = corrida: já havia reserva ativa. Conflito no append não deveria ocorrer (a versão sai da guarda), mas tem
                // a mesma resposta. Nos dois casos o rollback desfaz o CAS da saga e o walletevent.
                if (versao == 0 || !reservationEvents.slotReserved(conn, ew.getUserId(), ew.getEventId(), versao, expiresAt, chave,
                        holdId)) {
                    conn.rollback();
                    return Fim.VINCULO;
                }
                conn.commit();
                return Fim.OK;
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao reservar: " + ex.getMessage(), ex);
        }
    }

    /**
     * Compensação inline (o pedido ainda está no ar): {@code reserving -> compensating} (commit) -> {@code release} no stock ->
     * {@code compensating -> compensated}. Com 0 linhas no primeiro passo a linha é de outro dono, do reaper ou JÁ {@code reserved} com
     * este hold: relê a linha e só libera se NÃO for {@code reserved} com este hold (se a releitura falhar, não libera: sobra para o
     * reaper). Se o {@code release} falha, a linha fica {@code compensating} para o reaper. Package-private para o teste.
     */
    void compensarInline(EventWallet ew, String chave, UUID holdId) {
        boolean minha = keys.paraCompensando(chave, ew.getUserId(), holdId);
        if (!minha) {
            Linha atual;
            try {
                atual = keys.ler(chave, ew.getUserId());
            } catch (RuntimeException ex) {
                log.warn("[Saga] releitura da saga do hold {} falhou ({}): sem release, fica para o reaper", holdId, ex.toString());
                return;
            }
            if (atual != null && IdempotencyKeyRepository.RESERVED.equals(atual.status()) && holdId.equals(atual.holdId())) {
                return; // a reserva comitou: liberar o hold seria overselling
            }
        }
        try {
            stockClient.release(holdId, ew.getEventId(), ew.getUserId());
        } catch (RuntimeException ex) {
            log.warn("[Saga] release do hold {} falhou ({}): a saga fica compensating para o reaper", holdId, ex.toString());
            return;
        }
        if (minha) {
            keys.compensada(chave, ew.getUserId(), holdId, "inline");
        }
    }

    private void liberarQuieto(EventWallet ew, UUID holdId) {
        try {
            stockClient.release(holdId, ew.getEventId(), ew.getUserId());
        } catch (RuntimeException ex) {
            log.warn("[Saga] release do hold {} (CAS da tx2 perdido) falhou: {}", holdId, ex.toString());
        }
    }
}
