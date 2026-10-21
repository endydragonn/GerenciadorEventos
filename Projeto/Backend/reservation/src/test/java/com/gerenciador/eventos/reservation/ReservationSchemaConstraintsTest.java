package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Invariantes de constraint de {@code reservation.walletevent} (issue #7, movidos do monólito na #33 junto com a tabela):
 * <b>B)</b> índice parcial {@code idx_walletevent_pending_expires} — existe <b>e</b> é de fato parcial
 * ({@code pg_index.indpred IS NOT NULL}) com predicado sobre {@code pending} e chave {@code expires_at};
 * <b>C)</b> {@code walletevent_status_check} — aceita exatamente {@code confirmed/pending/expired/cancelled} e rejeita um 5º
 * valor ({@code check_violation}). Asserções de lista/igualdade, não de substring/subconjunto (o defeito apontado na #59).
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class ReservationSchemaConstraintsTest {

    @Autowired private DatabaseConnection databaseConnection;

    // ===================== B) índice parcial =====================

    @Test
    void indiceWalleteventPendingExpiresExisteEhParcialEChaveiaExpiresAt() throws Exception {
        // colunas-chave EXATAS: unnest de i.indkey (ordinal) -> pg_attribute; asserção de lista,
        // não de substring em indexdef (que passaria com expires_at só no predicado — o defeito #59).
        String sql =
            "SELECT i.indpred IS NOT NULL AS parcial, "
          + "       pg_get_expr(i.indpred, i.indrelid) AS predicado, "
          + "       (SELECT array_agg(a.attname ORDER BY k.ord) "
          + "          FROM unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord) "
          + "          JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum "
          + "       ) AS colunas_chave "
          + "FROM pg_index i "
          + "JOIN pg_class c ON c.oid = i.indexrelid "
          + "WHERE c.relname = 'idx_walletevent_pending_expires'";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "índice idx_walletevent_pending_expires não existe");
            assertTrue(rs.getBoolean("parcial"),
                    "índice existe mas NÃO é parcial (indpred IS NULL)");
            String predicado = rs.getString("predicado");
            assertNotNull(predicado, "predicado do índice parcial ausente");
            assertTrue(predicado.contains("pending"),
                    "predicado do índice parcial não filtra status = 'pending': " + predicado);
            java.sql.Array arr = rs.getArray("colunas_chave");
            assertNotNull(arr, "índice não tem colunas-chave");
            java.util.List<String> colunasChave = java.util.Arrays.asList((String[]) arr.getArray());
            assertEquals(java.util.List.of("expires_at"), colunasChave,
                    "chave do índice deveria ser exatamente [expires_at], veio: " + colunasChave);
        }
    }

    // ===================== C) walletevent_status_check =====================

    @Test
    void statusCheckAceitaOsQuatroValoresERejeitaUmQuinto() throws Exception {
        // sem FK para users/event (outro serviço): a linha é só dois ids
        long usuario = 7_001L;
        long evento = 7_002L;

        try (Connection conn = databaseConnection.getConnection()) {
            // insere UMA linha (PK composta user_id,event_id) e transita entre os status válidos
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO reservation.walletevent (user_id, event_id, status) VALUES (?, ?, 'pending')")) {
                ps.setLong(1, usuario);
                ps.setLong(2, evento);
                ps.executeUpdate();
            }
            for (String valido : new String[] { "confirmed", "pending", "expired", "cancelled" }) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE reservation.walletevent SET status = ? WHERE user_id = ? AND event_id = ?")) {
                    ps.setString(1, valido);
                    ps.setLong(2, usuario);
                    ps.setLong(3, evento);
                    assertEquals(1, ps.executeUpdate(),
                            "status válido '" + valido + "' deveria ser aceito");
                }
            }
            // 5º valor => check_violation (SQLState 23514)
            SQLException erro = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE reservation.walletevent SET status = 'foo' WHERE user_id = ? AND event_id = ?")) {
                    ps.setLong(1, usuario);
                    ps.setLong(2, evento);
                    ps.executeUpdate();
                }
            });
            assertEquals("23514", erro.getSQLState(),
                    "status inválido deveria violar walletevent_status_check (23514), veio: "
                            + erro.getSQLState() + " / " + erro.getMessage());
        }
    }
}
