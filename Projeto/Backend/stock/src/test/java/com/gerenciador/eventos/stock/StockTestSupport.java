package com.gerenciador.eventos.stock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;

import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.reservationclient.api.EventCap;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Base dos testes do serviço stock. Como o serviço não enxerga {@code users}/{@code event} (outro schema, sem FK), "usuário" e
 * "evento" são só ids: o evento ganha o {@code eventcap} (que é o "evento existe" dentro do serviço). Os ids vêm de um contador por
 * JVM, a partir de 1_000: classes diferentes nunca colidem, e ficam longe dos ids altos dos testes de reconciliação.
 */
abstract class StockTestSupport {

    private static final AtomicLong IDS = new AtomicLong(1_000);

    @Autowired
    protected EventCapRepository eventCapRepository;
    @Autowired
    protected DatabaseConnection db;

    protected static long novoId() {
        return IDS.getAndIncrement();
    }

    /** Evento com capacidade {@code capacidade} (-1 = ilimitado), sem janela de compra. */
    protected long novoEvento(int capacidade) {
        long id = novoId();
        eventCapRepository.create(new EventCap(id, capacidade, capacidade, null));
        return id;
    }

    protected int quant(long eventId) {
        return eventCapRepository.findByEventId(eventId).getQuant();
    }

    /** {@code held}, {@code released} ou {@code null} se o hold não existe. */
    protected String statusDoHold(UUID holdId) throws Exception {
        return coluna("SELECT status FROM stock.hold WHERE hold_id = ?", holdId);
    }

    protected Boolean debitado(UUID holdId) throws Exception {
        String v = coluna("SELECT decremented::text FROM stock.hold WHERE hold_id = ?", holdId);
        return v == null ? null : Boolean.valueOf(v);
    }

    protected int contarHolds(long eventId, String status, Boolean debitado) throws Exception {
        String sql = "SELECT count(*) FROM stock.hold WHERE event_id = ? AND status = ?"
                + (debitado == null ? "" : " AND decremented = " + debitado);
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, eventId);
            ps.setString(2, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** A invariante do stock: {@code quant = capacity - holds held e debitados} (evento limitado). */
    protected void assertInvariante(long eventId) throws Exception {
        EventCap cap = eventCapRepository.findByEventId(eventId);
        org.junit.jupiter.api.Assertions.assertEquals(cap.getCapacity() - contarHolds(eventId, "held", true), cap.getQuant(),
                "quant = capacity - holds held debitados (evento " + eventId + ")");
    }

    /** Espera até haver pelo menos {@code n} sessões deste banco paradas num lock. */
    protected void aguardarEsperandoLock(int n) throws Exception {
        EventsTestSupport.aguardar(Duration.ofSeconds(30), () -> {
            try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) >= n;
            } catch (Exception ex) {
                return false;
            }
        }, n + " sessão(ões) esperando lock");
    }

    private String coluna(String sql, UUID holdId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, holdId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
