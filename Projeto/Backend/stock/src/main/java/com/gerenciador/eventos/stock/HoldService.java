package com.gerenciador.eventos.stock;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.gerenciador.eventos.stockclient.api.EventCap;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.stockclient.api.HoldLiberadoException;
import com.gerenciador.eventos.stockclient.api.HoldRequest;
import com.gerenciador.eventos.stockclient.api.HoldResponse;

/**
 * Passos da saga que moram no stock (issue #47): reservar e liberar um hold, cada um numa transação curta com o {@code hold_id} como
 * chave de idempotência. O orquestrador (reservation) nunca segura conexão durante estas chamadas.
 */
@Service
public class HoldService {

    /** @param criado {@code true} = 201 (hold novo); {@code false} = 200 (repetição de um hold ainda {@code held}) */
    public record Reservado(HoldResponse resposta, boolean criado) { }

    private final DatabaseConnection databaseConnection;
    private final EventCapRepository eventCapRepository;
    private final HoldRepository holdRepository;

    public HoldService(DatabaseConnection databaseConnection, EventCapRepository eventCapRepository, HoldRepository holdRepository) {
        this.databaseConnection = databaseConnection;
        this.eventCapRepository = eventCapRepository;
        this.holdRepository = holdRepository;
    }

    /**
     * Reserva a vaga. Numa transação: (1) lê o {@code eventcap} (sem linha = "Capacidade do evento não configurada"); (2) {@code INSERT
     * hold ON CONFLICT DO NOTHING}, e se o hold já existe é repetição ({@code held} = devolve o mesmo, sem tocar no {@code eventcap};
     * {@code released} = lápide, {@link HoldLiberadoException}); (3) só então a janela (passada = rollback, o INSERT some, "Janela de
     * compra encerrada para este evento"); (4) só no modo {@code cas} de evento limitado, o CAS como ÚLTIMO comando antes do commit (0
     * linhas = rollback, o hold some junto, "Evento lotado").
     */
    public Reservado reservar(HoldRequest p) {
        validar(p);
        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                EventCap cap = eventCapRepository.findByEventId(conn, p.eventId());
                if (cap == null) throw new IllegalArgumentException("Capacidade do evento não configurada");
                HoldResponse resposta = new HoldResponse(p.holdId(), cap.getMaxTimeInLine());
                boolean debita = HoldRequest.CAS.equals(p.mode()) && !cap.isUnlimited();
                if (!holdRepository.inserirHeld(conn, p.holdId(), p.eventId(), p.userId(), debita)) {
                    String status = holdRepository.status(conn, p.holdId());
                    conn.rollback();
                    if ("released".equals(status)) throw new HoldLiberadoException("Hold liberado");
                    return new Reservado(resposta, false);
                }
                // A janela vem DEPOIS da detecção de repetição: um hold que já debitou, repetido após a janela fechar, é 200 (um 400
                // aqui faria o wallet crer que não houve débito e a vaga ficaria presa). O throw dá rollback e o INSERT some junto.
                if (cap.getBuyTimeLimit() != null && LocalDateTime.now().isAfter(cap.getBuyTimeLimit()))
                    throw new IllegalArgumentException("Janela de compra encerrada para este evento");
                if (debita && !eventCapRepository.tryReserve(conn, p.eventId())) throw new IllegalArgumentException("Evento lotado");
                conn.commit();
                return new Reservado(resposta, true);
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao reservar hold: " + ex.getMessage(), ex);
        }
    }

    /**
     * Libera o hold, idempotente por {@code holdId}: o upsert de um statement só do {@link HoldRepository#liberar}; se o hold estava
     * {@code held} com débito, devolve a vaga do evento DO HOLD (não do corpo, que só serve à lápide) como último comando.
     */
    public void liberar(UUID holdId, Long eventId, Long userId) {
        if (holdId == null) throw new IllegalArgumentException("holdId é obrigatório");
        if (userId == null || userId <= 0) throw new IllegalArgumentException("userId inválido");
        if (eventId == null || eventId <= 0) throw new IllegalArgumentException("eventId inválido");
        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Optional<HoldRepository.Liberado> liberado = holdRepository.liberar(conn, holdId, eventId, userId);
                if (liberado.isPresent() && liberado.get().decremented()) {
                    eventCapRepository.devolver(conn, liberado.get().eventId(), 1);
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao liberar hold: " + ex.getMessage(), ex);
        }
    }

    private static void validar(HoldRequest p) {
        if (p == null || p.holdId() == null) throw new IllegalArgumentException("holdId é obrigatório");
        if (p.userId() == null || p.userId() <= 0) throw new IllegalArgumentException("userId inválido");
        if (p.eventId() == null || p.eventId() <= 0) throw new IllegalArgumentException("eventId inválido");
        if (!HoldRequest.CAS.equals(p.mode()) && !HoldRequest.NAIVE.equals(p.mode()))
            throw new IllegalArgumentException("mode deve ser cas ou naive");
    }
}
