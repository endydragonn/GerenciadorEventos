package com.gerenciador.eventos.stockclient.api;

/** Corpo de {@code POST /internal/holds/{holdId}/release}: leva o evento e o usuário só para gravar a lápide de um hold que não existia. */
public record ReleaseRequest(Long eventId, Long userId) { }
