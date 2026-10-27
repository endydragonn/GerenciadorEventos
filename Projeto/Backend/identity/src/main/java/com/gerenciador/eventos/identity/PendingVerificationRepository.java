package com.gerenciador.eventos.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.gerenciador.eventos.shared.DatabaseConnection;

/** Repository JDBC de {@code identity.pending_verifications} (cadastro em duas fases; schema do serviço). */
@Repository
public class PendingVerificationRepository {

    @Autowired
    private DatabaseConnection databaseConnection;

    /** Grava na {@code conn} do chamador (transação dele): o código e o evento do e-mail entram juntos (#32). */
    public void save(Connection conn, PendingVerification verification) throws SQLException {
        String sql = "INSERT INTO identity.pending_verifications (email, fone, user_name, password_hash, birthdate, verification_code, expires_at, created_at, attempts, max_attempts) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, verification.getEmail());
            stmt.setString(2, verification.getFone());
            stmt.setString(3, verification.getUserName());
            stmt.setString(4, verification.getPasswordHash());
            stmt.setDate(5, java.sql.Date.valueOf(verification.getBirthdate()));
            stmt.setString(6, verification.getVerificationCode());
            stmt.setTimestamp(7, Timestamp.valueOf(verification.getExpiresAt()));
            stmt.setTimestamp(8, Timestamp.valueOf(verification.getCreatedAt()));
            stmt.setInt(9, verification.getAttempts());
            stmt.setInt(10, verification.getMaxAttempts());
            stmt.executeUpdate();
        }
    }

    public Optional<PendingVerification> findByEmail(String email) throws SQLException {
        String sql = "SELECT * FROM identity.pending_verifications WHERE email = ? ORDER BY created_at DESC LIMIT 1";
        
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return Optional.of(mapRowToVerification(rs));
            }
        }
        return Optional.empty();
    }

    /**
     * Consome UMA tentativa de forma atômica: {@code attempts + 1} só se ainda há tentativa ({@code attempts < max_attempts}).
     * {@code false} = tentativas esgotadas. O teste e a escrita num UPDATE só: threads concorrentes adivinhando o código não
     * passam do limite.
     */
    public boolean consumeAttempt(Long id) throws SQLException {
        String sql = "UPDATE identity.pending_verifications SET attempts = attempts + 1 WHERE id = ? AND attempts < max_attempts";
        try (Connection conn = databaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            return stmt.executeUpdate() == 1;
        }
    }

    public void delete(Long id) throws SQLException {
        try (Connection conn = databaseConnection.getConnection()) {
            delete(conn, id);
        }
    }

    /** Apaga na {@code conn} do chamador (transação dele): o usuário criado e o fim da verificação pendente entram juntos. */
    public void delete(Connection conn, Long id) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM identity.pending_verifications WHERE id = ?")) {
            stmt.setLong(1, id);
            stmt.executeUpdate();
        }
    }

    /** Apaga na {@code conn} do chamador (transação dele). */
    public void deleteByEmail(Connection conn, String email) throws SQLException {
        String sql = "DELETE FROM identity.pending_verifications WHERE email = ?";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            stmt.executeUpdate();
        }
    }

    private PendingVerification mapRowToVerification(ResultSet rs) throws SQLException {
        PendingVerification verification = new PendingVerification();
        verification.setId(rs.getLong("id"));
        verification.setEmail(rs.getString("email"));
        verification.setFone(rs.getString("fone"));
        verification.setUserName(rs.getString("user_name"));
        verification.setPasswordHash(rs.getString("password_hash"));
        verification.setBirthdate(rs.getDate("birthdate").toLocalDate());
        verification.setVerificationCode(rs.getString("verification_code"));
        verification.setExpiresAt(rs.getTimestamp("expires_at").toLocalDateTime());
        verification.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        verification.setAttempts(rs.getInt("attempts"));
        verification.setMaxAttempts(rs.getInt("max_attempts"));
        return verification;
    }
}
