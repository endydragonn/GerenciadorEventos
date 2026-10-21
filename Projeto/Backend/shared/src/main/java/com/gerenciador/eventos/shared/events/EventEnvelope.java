package com.gerenciador.eventos.shared.events;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Envelope do evento de domínio (ADR Event-Backbone-Event-Store, seção 3): igual nas colunas do
 * {@code event_store} e no corpo JSON da mensagem do tópico.
 *
 * @param version        versão no stream, começa em 1; base da idempotência do consumer
 * @param globalPosition identidade/ordem de ALOCAÇÃO no event_store (não é ordem de commit)
 * @param schemaVersion  versão do formato do {@code payload} daquele {@code eventType}
 * @param occurredAt     instante do append ({@code clock_timestamp()}); início da medição de lag
 * @param payload        dados do evento com os valores já calculados; nunca dado pessoal
 */
public record EventEnvelope(
        UUID eventId,
        String streamType,
        String streamId,
        int version,
        long globalPosition,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        JsonNode payload,
        JsonNode metadata) {
}
