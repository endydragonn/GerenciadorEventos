package com.gerenciador.eventos.reservation.api;

import java.time.LocalDateTime;

/**
 * Agregado isolado de capacidade/vagas de um evento (tabela public.eventcap).
 *
 * <p>Fronteira de consistência forte, separada dos metadados de {@link Event}. A invariante
 * {@code 0 <= quant <= capacity} é protegida pelo CHECK no banco e pela reserva via UPDATE
 * condicional atômico feita em {@code EventCapRepository}.</p>
 */
public class EventCap {

    /** Valor de {@code capacity}/{@code quant} que marca um evento sem limite de vendas. */
    public static final int UNLIMITED = -1;

    private Long eventId;
    private Integer capacity;        // total de vagas; -1 = sem limite
    private Integer quant;           // vagas disponíveis; -1 quando ilimitado
    private LocalDateTime buyTimeLimit;   // até quando o evento aceita compra
    private Integer maxTimeInLine;   // segundos que uma reserva fica pending antes de expirar
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public EventCap() {
        this.maxTimeInLine = 120;
    }

    public EventCap(Long eventId, Integer capacity, Integer quant, LocalDateTime buyTimeLimit) {
        this.eventId = eventId;
        this.capacity = capacity;
        this.quant = quant;
        this.buyTimeLimit = buyTimeLimit;
        this.maxTimeInLine = 120;
    }

    /** {@code true} se o evento não tem limite de vendas ({@code capacity == -1}). */
    public boolean isUnlimited() {
        return capacity != null && capacity == UNLIMITED;
    }

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }

    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }

    public Integer getQuant() { return quant; }
    public void setQuant(Integer quant) { this.quant = quant; }

    public LocalDateTime getBuyTimeLimit() { return buyTimeLimit; }
    public void setBuyTimeLimit(LocalDateTime buyTimeLimit) { this.buyTimeLimit = buyTimeLimit; }

    public Integer getMaxTimeInLine() { return maxTimeInLine; }
    public void setMaxTimeInLine(Integer maxTimeInLine) { this.maxTimeInLine = maxTimeInLine; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "EventCap{" +
                "eventId=" + eventId +
                ", capacity=" + capacity +
                ", quant=" + quant +
                ", buyTimeLimit=" + buyTimeLimit +
                ", maxTimeInLine=" + maxTimeInLine +
                '}';
    }
}
