package com.gerenciador.eventos.shared.events;

/**
 * Buraco no stream que não existe nem no {@code event_store}: a mensagem da versão n chegou, mas a n-1 (ou anterior)
 * não está gravada. Pela ordem por stream isso não deve acontecer; se acontecer o consumer não avança nada e o
 * {@link EventsKafkaConfig error handler} tenta de novo algumas vezes antes de mandar a mensagem para o DLT.
 */
public class EventGapException extends IllegalStateException {

    public EventGapException(String message) {
        super(message);
    }
}
