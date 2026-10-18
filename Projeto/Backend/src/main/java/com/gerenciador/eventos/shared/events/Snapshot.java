package com.gerenciador.eventos.shared.events;

import com.fasterxml.jackson.databind.JsonNode;

/** Estado de um stream até a {@code version} (inclusive). Cache descartável: nunca é fonte de verdade. */
public record Snapshot(String streamType, String streamId, int version, JsonNode state) {
}
