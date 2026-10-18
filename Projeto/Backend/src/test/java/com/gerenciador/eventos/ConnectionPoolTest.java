package com.gerenciador.eventos;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.gerenciador.eventos.shared.DatabaseConnection;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * Issue #96: {@code DatabaseConnection} deve entregar conexões de um pool HikariCP, não abrir
 * uma conexão TCP nova por chamada ({@code DriverManager}), o que esgotava as portas efêmeras
 * sob carga.
 */
@SpringBootTest(properties = {
        "readmodel.availability.refresh.ms=3600000", "reservation.expiry.scan.ms=3600000"})
class ConnectionPoolTest {

    @Autowired
    private DatabaseConnection databaseConnection;

    @Autowired
    private DataSource dataSource;

    @Test
    void getConnection_vemDoPoolHikari() throws Exception {
        HikariDataSource hikari = assertInstanceOf(HikariDataSource.class, dataSource);

        try (Connection conn = databaseConnection.getConnection()) {
            assertTrue(conn.getClass().getName().startsWith("com.zaxxer.hikari"),
                    "conexão deveria ser o proxy do Hikari, era " + conn.getClass().getName());
            assertTrue(conn.isValid(2));
        }
        assertTrue(hikari.getHikariPoolMXBean().getTotalConnections() >= 1);
    }

    @Test
    void conexaoFechadaVoltaAoPool() throws Exception {
        HikariDataSource hikari = assertInstanceOf(HikariDataSource.class, dataSource);
        for (int i = 0; i < 20; i++) {
            try (Connection c = databaseConnection.getConnection()) {
                assertTrue(c.isValid(2));
                assertEquals(1, hikari.getHikariPoolMXBean().getActiveConnections());
            }
        }
        // close() devolve ao pool em vez de encerrar: nada fica emprestado
        assertEquals(0, hikari.getHikariPoolMXBean().getActiveConnections());
    }
}
