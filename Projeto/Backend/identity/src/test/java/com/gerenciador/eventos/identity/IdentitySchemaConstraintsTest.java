package com.gerenciador.eventos.identity;

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
 * na implementação EventCap/CAS de 2026-09-01 (issue #7 — verificação); desde o cutover do identity (#36) mora no módulo identity,
 * contra o schema {@code identity}:
 *
 * <ul>
 *   <li><b>A)</b> UNIQUE em {@code users(email)} e {@code users(fone)}
 *       — conjunto EXATO de colunas restringidas, consultado no catálogo ({@code pg_constraint}),
 *       não subconjunto;</li>
 * </ul>
 *
 * <p>O UNIQUE de {@code event(event_name)} saiu com a tabela para o schema {@code catalog} na #35: é do
 * {@code CatalogSchemaConstraintsTest}, no módulo {@code catalog}.</p>
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
public class IdentitySchemaConstraintsTest {

    @Autowired private DatabaseConnection databaseConnection;

    // ===================== A) constraints UNIQUE =====================

    @Test
    void usersEmailTemConstraintUniquePelaColunaEmail() throws Exception {
        assertUniqueConstraintColumns("Users_email_key", "identity.users", "email");
    }

    @Test
    void usersFoneEhUniqueENotNull() throws Exception {
        // fone é OBRIGATÓRIO e único (decisão do usuário em 2026-09-03, issue #7 critério 2):
        // o schema real, o UserService.validateRequired e o frontend já tratam assim.
        assertUniqueConstraintColumns("Users_fone_key", "identity.users", "fone");
        assertColunaNotNull("identity.users", "fone");
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

    /** INSERT cru em identity.users (contorna o pré-check de duplicidade do UserService). */
    private void inserirUsuarioCru(Connection conn, String nome, String email, String fone)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO identity.users (user_name, email, fone, password, birthdate) "
              + "VALUES (?, ?, ?, 'x', DATE '2000-01-01')")) {
            ps.setString(1, nome);
            ps.setString(2, email);
            ps.setString(3, fone);
            ps.executeUpdate();
        }
    }
}
