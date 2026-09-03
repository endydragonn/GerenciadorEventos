package com.gerenciador.eventos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.POJO.Event;
import com.gerenciador.eventos.POJO.User;
import com.gerenciador.eventos.Service.EventService;
import com.gerenciador.eventos.Service.MyWalletService;
import com.gerenciador.eventos.Service.UserService;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.config.DatabaseConnection;

import java.time.LocalDateTime;

/**
 * Trava em teste, contra o Postgres real, os invariantes de constraint do schema criados
 * na implementação EventCap/CAS de 2026-09-01 (issue #7 — verificação):
 *
 * <ul>
 *   <li><b>A)</b> UNIQUE em {@code users(email)}, {@code users(fone)} e {@code event(event_name)}
 *       — conjunto EXATO de colunas restringidas, consultado no catálogo ({@code pg_constraint}),
 *       não subconjunto;</li>
 *   <li><b>B)</b> índice parcial {@code idx_walletevent_pending_expires} — existe <b>e</b> é de
 *       fato parcial ({@code pg_index.indpred IS NOT NULL}) com predicado sobre {@code pending}
 *       e chave {@code expires_at};</li>
 *   <li><b>C)</b> {@code walletevent_status_check} — aceita exatamente
 *       {@code confirmed/pending/expired/cancelled} e rejeita um 5º valor ({@code check_violation}).</li>
 * </ul>
 *
 * <p>Não estende {@code SchemaValidationTest.assertColumns} de propósito: aquele helper faz
 * verificação de subconjunto ({@code containsAll}), que é justamente o defeito apontado pela
 * issue #59. Aqui a asserção é de igualdade de conjunto.</p>
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class SchemaConstraintsTest {

    @Autowired private DatabaseConnection databaseConnection;
    @Autowired private UserService userService;
    @Autowired private EventService eventService;
    @Autowired private MyWalletService myWalletService;

    // ===================== A) constraints UNIQUE =====================

    @Test
    void usersEmailTemConstraintUniquePelaColunaEmail() throws Exception {
        assertUniqueConstraintColumns("Users_email_key", "public.users", "email");
    }

    @Test
    void usersFoneEhUniqueENotNull() throws Exception {
        // fone é OBRIGATÓRIO e único (decisão do usuário em 2026-09-03, issue #7 critério 2):
        // o schema real, o UserService.validateRequired e o frontend já tratam assim.
        assertUniqueConstraintColumns("Users_fone_key", "public.users", "fone");
        assertColunaNotNull("public.users", "fone");
    }

    @Test
    void eventEventNameTemConstraintUniquePelaColunaEventName() throws Exception {
        assertUniqueConstraintColumns("Event_event_name_key", "public.event", "event_name");
    }

    @Test
    void emailDuplicadoViolaConstraintNoBanco() throws Exception {
        // INSERT cru, contornando UserService.validateDuplicates (pré-check em emailExists),
        // para exercitar de fato a constraint Users_email_key no Postgres. As demais colunas
        // UNIQUE (user_name, fone) têm valores distintos, então a única violação possível é o email.
        try (Connection conn = databaseConnection.getConnection()) {
            inserirUsuarioCru(conn, "SC7 dup email 1", "sc7-dup-email@example.com", "sc7-fone-41");
            SQLException erro = assertThrows(SQLException.class, () ->
                    inserirUsuarioCru(conn, "SC7 dup email 2", "sc7-dup-email@example.com", "sc7-fone-42"));
            assertEquals("23505", erro.getSQLState(),
                    "email duplicado deveria violar Users_email_key (unique_violation 23505), veio: "
                            + erro.getSQLState() + " / " + erro.getMessage());
        }
    }

    @Test
    void nomeDeEventoDuplicadoViolaConstraintNoBanco() throws Exception {
        // INSERT cru, contornando EventService.validateDuplicatesOnCreate (pré-check em nameExists),
        // para exercitar de fato a constraint Event_event_name_key no Postgres.
        try (Connection conn = databaseConnection.getConnection()) {
            long criadorId = inserirUsuarioCruRetornandoId(
                    conn, "SC7 ev creator", "sc7-ev-creator@example.com", "sc7-fone-51");
            inserirEventoCru(conn, criadorId, "Evento SC7 Nome Duplicado");
            SQLException erro = assertThrows(SQLException.class, () ->
                    inserirEventoCru(conn, criadorId, "Evento SC7 Nome Duplicado"));
            assertEquals("23505", erro.getSQLState(),
                    "event_name duplicado deveria violar Event_event_name_key (unique_violation 23505), veio: "
                            + erro.getSQLState() + " / " + erro.getMessage());
        }
    }

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
        User criador = criarUsuario("sc7-status-creator@example.com", "sc7-fone-31");
        User inscrito = criarUsuario("sc7-status-user@example.com", "sc7-fone-32");
        Event evento = criarEvento(criador.getId(), "Evento SC7 Status Check");

        try (Connection conn = databaseConnection.getConnection()) {
            // insere UMA linha (PK composta user_id,event_id) e transita entre os status válidos
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO walletevent (user_id, event_id, status) VALUES (?, ?, 'pending')")) {
                ps.setLong(1, inscrito.getId());
                ps.setLong(2, evento.getEvent_id());
                ps.executeUpdate();
            }
            for (String valido : new String[] { "confirmed", "pending", "expired", "cancelled" }) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE walletevent SET status = ? WHERE user_id = ? AND event_id = ?")) {
                    ps.setString(1, valido);
                    ps.setLong(2, inscrito.getId());
                    ps.setLong(3, evento.getEvent_id());
                    assertEquals(1, ps.executeUpdate(),
                            "status válido '" + valido + "' deveria ser aceito");
                }
            }
            // 5º valor => check_violation (SQLState 23514), sem colidir com FK (parents existem)
            SQLException erro = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE walletevent SET status = 'foo' WHERE user_id = ? AND event_id = ?")) {
                    ps.setLong(1, inscrito.getId());
                    ps.setLong(2, evento.getEvent_id());
                    ps.executeUpdate();
                }
            });
            assertEquals("23514", erro.getSQLState(),
                    "status inválido deveria violar walletevent_status_check (23514), veio: "
                            + erro.getSQLState() + " / " + erro.getMessage());
        }
    }

    // ===================== helpers =====================

    /**
     * Afirma que a constraint UNIQUE {@code conname} da relação {@code regclass} restringe
     * EXATAMENTE o conjunto de colunas {@code colunasEsperadas} (igualdade, não subconjunto).
     */
    private void assertUniqueConstraintColumns(String conname, String regclass,
                                               String... colunasEsperadas) throws Exception {
        String sql =
            "SELECT a.attname "
          + "FROM pg_constraint c "
          + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey) "
          + "WHERE c.conname = ? AND c.conrelid = ?::regclass AND c.contype = 'u'";
        Set<String> atual = new LinkedHashSet<>();
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conname);
            ps.setString(2, regclass);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    atual.add(rs.getString(1));
                }
            }
        }
        assertFalse(atual.isEmpty(),
                "constraint UNIQUE '" + conname + "' não encontrada em " + regclass);
        Set<String> esperado = new LinkedHashSet<>();
        for (String col : colunasEsperadas) {
            esperado.add(col);
        }
        assertEquals(esperado, atual,
                "constraint '" + conname + "' restringe colunas " + atual
                        + ", esperado exatamente " + esperado);
    }

    /** Afirma que a coluna {@code coluna} de {@code regclass} é {@code NOT NULL} no schema real. */
    private void assertColunaNotNull(String regclass, String coluna) throws Exception {
        String sql =
            "SELECT a.attnotnull "
          + "FROM pg_attribute a "
          + "WHERE a.attrelid = ?::regclass AND a.attname = ? AND a.attnum > 0 AND NOT a.attisdropped";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, regclass);
            ps.setString(2, coluna);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "coluna " + regclass + "." + coluna + " não encontrada");
                assertTrue(rs.getBoolean("attnotnull"),
                        "coluna " + regclass + "." + coluna + " deveria ser NOT NULL no schema real");
            }
        }
    }

    private User novoUsuarioPojo(String email, String fone) {
        User u = new User();
        u.setName("SC7 " + email);
        u.setEmail(email);
        u.setFone(fone);
        return u;
    }

    private User criarUsuario(String email, String fone) {
        User u = userService.createUser(novoUsuarioPojo(email, fone));
        myWalletService.ensureExists(u.getId());
        return u;
    }

    private Event criarEvento(Long criadorId, String nome) {
        Event e = new Event();
        e.setCreator_id(criadorId);
        e.setEvent_name(nome);
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(10));
        e.setBuy_time_limit(null);
        e.setLot_quantity(5);
        e.setDescription("desc");
        return eventService.createEvent(e);
    }

    /** INSERT cru em public.users (contorna o pré-check de duplicidade do UserService). */
    private void inserirUsuarioCru(Connection conn, String nome, String email, String fone)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (user_name, email, fone, password, birthdate) "
              + "VALUES (?, ?, ?, 'x', DATE '2000-01-01')")) {
            ps.setString(1, nome);
            ps.setString(2, email);
            ps.setString(3, fone);
            ps.executeUpdate();
        }
    }

    private long inserirUsuarioCruRetornandoId(Connection conn, String nome, String email, String fone)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (user_name, email, fone, password, birthdate) "
              + "VALUES (?, ?, ?, 'x', DATE '2000-01-01') RETURNING user_id")) {
            ps.setString(1, nome);
            ps.setString(2, email);
            ps.setString(3, fone);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** INSERT cru em public.event (contorna o pré-check de duplicidade do EventService). */
    private void inserirEventoCru(Connection conn, long criadorId, String nome) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO event (creator_id, event_name, ead, event_date, description) "
              + "VALUES (?, ?, true, CURRENT_TIMESTAMP + interval '10 days', 'desc')")) {
            ps.setLong(1, criadorId);
            ps.setString(2, nome);
            ps.executeUpdate();
        }
    }
}
