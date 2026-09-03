package com.gerenciador.eventos.POJO;

import java.time.LocalDateTime;

public class EventWallet {
    private Long user_id;
    private Long event_id;
    private String status;              // confirmed | pending | expired | cancelled
    private LocalDateTime expiresAt;    // preenchido só quando status = pending
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // construtor vazio
    public EventWallet() {
        this.user_id = null;
        this.event_id = null;
        this.status = null;
        this.expiresAt = null;
        this.createdAt = null;
        this.updatedAt = null;
    }

    //construtor completo
    public EventWallet(Long user_id, Long event_id) {
        this.user_id = user_id;
        this.event_id = event_id;
        this.status = null;
        this.expiresAt = null;
        this.createdAt = null;
        this.updatedAt = null;
    }

    // getters
    public Long getUserId() {return user_id;}

    public Long getEventId() {return event_id;}

    public String getStatus() {return status;}

    public LocalDateTime getExpiresAt() {return expiresAt;}

    public LocalDateTime getCreatedAt() {return createdAt;}

    public LocalDateTime getUpdatedAt() {return updatedAt;}

    //setters
    public void setUserId(Long user_id) {this.user_id = user_id;}

    public void setEventId(Long event_id) {this.event_id = event_id;}

    public void setStatus(String status) {this.status = status;}

    public void setExpiresAt(LocalDateTime expiresAt) {this.expiresAt = expiresAt;}

    public void setCreatedAt(LocalDateTime createdAt) {this.createdAt = createdAt;}

    public void setUpdatedAt(LocalDateTime updatedAt) {this.updatedAt = updatedAt;}

    @Override
    public String toString() {
        return "EventWallet{" +
                "user_id=" + user_id +
                ", event_id=" + event_id +
                ", status='" + status + '\'' +
                ", expiresAt=" + expiresAt +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
