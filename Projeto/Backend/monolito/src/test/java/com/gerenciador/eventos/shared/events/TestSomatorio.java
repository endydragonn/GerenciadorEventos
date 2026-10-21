package com.gerenciador.eventos.shared.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Projeção de TESTE (a #27 não tem projeção de domínio): por stream, conta os eventos e soma o campo
 * {@code valor} do payload. Tabela {@code public.test_somatorio}, criada pelos testes (não é schema do app).
 */
public class TestSomatorio implements Projection {

    public static final String NOME = "test_somatorio";

    public static void criarTabela(DatabaseConnection db) throws SQLException {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS public.test_somatorio "
                    + "(stream_id text PRIMARY KEY, eventos integer NOT NULL, soma bigint NOT NULL)");
        }
    }

    /** Remove a tabela de teste: ela não faz parte do schema do app e não pode sobrar no banco compartilhado. */
    public static void derrubarTabela(DatabaseConnection db) throws SQLException {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS public.test_somatorio");
        }
    }

    /** {@code [eventos, soma]} da linha do stream, ou {@code null} se não existe. */
    public static long[] ler(DatabaseConnection db, String schema, String streamId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT eventos, soma FROM " + schema + ".test_somatorio WHERE stream_id = ?")) {
            ps.setString(1, streamId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new long[] {rs.getLong(1), rs.getLong(2)} : null;
            }
        }
    }

    @Override
    public String name() {
        return NOME;
    }

    @Override
    public List<String> tables() {
        return List.of("test_somatorio");
    }

    @Override
    public void apply(Connection conn, EventEnvelope event, String schema) throws SQLException {
        String sql = "INSERT INTO " + schema + ".test_somatorio (stream_id, eventos, soma) VALUES (?, 1, ?) "
                + "ON CONFLICT (stream_id) DO UPDATE SET eventos = test_somatorio.eventos + 1, "
                + "soma = test_somatorio.soma + EXCLUDED.soma";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, event.streamId());
            ps.setLong(2, event.payload().get("valor").asLong());
            ps.executeUpdate();
        }
    }
}
