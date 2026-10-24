package com.gerenciador.eventos.catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Constraints do schema {@code catalog} exercitadas com INSERT cru (contornando a pré-checagem do {@code EventService}), como o
 * {@code SchemaConstraintsTest} do monólito fazia para {@code public.event} (issue #35).
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class CatalogSchemaConstraintsTest {

    @Autowired
    private DatabaseConnection databaseConnection;

    @Test
    void nomeDeEventoDuplicadoViolaConstraintNoBanco() throws Exception {
        String nome = "Evento SC Nome Duplicado " + System.nanoTime();
        try (Connection conn = databaseConnection.getConnection()) {
            inserirEventoCru(conn, nome);
            SQLException erro = assertThrows(SQLException.class, () -> inserirEventoCru(conn, nome));
            assertEquals("23505", erro.getSQLState(), "event_name duplicado viola Event_event_name_key: " + erro.getMessage());
        }
    }

    /** {@code creator_id} sem FK: um id que não existe em {@code users} é aceito (quem confere o criador é o gateway). */
    @Test
    void creatorIdInexistenteEmUsersEhAceito() throws Exception {
        try (Connection conn = databaseConnection.getConnection()) {
            long id = inserirEventoCru(conn, "Evento SC criador solto " + System.nanoTime(), 2_000_000_000L);
            assertEquals(1, contar(conn, "SELECT count(*) FROM catalog.event WHERE event_id = " + id));
        }
    }

    /** Apagar o evento apaga os apresentadores ({@code ON DELETE CASCADE}). */
    @Test
    void apagarEventoApagaApresentadoresEmCascata() throws Exception {
        try (Connection conn = databaseConnection.getConnection(); Statement st = conn.createStatement()) {
            long id = inserirEventoCru(conn, "Evento SC cascata " + System.nanoTime());
            st.execute("INSERT INTO catalog.eventpresenter (event_id, presenter_name) VALUES (" + id + ", 'Ana'), (" + id + ", 'Bia')");
            st.execute("DELETE FROM catalog.event WHERE event_id = " + id);
            assertEquals(0, contar(conn, "SELECT count(*) FROM catalog.eventpresenter WHERE event_id = " + id));
        }
    }

    private long inserirEventoCru(Connection conn, String nome) throws SQLException {
        return inserirEventoCru(conn, nome, 1L);
    }

    private long inserirEventoCru(Connection conn, String nome, long criador) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO catalog.event (creator_id, event_name, ead, event_date, description) "
              + "VALUES (?, ?, true, CURRENT_TIMESTAMP + interval '10 days', 'desc') RETURNING event_id")) {
            ps.setLong(1, criador);
            ps.setString(2, nome);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long contar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
