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
        // o servidor falso do reservation guarda estado em memória (eventcap por event_id): o TRUNCATE ... RESTART IDENTITY
        // reaproveita os ids de evento, então o estado dele tem de ir junto
        FakeReservationServer fake = FakeReservationServer.get();
        if (fake != null) {
            fake.reset();
        }
        FakeCatalogServer catalog = FakeCatalogServer.get(); // eventos do catalog falso (#35), em memória
        if (catalog != null) {
            catalog.reset();
        }
        FakeIdentityServer identity = FakeIdentityServer.get(); // usuários do identity falso (#36), em memória
        if (identity != null) {
            identity.reset();
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
