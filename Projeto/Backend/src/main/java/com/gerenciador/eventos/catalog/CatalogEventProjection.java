package com.gerenciador.eventos.catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.gerenciador.eventos.shared.events.EventEnvelope;
import com.gerenciador.eventos.shared.events.Projection;

/**
 * Projeção do stream {@code catalog.event} nas tabelas {@code event} e {@code eventpresenter} (read model, issue #28).
 *
 * <p>É INLINE: o command side (ver {@link CatalogEventWriter}) a aplica na MESMA transação do append, com
 * {@code schema = "public"}; o replay a aplica com o mesmo código no schema-sombra. Determinística: tudo vem do
 * payload (a única exceção são os carimbos {@code created_at}/{@code updated_at}, que seguem o default/CURRENT_TIMESTAMP
 * do banco e ficam fora da comparação do replay). {@code image_data} NÃO vem do log (blob servido por
 * {@code /bff/events/{id}/image}): quem escreve a imagem é o command side, direto na tabela viva.</p>
 *
 * <p>{@code capacity}/{@code buyTimeLimit} vão só no {@code EventCreated} (o {@code eventcap} nasce na mesma transação), mas {@code eventcap} é do agregado de
 * {@code reservation} (CAS) e não é projetado aqui.</p>
 */
@Component
public class CatalogEventProjection implements Projection {

    public static final String STREAM_TYPE = "catalog.event";

    @Override
    public String name() {
        return STREAM_TYPE;
    }

    @Override
    public List<String> tables() {
        return List.of("event", "eventpresenter");
    }

    @Override
    public void apply(Connection conn, EventEnvelope ev, String schema) throws SQLException {
        JsonNode p = ev.payload();
        long id = Long.parseLong(ev.streamId());
        switch (ev.eventType()) {
            case "EventCreated" -> {
                criar(conn, schema, id, p, ev.version());
                for (JsonNode nome : p.path("presenters")) {
                    inserirApresentador(conn, schema, id, nome.asText());
                }
            }
            case "EventUpdated" -> atualizar(conn, schema, id, p, ev.version());
            case "PresenterAdded" -> {
                inserirApresentador(conn, schema, id, p.get("presenterName").asText());
                avancarVersao(conn, schema, id, ev.version());
            }
            case "PresenterRemoved" -> {
                executar(conn, "DELETE FROM " + schema + ".eventpresenter WHERE event_id = ? AND presenter_name = ?",
                        id, p.get("presenterName").asText());
                avancarVersao(conn, schema, id, ev.version());
            }
            case "EventCancelled" -> {
                // Na viva, eventcap e event_availability caem por ON DELETE CASCADE (a sombra não tem FK).
                executar(conn, "DELETE FROM " + schema + ".eventpresenter WHERE event_id = ?", id);
                executar(conn, "DELETE FROM " + schema + ".event WHERE event_id = ?", id);
            }
            default -> throw new IllegalArgumentException("Evento desconhecido em catalog.event: " + ev.eventType());
        }
    }

    private void criar(Connection conn, String schema, long id, JsonNode p, int version) throws SQLException {
        String sql = "INSERT INTO " + schema + ".event (event_id, creator_id, event_name, ead, address, event_date, "
                + "description, version) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, ?, ?::timestamp, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, p.get("creatorId").asLong());
            ps.setString(3, p.get("eventName").asText());
            ps.setBoolean(4, p.get("isEad").asBoolean());
            ps.setString(5, textoOuNulo(p.get("address")));
            ps.setString(6, p.get("eventDate").asText());
            ps.setString(7, p.get("description").asText());
            ps.setInt(8, version);
            ps.executeUpdate();
        }
    }

    private void atualizar(Connection conn, String schema, long id, JsonNode p, int version) throws SQLException {
        String sql = "UPDATE " + schema + ".event SET creator_id = ?, event_name = ?, ead = ?, address = ?, "
                + "event_date = ?::timestamp, description = ?, updated_at = CURRENT_TIMESTAMP, version = ? "
                + "WHERE event_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, p.get("creatorId").asLong());
            ps.setString(2, p.get("eventName").asText());
            ps.setBoolean(3, p.get("isEad").asBoolean());
            ps.setString(4, textoOuNulo(p.get("address")));
            ps.setString(5, p.get("eventDate").asText());
            ps.setString(6, p.get("description").asText());
            ps.setInt(7, version);
            ps.setLong(8, id);
            ps.executeUpdate();
        }
    }

    private void inserirApresentador(Connection conn, String schema, long id, String nome) throws SQLException {
        executar(conn, "INSERT INTO " + schema + ".eventpresenter (event_id, presenter_name) VALUES (?, ?) "
                + "ON CONFLICT DO NOTHING", id, nome);
    }

    private void avancarVersao(Connection conn, String schema, long id, int version) throws SQLException {
        executar(conn, "UPDATE " + schema + ".event SET version = ? WHERE event_id = ?", version, id);
    }

    /** Executa um statement cujos parâmetros são {@code long}/{@code int}/{@code String}. */
    private void executar(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    private static String textoOuNulo(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }
}
