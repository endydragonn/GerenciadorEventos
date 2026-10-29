package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import com.gerenciador.eventos.shared.DatabaseConnection;

/** Consultas de teste à saga e ao ledger (os testes do reaper conferem os dois schemas, como o {@link InvariantesDaSaga}). */
final class SagaDb {

    private final DatabaseConnection db;

    SagaDb(DatabaseConnection db) {
        this.db = db;
    }

    String statusDaSaga(String chave, Long userId) throws Exception {
        return (String) escalar("SELECT saga_status FROM reservation.idempotency_key WHERE idempotency_key = ? AND user_id = ?", chave, userId);
    }

    String resolvidaPor(String chave, Long userId) throws Exception {
        return (String) escalar("SELECT resolved_by FROM reservation.idempotency_key WHERE idempotency_key = ? AND user_id = ?", chave, userId);
    }

    UUID holdDaSaga(String chave, Long userId) throws Exception {
        return (UUID) escalar("SELECT hold_id FROM reservation.idempotency_key WHERE idempotency_key = ? AND user_id = ?", chave, userId);
    }

    String statusDoHold(UUID hold) throws Exception {
        return (String) escalar("SELECT status FROM stock.hold WHERE hold_id = ?", hold);
    }

    int contar(String sql, Object... params) throws Exception {
        return ((Number) escalar(sql, params)).intValue();
    }

    /** Espera a saga chegar a {@code status}; devolve os ms gastos ou falha no limite. */
    long esperarStatus(String chave, Long userId, String status, long limiteMs) throws Exception {
        long t0 = System.nanoTime();
        while (!status.equals(statusDaSaga(chave, userId))) {
            if ((System.nanoTime() - t0) / 1_000_000 > limiteMs) {
                throw new AssertionError("saga " + chave + " não chegou a " + status + " em " + limiteMs + " ms (está "
                        + statusDaSaga(chave, userId) + ")");
            }
            Thread.sleep(20);
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** Apaga as sagas não terminais que outras classes deixaram: o reaper (que vê o banco todo) não pode contá-las. */
    void limparSagasEmCurso() throws Exception {
        executar("DELETE FROM reservation.idempotency_key WHERE saga_status IN ('reserving', 'compensating')");
    }

    void executar(String sql, Object... params) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    Object escalar(String sql, Object... params) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }
}
