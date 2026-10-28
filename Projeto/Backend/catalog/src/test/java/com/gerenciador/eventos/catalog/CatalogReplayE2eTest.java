package com.gerenciador.eventos.catalog;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventReplay;

/**
 * Replay do stream {@code catalog.event} sobre o banco da stack e2e (issue #37, {@code Projeto/e2e/run-e2e.sh}): depois do
 * fluxo pelo gateway, reconstrói na sombra, só do {@code catalog.event_store}, {@code event}/{@code eventpresenter} e exige
 * que não divirjam das vivas (mesmas colunas comparáveis do {@code CatalogEventSourcingTest}). NÃO apaga nada: lê o log e
 * cria/derruba só o schema-sombra (por isso não importa o {@code GlobalDbTruncator}). Fica PULADO no {@code run-tests.sh};
 * liga com {@code E2E_REPLAY=1}.
 */
@EnabledIfEnvironmentVariable(named = "E2E_REPLAY", matches = "1")
@SpringBootTest
public class CatalogReplayE2eTest {

    private static final String SOMBRA = "replay_e2e_catalog";
    private static final Map<String, List<String>> COMPARAVEIS = Map.of("event", List.of(
            "event_id", "creator_id", "event_name", "ead", "address", "event_date", "description", "version"));

    @Autowired private EventReplay replay;
    @Autowired private CatalogEventProjection projection;
    @Autowired private DatabaseConnection db;

    @AfterEach
    void derrubarSombra() throws Exception {
        replay.drop(SOMBRA);
    }

    @Test
    void eventEPresentersReconstruidosDoLogSaoOsVivos() throws Exception {
        replay.rebuild(projection, CatalogEventProjection.STREAM_TYPE, SOMBRA);

        assertEquals(List.of(), replay.divergentTables(projection, SOMBRA, COMPARAVEIS));
        // não vale replay de log vazio: o fluxo e2e criou ao menos um evento
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + SOMBRA + ".event")) {
            rs.next();
            assertTrue(rs.getLong(1) >= 1);
        }
    }
}
