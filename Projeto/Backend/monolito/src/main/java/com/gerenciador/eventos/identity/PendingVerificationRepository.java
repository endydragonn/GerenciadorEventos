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

@Repository
public class PendingVerificationRepository {

    @Autowired
    private DatabaseConnection databaseConnection;

    /** Grava na {@code conn} do chamador (transação dele): o código e o evento do e-mail entram juntos (#32). */
    public void save(Connection conn, PendingVerification verification) throws SQLException {
        String sql = "INSERT INTO pending_verifications (email, fone, user_name, password_hash, birthdate, verification_code, expires_at, created_at, attempts, max_attempts) " +
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
        String sql = "SELECT * FROM pending_verifications WHERE email = ? ORDER BY created_at DESC LIMIT 1";
        
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

    public Optional<PendingVerification> findByEmailAndCode(String email, String code) throws SQLException {
        String sql = "SELECT * FROM pending_verifications WHERE email = ? AND verification_code = ?";
        
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            stmt.setString(2, code);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return Optional.of(mapRowToVerification(rs));
            }
        }
        return Optional.empty();
    }

    public void incrementAttempts(Long id) throws SQLException {
        String sql = "UPDATE pending_verifications SET attempts = attempts + 1 WHERE id = ?";
        
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            stmt.executeUpdate();
        }
    }

    public void delete(Long id) throws SQLException {
        String sql = "DELETE FROM pending_verifications WHERE id = ?";
        
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            stmt.executeUpdate();
        }
    }

    /** Apaga na {@code conn} do chamador (transação dele). */
    public void deleteByEmail(Connection conn, String email) throws SQLException {
        String sql = "DELETE FROM pending_verifications WHERE email = ?";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            stmt.executeUpdate();
        }
    }

    public void update(PendingVerification verification) throws SQLException {
        String sql = "UPDATE pending_verifications SET verification_code = ?, expires_at = ?, attempts = ? WHERE email = ?";
        
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, verification.getVerificationCode());
            stmt.setTimestamp(2, Timestamp.valueOf(verification.getExpiresAt()));
            stmt.setInt(3, verification.getAttempts());
            stmt.setString(4, verification.getEmail());
            stmt.executeUpdate();
        }
    }

    public void cleanupExpiredVerifications() throws SQLException {
        String sql = "DELETE FROM pending_verifications WHERE expires_at < CURRENT_TIMESTAMP";
        
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
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
