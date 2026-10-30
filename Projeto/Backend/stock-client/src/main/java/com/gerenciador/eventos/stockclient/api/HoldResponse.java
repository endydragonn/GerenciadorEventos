package com.gerenciador.eventos.stockclient.api;

import java.util.UUID;

/** Resposta de {@code POST /internal/holds} (201, ou 200 na repetição): {@code maxTimeInLine} em segundos, do {@code eventcap}. */
public record HoldResponse(UUID holdId, int maxTimeInLine) { }
