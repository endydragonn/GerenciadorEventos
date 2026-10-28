package com.gerenciador.eventos.identity;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Guarda de boot (issue #36, spec "Guarda de boot", caso 13): com {@code public.users} presente o serviço NÃO sobe, com a mensagem
 * que aponta a migração {@code 20261028}. O contexto deste teste só sobe porque o banco já está migrado (a própria guarda roda no
 * boot); o caso "presente" cria {@code public.users} DENTRO de uma transação e a desfaz, sem tocar no schema de ninguém.
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class PublicSchemaGuardTest {

    @Autowired private DatabaseConnection databaseConnection;

    @Test
    void bancoMigrado_aGuardaPassa() throws Exception {
        try (Connection conn = databaseConnection.getConnection()) {
            assertDoesNotThrow(() -> PublicSchemaGuard.verificar(conn));
        }
    }

    @Test
    void publicUsersPresente_naoSobe_comMensagemQueApontaAMigracao() throws Exception {
        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE public.users (user_id integer)");
                IllegalStateException ex = assertThrows(IllegalStateException.class, () -> PublicSchemaGuard.verificar(conn));
                assertTrue(ex.getMessage().contains("20261028-identity-schema.sql"), ex.getMessage());
                assertTrue(ex.getMessage().contains("pg_dump"), "a mensagem traz o backup: " + ex.getMessage());
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
            }
            assertDoesNotThrow(() -> PublicSchemaGuard.verificar(conn)); // o rollback não deixou a tabela
        }
    }
}
