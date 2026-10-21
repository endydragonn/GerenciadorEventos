package com.gerenciador.eventos.shared.events;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Evento a appendar. {@code version} é a versão ESPERADA no stream (a do estado lido na mesma transação + 1).
 *
 * @param partitionKey chave da mensagem no tópico: o id do evento DE DOMÍNIO ({@code event.event_id}), não o
 *                     {@code eventId} do envelope; reservas do mesmo evento caem na mesma partição, em ordem
 */
public record NovoEvento(
        UUID eventId,
        String streamType,
        String streamId,
        int version,
        String eventType,
        int schemaVersion,
        JsonNode payload,
        JsonNode metadata,
        String partitionKey) {

    /** Atalho: {@code eventId} novo, {@code schemaVersion} 1 e metadata vazio. */
    public static NovoEvento of(String streamType, String streamId, int version, String eventType,
            JsonNode payload, String partitionKey) {
        return new NovoEvento(UUID.randomUUID(), streamType, streamId, version, eventType, 1, payload,
                JsonNodeFactory.instance.objectNode(), partitionKey);
    }
}
