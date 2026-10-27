package com.gerenciador.eventos.identity;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class PendingVerification {
    private Long id;
    private String email;
    private String fone;
    private String userName;
    private String passwordHash;
    private LocalDate birthdate;
    private String verificationCode;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private int attempts;
    private int maxAttempts;

    public PendingVerification() {
    }

    public PendingVerification(String email, String fone, String userName, String passwordHash, 
                               LocalDate birthdate, String verificationCode, LocalDateTime expiresAt) {
        this.email = email;
        this.fone = fone;
        this.userName = userName;
        this.passwordHash = passwordHash;
        this.birthdate = birthdate;
        this.verificationCode = verificationCode;
        this.expiresAt = expiresAt;
        this.attempts = 0;
        this.maxAttempts = 5;
        this.createdAt = LocalDateTime.now();
    }

    // Getters e Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFone() {
        return fone;
    }

    public void setFone(String fone) {
        this.fone = fone;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public LocalDate getBirthdate() {
        return birthdate;
    }

    public void setBirthdate(LocalDate birthdate) {
        this.birthdate = birthdate;
    }

    public String getVerificationCode() {
        return verificationCode;
    }

    public void setVerificationCode(String verificationCode) {
        this.verificationCode = verificationCode;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean hasExceededAttempts() {
        return attempts >= maxAttempts;
    }
}
