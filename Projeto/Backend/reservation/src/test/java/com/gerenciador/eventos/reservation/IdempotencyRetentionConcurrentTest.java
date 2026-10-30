package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Retenção da {@code idempotency_key} com 2+ réplicas (issue #111): N execuções de {@code purgeExpired()} AO MESMO TEMPO
 * sobre mais de 2 lotes de chaves vencidas. Sem erro (nenhuma tarefa falha), cada chave vencida apagada uma única vez (a
 * soma do que cada execução relata = o total de vencidas) e as chaves dentro do prazo continuam lá.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
@TestPropertySource(properties = "idempotency.retention.scan.ms=3600000")
public class IdempotencyRetentionConcurrentTest {
    private static final int REPLICAS = 4;
    private static final int VENCIDAS = 2500; // > 2 lotes de 1000
    private static final int RECENTES = 50;

    @Autowired private IdempotencyKeyRetentionService retentionService;
    @Autowired private DatabaseConnection databaseConnection;

    @BeforeEach
    public void limpar() {
        retentionService.purgeExpired(); // vencidas que outra classe tenha deixado: não podem entrar na soma
    }

    private void exec(String sql, String prefixo) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, prefixo);
            ps.executeUpdate();
        }
    }

    private long contar(String prefixo) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM reservation.idempotency_key WHERE idempotency_key LIKE ?")) {
            ps.setString(1, prefixo + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @RepeatedTest(3)
    public void execucoesConcorrentesApagamSoAsVencidasCadaUmaUmaVez() throws Exception {
        String prefixo = "conc-" + UUID.randomUUID() + "-";
        exec("INSERT INTO reservation.idempotency_key (idempotency_key, user_id, event_id, expires_at, created_at, saga_status) "
                + "SELECT ? || g, g, 1, now(), now() - interval '48 hours', 'reserved' FROM generate_series(1, " + VENCIDAS + ") g",
                prefixo + "v-");
        exec("INSERT INTO reservation.idempotency_key (idempotency_key, user_id, event_id, expires_at, saga_status) "
                + "SELECT ? || g, g, 1, now(), 'reserved' FROM generate_series(1, " + RECENTES + ") g", prefixo + "r-");

        List<Integer> apagadas = Concorrencia.emParalelo(REPLICAS, retentionService::purgeExpired);

        assertEquals(VENCIDAS, apagadas.stream().mapToInt(Integer::intValue).sum(),
                "cada vencida é apagada (e relatada) por uma única execução: " + apagadas);
        assertEquals(0, contar(prefixo + "v-"), "nenhuma vencida sobrou");
        assertEquals(RECENTES, contar(prefixo + "r-"), "as dentro do prazo continuam");
    }
}
