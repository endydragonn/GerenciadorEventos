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

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;


/**
 * Trava em teste, contra o Postgres real, os invariantes de constraint do schema criados
 * na implementação EventCap/CAS de 2026-09-01 (issue #7 — verificação):
 *
 * <ul>
 *   <li><b>A)</b> UNIQUE em {@code users(email)}, {@code users(fone)} e {@code event(event_name)}
 *       — conjunto EXATO de colunas restringidas, consultado no catálogo ({@code pg_constraint}),
 *       não subconjunto;</li>
 * </ul>
 *
 * <p>Os itens B (índice parcial {@code idx_walletevent_pending_expires}) e C ({@code walletevent_status_check}) eram sobre
 * {@code walletevent}, que saiu para o schema {@code reservation} na #33: agora são do
 * {@code ReservationSchemaConstraintsTest}, no módulo {@code reservation}.</p>
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
