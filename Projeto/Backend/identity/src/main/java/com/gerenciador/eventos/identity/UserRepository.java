package com.gerenciador.eventos.identity;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.NotificationEvents;

/**
 * Repository JDBC de {@code identity.users} (schema do serviço; toda query qualificada, o Supavisor em transaction mode proíbe
 * {@code search_path}). As escritas recebem a {@link Connection} do chamador: a escrita e o evento de ciclo de vida
 * ({@link IdentityEvents}) entram na MESMA transação. {@code host} NUNCA é gravado aqui (só lido): nenhuma rota promove a host
 * (senão o {@code PUT /internal/users/{id}} viraria auto-promoção, IDOR #166).
 */
@Repository
public class UserRepository {

    @Autowired
    private DatabaseConnection databaseConnection;

    /**
     * Insere o usuário e preenche o id, os carimbos e o {@code host} gerados pelo banco (a carteira nasce no mesmo INSERT, pelo
     * trigger). Escreve na {@code conn} do chamador.
     */
    public User save(Connection conn, User user) throws SQLException {
        String sql = "INSERT INTO identity.users (user_name, email, fone, password, birthdate, admin) VALUES (?, ?, ?, ?, ?, ?) "
                + "RETURNING user_id, created_at, updated_at, host";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            // Valores com defaults para respeitar NOT NULL do schema
            String passwordVal = (user.getPassword() != null && !user.getPassword().isBlank()) ? user.getPassword() : "password";
            Date birthdateVal = toSqlDate(user.getBirthDate());
            if (birthdateVal == null) {
                birthdateVal = Date.valueOf(LocalDate.now());
            }
            stmt.setString(1, user.getName());
            stmt.setString(2, user.getEmail());
            stmt.setString(3, user.getFone());
            stmt.setString(4, passwordVal);
            stmt.setDate(5, birthdateVal);
            stmt.setBoolean(6, toPrimitive(user.getIsAdmin()));
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                user.setId(rs.getLong(1));
                user.setCreatedAt(rs.getTimestamp(2).toLocalDateTime());
                user.setUpdatedAt(rs.getTimestamp(3).toLocalDateTime());
                user.setIsHost(rs.getBoolean(4));
            }
            return user;
        }
    }

    /** Verificar se email já existe */
    public boolean emailExists(String email) {
        return existe("SELECT COUNT(*) FROM identity.users WHERE LOWER(email) = LOWER(?)", email, "email");
    }

    /** Verificar se nome já existe */
    public boolean nameExists(String name) {
        return existe("SELECT COUNT(*) FROM identity.users WHERE user_name = ?", name, "nome");
    }

    /** Verificar se fone já existe */
    public boolean foneExists(String fone) {
        return existe("SELECT COUNT(*) FROM identity.users WHERE fone = ?", fone, "fone");
    }

    private boolean existe(String sql, String valor, String campo) {
        try (Connection conn = databaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, valor);
            ResultSet rs = stmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao verificar " + campo + ": " + e.getMessage(), e);
        }
    }

    /** Buscar usuário por ID */
    public User findById(Long id) {
        try (Connection conn = databaseConnection.getConnection()) {
            return findById(conn, id);
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar usuário: " + e.getMessage(), e);
        }
    }

    /** Igual ao de cima, na {@code conn} do chamador (dentro da transação dele). */
    public User findById(Connection conn, Long id) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM identity.users WHERE user_id = ?")) {
            stmt.setLong(1, id);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? mapResultSetToUser(rs) : null;
        }
    }

    /** Nomes dos usuários ativos entre os ids, numa consulta ({@code user_id = ANY(?)}). */
    public Map<Long, String> findActiveNames(Collection<Long> ids) {
        String sql = "SELECT user_id, user_name FROM identity.users WHERE user_id = ANY(?) AND isactive";
        Map<Long, String> nomes = new HashMap<>();
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setArray(1, conn.createArrayOf("bigint", ids.toArray()));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    nomes.put(rs.getLong(1), rs.getString(2));
                }
            }
            return nomes;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar nomes de usuários: " + e.getMessage(), e);
        }
    }

    /** Buscar usuário por email */
    public User findByEmail(String email) {
        String sql = "SELECT * FROM identity.users WHERE LOWER(email) = LOWER(?)";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? mapResultSetToUser(rs) : null;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar usuário: " + e.getMessage(), e);
        }
    }

    /**
     * Atualiza os campos graváveis (nome, e-mail, fone, senha, nascimento, admin; NUNCA {@code host}) na {@code conn} do chamador.
     * Campo não informado ({@code password}, {@code birthDate} nulo ou ilegível, {@code isAdmin}) é preservado pelo próprio
     * {@code UPDATE} ({@code COALESCE}), sem ler-mesclar-gravar (#170): um PUT sem senha concorrente a uma troca de senha não
     * regrava o hash antigo por cima do novo. Devolve {@code false} se o usuário não existe.
     */
    public boolean update(Connection conn, User user) throws SQLException {
        String sql = "UPDATE identity.users SET user_name = ?, email = ?, fone = ?, password = COALESCE(?, password), "
                + "birthdate = COALESCE(CAST(? AS date), birthdate), admin = COALESCE(CAST(? AS boolean), admin) "
                + "WHERE user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.getName());
            stmt.setString(2, user.getEmail());
            stmt.setString(3, user.getFone());
            stmt.setString(4, user.getPassword());
            Date birthdateVal = toSqlDate(user.getBirthDate());
            if (birthdateVal != null) {
                stmt.setDate(5, birthdateVal);
            } else {
                stmt.setNull(5, Types.DATE);
            }
            if (user.getIsAdmin() != null) {
                stmt.setBoolean(6, user.getIsAdmin());
            } else {
                stmt.setNull(6, Types.BOOLEAN);
            }
            stmt.setLong(7, user.getId());
            return stmt.executeUpdate() == 1;
        }
    }

    /** Grava o hash da nova senha na {@code conn} do chamador. */
    public boolean updatePassword(Connection conn, Long userId, String passwordHash) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("UPDATE identity.users SET password = ? WHERE user_id = ?")) {
            stmt.setString(1, passwordHash);
            stmt.setLong(2, userId);
            return stmt.executeUpdate() == 1;
        }
    }

    /**
     * Troca a senha CONSUMINDO o código MFA, num UPDATE condicional (CAS): só vale se o código ainda é o gravado e não expirou, e
     * o mesmo statement o zera. Duas chamadas concorrentes com o mesmo código: só uma afeta a linha. Devolve {@code false} se perdeu.
     */
    public boolean updatePasswordConsumingMfa(Connection conn, Long userId, String passwordHash, String mfaCode) throws SQLException {
        String sql = "UPDATE identity.users SET password = ?, password_reset_mfa_code = NULL, password_reset_mfa_expiration = NULL "
                + "WHERE user_id = ? AND password_reset_mfa_code = ? AND password_reset_mfa_expiration > NOW()";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, passwordHash);
            stmt.setLong(2, userId);
            stmt.setString(3, mfaCode);
            return stmt.executeUpdate() == 1;
        }
    }

    /** Soft delete: marca como inativo. Devolve {@code false} se o usuário não existe. Escreve na {@code conn} do chamador. */
    public boolean softDelete(Connection conn, Long id) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("UPDATE identity.users SET isActive = false WHERE user_id = ?")) {
            stmt.setLong(1, id);
            return stmt.executeUpdate() == 1;
        }
    }

    /** Mapear ResultSet para objeto User */
    private User mapResultSetToUser(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(rs.getLong("user_id"));
        user.setName(rs.getString("user_name"));
        user.setEmail(rs.getString("email"));
        user.setFone(rs.getString("fone"));
        user.setPassword(rs.getString("password"));
        // birthdate é DATE; converter para string ISO yyyy-MM-dd
        Date bd = rs.getDate("birthdate");
        if (bd != null) {
            user.setBirthDate(bd.toString());
        }
        user.setIsAdmin(rs.getBoolean("admin"));
        // Coluna criada sem aspas no DDL ("Host boolean ..."), então o Postgres dobra o
        // identificador para minúsculas — igual "isActive"/"isactive" logo abaixo.
        user.setIsHost(rs.getBoolean("host"));
        user.setIsActive(rs.getBoolean("isactive"));

        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            user.setCreatedAt(createdAt.toLocalDateTime());
        }
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) {
            user.setUpdatedAt(updatedAt.toLocalDateTime());
        }
        return user;
    }

    /**
     * Guarda o código MFA do reset de senha e a validade, na {@code conn} do chamador (transação dele): o código e o evento do
     * e-mail entram juntos.
     */
    public void setPasswordResetMfaCode(Connection conn, Long userId, String mfaCode) throws SQLException {
        String sql = "UPDATE identity.users SET password_reset_mfa_code = ?, "
                + "password_reset_mfa_expiration = NOW() + make_interval(mins => ?) WHERE user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, mfaCode);
            stmt.setInt(2, (int) NotificationEvents.MFA_VALIDADE.toMinutes());
            stmt.setLong(3, userId);
            stmt.executeUpdate();
        }
    }

    /** Validar código MFA para reset de senha (código nulo ou usuário inexistente = {@code false}). */
    public boolean verifyPasswordResetMfaCode(Long userId, String mfaCode) {
        String sql = "SELECT COUNT(*) FROM identity.users WHERE user_id = ? AND password_reset_mfa_code = ? "
                + "AND password_reset_mfa_expiration > NOW()";
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setString(2, mfaCode);
            ResultSet rs = stmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao validar MFA: " + e.getMessage(), e);
        }
    }

    private boolean toPrimitive(Boolean b) {
        return Boolean.TRUE.equals(b);
    }

    private Date toSqlDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            // Tenta ISO-8601 primeiro
            return Date.valueOf(LocalDate.parse(dateStr));
        } catch (DateTimeParseException ex) {
            try {
                // Tenta formato dd/MM/yyyy
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
                return Date.valueOf(LocalDate.parse(dateStr, fmt));
            } catch (DateTimeParseException ex2) {
                return null;
            }
        }
    }
}
