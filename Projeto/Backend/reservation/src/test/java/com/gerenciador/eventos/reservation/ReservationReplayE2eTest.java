package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventReplay;
import com.gerenciador.eventos.shared.events.Projection;

/**
 * Replay do stream {@code reservation.wallet} sobre o banco da stack e2e (issue #37, {@code Projeto/e2e/run-e2e.sh}): depois
 * do fluxo pelo gateway, reconstrói na sombra, só do {@code reservation.event_store}, o {@code walletevent} e a projeção de
 * {@code ativos} (a mesma do {@code ReservationReplayTest}) e exige que não divirjam das vivas. NÃO apaga nada: lê o log e
 * cria/derruba só o schema-sombra. Fica PULADO no {@code run-tests.sh}; liga com {@code E2E_REPLAY=1}.
 */
@EnabledIfEnvironmentVariable(named = "E2E_REPLAY", matches = "1")
@SpringBootTest
public class ReservationReplayE2eTest {

    private static final String SOMBRA = "replay_e2e_wallet";

    @Autowired private EventReplay replay;
    @Autowired private DatabaseConnection db;

    private final Projection projecao = new ReservationReplayTest.WalletEAtivos();

    @AfterEach
    void derrubarSombra() throws Exception {
        replay.drop(SOMBRA);
    }

    @Test
    void walletEventEAtivosReconstruidosDoLogSaoOsVivos() throws Exception {
        replay.rebuild(projecao, ReservationEvents.STREAM_TYPE, SOMBRA);

        assertEquals(List.of(), replay.divergentTables(projecao, SOMBRA, ReservationReplayTest.COMPARAVEIS));
        // não vale replay de log vazio: o fluxo e2e deixou ao menos a reserva confirmada
        assertTrue(contar("SELECT count(*) FROM " + SOMBRA + ".walletevent WHERE status = 'confirmed'") >= 1);
        assertTrue(contar("SELECT count(*) FROM " + SOMBRA + ".event_availability") >= 1);
    }

    private long contar(String sql) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
