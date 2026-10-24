package com.gerenciador.eventos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.identity.api.User;
import com.gerenciador.eventos.identity.UserService;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Exercita a migração a quente {@code db/migrations/20260901-eventcap-isolation.sql} contra
 * um Postgres real.
 *
 * <p>Reconstrói o estado do schema ANTIGO (colunas {@code capacity} / {@code quant} /
 * {@code buy_time_limit} em {@code event}, onde {@code quant} = ingressos <b>vendidos</b>) e
 * confere que a etapa 2 da migração converte para o modelo NOVO
 * ({@code eventcap.quant} = vagas <b>disponíveis</b> = {@code capacity - vendidos}).</p>
 *
 * <p><b>Cobre:</b> a expressão de conversão da etapa 2 ({@code capacity - quant}, com piso 0);
 * o caso {@code capacity IS NULL} (regra antiga de evento ilimitado) virando {@code -1 / -1};
 * e a idempotência do {@code ON CONFLICT (event_id) DO NOTHING} (uma linha já migrada não é
 * sobrescrita numa re-execução). <b>Não cobre:</b> as etapas de DDL puro (criar tabelas,
 * dropar colunas, constraint de {@code status}), que num banco novo já são no-op e são
 * cobertas pelo {@code SchemaValidationTest}.</p>
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class EventCapMigrationTest {

    @Autowired private UserService userService;
    @Autowired private DatabaseConnection databaseConnection;

    @Test
    public void migracaoConverteIngressosVendidosEmVagasDisponiveis() throws Exception {
        Path migracao = localizarMigracao();

        User creator = new User();
        creator.setName("Mig Creator");
        creator.setEmail("mig-creator@example.com");
        creator.setFone("11900000001");
        creator = userService.createUser(creator);
        long creatorId = creator.getId();

        try (Connection conn = databaseConnection.getConnection()) {
            // 1) reconstrói o schema ANTIGO: colunas em event, sem eventcap correspondente
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE public.event "
                        + "ADD COLUMN IF NOT EXISTS capacity integer, "
                        + "ADD COLUMN IF NOT EXISTS quant integer, "
                        + "ADD COLUMN IF NOT EXISTS buy_time_limit timestamp without time zone");
            }

            // evento LIMITADO no modelo antigo: 100 lugares, 2 já vendidos
            long idLimitado = inserirEventoAntigo(conn, creatorId, "Migracao Evento Limitado", 100, 2);
            // evento ILIMITADO no modelo antigo: capacity NULL
            long idIlimitado = inserirEventoAntigo(conn, creatorId, "Migracao Evento Ilimitado", null, 0);

            // Desde a #33 walletevent/eventcap moram no schema reservation; a migração de 2026-09-01 só conhece o estado
            // LEGADO em public (ela faz ALTER TABLE public.walletevent). O teste monta a walletevent legada e a derruba no fim.
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS public.walletevent (user_id integer NOT NULL, "
                        + "event_id integer NOT NULL, created_at timestamp DEFAULT CURRENT_TIMESTAMP, "
                        + "updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (user_id, event_id))");
            }

            // 2) roda a migração de verdade
            try (Statement st = conn.createStatement()) {
                st.execute(Files.readString(migracao));
            }

            // 3) limitado: capacity preservada; quant = 100 - 2 vendidos = 98 disponíveis
            int[] limitado = lerEventcap(conn, idLimitado);
            assertEquals(100, limitado[0], "capacity deve ser preservada");
            assertEquals(98, limitado[1],
                    "quant deve ser capacity - vendidos (vagas DISPONIVEIS), nao os vendidos");

            // 4) sem capacidade (regra antiga) => evento ilimitado -1 / -1
            int[] ilimitado = lerEventcap(conn, idIlimitado);
            assertEquals(-1, ilimitado[0], "capacity NULL vira -1 (ilimitado)");
            assertEquals(-1, ilimitado[1], "quant de evento ilimitado e -1");

            // 5) idempotência do ON CONFLICT DO NOTHING: readiciona as colunas com valores
            //    diferentes e re-roda — a linha já convertida NAO pode ser sobrescrita
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE public.event "
                        + "ADD COLUMN IF NOT EXISTS capacity integer, "
                        + "ADD COLUMN IF NOT EXISTS quant integer, "
                        + "ADD COLUMN IF NOT EXISTS buy_time_limit timestamp without time zone");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE public.event SET capacity = 7, quant = 7 WHERE event_id = ?")) {
                ps.setLong(1, idLimitado);
                ps.executeUpdate();
            }
            try (Statement st = conn.createStatement()) {
                st.execute(Files.readString(migracao));
            }
            int[] limitadoDeNovo = lerEventcap(conn, idLimitado);
            assertEquals(98, limitadoDeNovo[1],
                    "re-execucao nao pode sobrescrever uma linha ja migrada (ON CONFLICT DO NOTHING)");
        } finally {
            limpar(creatorId);
        }
    }

    private long inserirEventoAntigo(Connection conn, long creatorId, String nome,
                                     Integer capacity, Integer vendidos) throws Exception {
        String sql = "INSERT INTO public.event "
                + "(creator_id, event_name, ead, event_date, description, capacity, quant) "
                + "VALUES (?, ?, true, CURRENT_TIMESTAMP + interval '30 days', 'desc', ?, ?) "
                + "RETURNING event_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, creatorId);
            ps.setString(2, nome);
            if (capacity != null) ps.setInt(3, capacity); else ps.setNull(3, java.sql.Types.INTEGER);
            ps.setInt(4, vendidos);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** @return {@code [capacity, quant]} de eventcap para o evento. */
    private int[] lerEventcap(Connection conn, long eventId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT capacity, quant FROM public.eventcap WHERE event_id = ?")) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "eventcap nao foi criado para event_id " + eventId);
                return new int[] { rs.getInt(1), rs.getInt(2) };
            }
        }
    }

    /** Remove os dados órfãos deste teste e restaura o schema novo (sem as colunas antigas). */
    private void limpar(long creatorId) {
        try (Connection conn = databaseConnection.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM public.event WHERE creator_id = ?")) {
                ps.setLong(1, creatorId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM public.users WHERE user_id = ?")) {
                ps.setLong(1, creatorId);
                ps.executeUpdate();
            }
            try (Statement st = conn.createStatement()) {
                // a migração recriou as tabelas legadas em public: não existem no schema atual (estão em reservation)
                st.execute("DROP TABLE IF EXISTS public.eventcap, public.walletevent CASCADE");
                st.execute("ALTER TABLE public.event "
                        + "DROP COLUMN IF EXISTS capacity, "
                        + "DROP COLUMN IF EXISTS quant, "
                        + "DROP COLUMN IF EXISTS buy_time_limit");
            }
        } catch (Exception ex) {
            throw new RuntimeException("Falha ao limpar dados do EventCapMigrationTest: " + ex.getMessage(), ex);
        }
    }

    private static Path localizarMigracao() {
        Path[] candidatos = {
            Path.of("../db/migrations/20260901-eventcap-isolation.sql"),
            Path.of("db/migrations/20260901-eventcap-isolation.sql"),
            Path.of("Projeto/Backend/db/migrations/20260901-eventcap-isolation.sql"),
        };
        for (Path p : candidatos) {
            if (Files.exists(p)) return p;
        }
        throw new IllegalStateException(
                "Arquivo da migração não encontrado; cwd=" + Path.of("").toAbsolutePath());
    }
}
