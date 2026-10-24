package com.gerenciador.eventos.TestSupport;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;

import com.gerenciador.eventos.shared.DatabaseConnection;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/** TRUNCATE (início/fim da suíte) das tabelas do schema {@code catalog} (cópia enxuta do TestSupport do monólito). */
@TestConfiguration
public class GlobalDbTruncator {

    private static final Logger log = LoggerFactory.getLogger(GlobalDbTruncator.class);
    private final DatabaseConnection databaseConnection;
    private final List<String> schemas;

    public GlobalDbTruncator(DatabaseConnection databaseConnection,
                             @Value(TestSchemas.PROPERTY) String schemas) {
        this.databaseConnection = databaseConnection;
        this.schemas = TestSchemas.parse(schemas);
    }

    private void truncateAll() {
        // o reservation falso guarda eventcap por event_id em memória: o RESTART IDENTITY reaproveita os ids, o estado vai junto
        FakeEventCapServer fake = FakeEventCapServer.get();
        if (fake != null) {
            fake.reset();
        }
        String truncateSQL = null;
        try (Connection conn = databaseConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            truncateSQL = TestSchemas.truncateSql(conn, schemas);
            if (truncateSQL != null) {
                stmt.execute(truncateSQL);
            }
        } catch (Exception e) {
            log.warn("Falha ao truncar tabelas de teste (schemas={}, sql={}): {}", schemas, truncateSQL, e.getMessage());
        }
    }

    @PostConstruct
    public void onStart() {
        log.info("[Test] Truncating database at test suite start...");
        truncateAll();
    }

    @PreDestroy
    public void onEnd() {
        log.info("[Test] Truncating database at test suite end...");
        truncateAll();
    }
}
