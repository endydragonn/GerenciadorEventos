package com.gerenciador.eventos.reservation;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Invariantes da saga stock + wallet (issue #47), checadas em todo teste de compensação, SÓ para os eventos que o teste criou (os testes
 * antigos mexem em {@code quant} na mão e deixariam a checagem global dependente da ordem das classes). É o ÚNICO código de teste que
 * lê os dois schemas ({@code reservation} e {@code stock}), de propósito: nenhum serviço lê tabela de outro.
 * <ol>
 *   <li>{@code quant = capacity - count(hold held AND decremented)} em todo evento limitado (nada de vaga a mais nem a menos);</li>
 *   <li>nenhum {@code walletevent} ativo sem hold {@code held} com o mesmo {@code hold_id} (não existe "wallet sem stock");</li>
 *   <li>o sentido INVERSO: todo hold {@code held} tem um {@code walletevent} ativo com o mesmo {@code hold_id}, ou uma saga
 *       {@code reserving}/{@code compensating} com ele, ou um {@code ReservationCancelled}/{@code ReservationExpired} com o {@code holdId}
 *       ainda não consumido. Sem esta checagem uma vaga presa para sempre passaria com tudo verde;</li>
 *   <li>ao fim, nenhuma saga {@code reserving} ou {@code compensating} (salvo {@code permitirSagaPresa}, para o teste que a deixa de
 *       propósito).</li>
 * </ol>
 */
final class InvariantesDaSaga {

    private InvariantesDaSaga() {
    }

    static void verificar(DatabaseConnection db, Long... eventIds) throws SQLException {
        verificar(db, false, eventIds);
    }

    static void verificar(DatabaseConnection db, boolean permitirSagaPresa, Long... eventIds) throws SQLException {
        try (Connection c = db.getConnection()) {
            Array ids = c.createArrayOf("bigint", eventIds);
            assertEquals(List.of(), consultar(c, ids, "SELECT ec.event_id || ': quant=' || ec.quant || ' capacity=' || ec.capacity || ' debitados=' || "
                    + "(SELECT count(*) FROM stock.hold h WHERE h.event_id = ec.event_id AND h.status = 'held' AND h.decremented) "
                    + "FROM stock.eventcap ec WHERE ec.event_id = ANY(?) AND ec.capacity <> -1 AND ec.quant <> ec.capacity - "
                    + "(SELECT count(*) FROM stock.hold h WHERE h.event_id = ec.event_id AND h.status = 'held' AND h.decremented)"),
                    "quant = capacity - holds debitados");
            assertEquals(List.of(), consultar(c, ids, "SELECT w.user_id || ':' || w.event_id || ' hold ' || coalesce(w.hold_id::text, 'null') "
                    + "FROM reservation.walletevent w WHERE w.event_id = ANY(?) AND w.status IN ('pending', 'confirmed') AND NOT EXISTS "
                    + "(SELECT 1 FROM stock.hold h WHERE h.hold_id = w.hold_id AND h.status = 'held')"),
                    "todo walletevent ativo tem hold held com o mesmo hold_id");
            assertEquals(List.of(), consultar(c, ids, "SELECT h.hold_id::text FROM stock.hold h WHERE h.event_id = ANY(?) AND h.status = 'held' "
                    + "AND NOT EXISTS (SELECT 1 FROM reservation.walletevent w WHERE w.hold_id = h.hold_id AND w.status IN ('pending', 'confirmed')) "
                    + "AND NOT EXISTS (SELECT 1 FROM reservation.idempotency_key k WHERE k.hold_id = h.hold_id "
                    + "AND k.saga_status IN ('reserving', 'compensating')) "
                    + "AND NOT EXISTS (SELECT 1 FROM reservation.event_store e WHERE e.stream_type = 'reservation.wallet' "
                    + "AND e.event_type IN ('ReservationCancelled', 'ReservationExpired') AND e.payload ->> 'holdId' = h.hold_id::text)"),
                    "todo hold held tem walletevent ativo, saga em curso ou devolução pendente (vaga presa para sempre)");
            if (!permitirSagaPresa) {
                assertEquals(List.of(), consultar(c, ids, "SELECT idempotency_key || ' ' || saga_status FROM reservation.idempotency_key "
                        + "WHERE event_id = ANY(?) AND saga_status IN ('reserving', 'compensating')"),
                        "nenhuma saga reserving ou compensating sobrando");
            }
        }
    }

    private static List<String> consultar(Connection c, Array ids, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, ids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }
}
