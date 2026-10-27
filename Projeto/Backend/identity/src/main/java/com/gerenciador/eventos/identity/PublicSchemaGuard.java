package com.gerenciador.eventos.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.stereotype.Component;

import com.gerenciador.eventos.shared.DatabaseConnection;

import jakarta.annotation.PostConstruct;

/**
 * Guarda de boot (issue #36, spec "Guarda de boot"): o serviço identity NÃO sobe se o banco ainda tiver {@code public.users}, isto é, se
 * a migração {@code db/migrations/20261028-identity-schema.sql} (que move {@code users}/{@code mywallet}/{@code pending_verifications}
 * e o stream {@code identity.notification} para o schema {@code identity}) ainda não foi aplicada. Sem ela o serviço rodaria sobre
 * tabelas NOVAS e VAZIAS do schema {@code identity} (criadas pelo {@code 15-identity.sql}), e o 1º cadastro herdaria o {@code user_id} 1,
 * que no {@code public.users} já é de outra pessoa (eventos e reservas apontam para esse id). Falhar alto é o que impede isso.
 */
@Component
public class PublicSchemaGuard {

    static final String MENSAGEM = "O banco ainda tem public.users: a migração db/migrations/20261028-identity-schema.sql não foi "
            + "aplicada, e o serviço identity não sobe sobre tabelas vazias do schema identity (o 1º cadastro herdaria um user_id que "
            + "já pertence a outro usuário). Pare o backend e o identity, faça o backup (pg_dump -h localhost -U admin --clean "
            + "--if-exists -n public meu_banco) e aplique a migração (roteiro e rollback no cabeçalho do arquivo).";

    private final DatabaseConnection db;

    public PublicSchemaGuard(DatabaseConnection db) {
        this.db = db;
    }

    @PostConstruct
    void verificarNoBoot() throws SQLException {
        try (Connection conn = db.getConnection()) {
            verificar(conn);
        }
    }

    /** Lança {@link IllegalStateException} com a instrução da migração se {@code public.users} existir. */
    static void verificar(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT to_regclass('public.users') IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            if (rs.getBoolean(1)) {
                throw new IllegalStateException(MENSAGEM);
            }
        }
    }
}
