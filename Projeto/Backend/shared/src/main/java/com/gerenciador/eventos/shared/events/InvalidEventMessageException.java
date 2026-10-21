package com.gerenciador.eventos.shared.events;

/**
 * A mensagem do tópico não é um {@link EventEnvelope} válido (JSON quebrado ou campos obrigatórios ausentes). Reentregar
 * não conserta: o {@link EventsKafkaConfig error handler} manda direto para o DLT, sem retry.
 */
public class InvalidEventMessageException extends RuntimeException {

    public InvalidEventMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
