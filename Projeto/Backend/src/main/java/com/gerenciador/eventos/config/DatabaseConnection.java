package com.gerenciador.eventos.config;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

/**
 * Entrega conexões do {@link DataSource} do Spring (pool HikariCP, configurado por
 * {@code spring.datasource.*}). O chamador continua fechando com try-with-resources: o
 * {@code close()} devolve a conexão ao pool em vez de encerrá-la.
 */
@Component
public class DatabaseConnection {

    private final DataSource dataSource;

    public DatabaseConnection(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
}
