package com.gerenciador.eventos.catalog;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gerenciador.eventos.catalog.api.Event;

/**
 * Payloads dos eventos do stream {@code catalog.event}, com os valores JÁ CALCULADOS (defaults aplicados), para o
 * replay não depender de regra nenhuma. Só ids e dados do domínio do evento: nada de e-mail/telefone/senha (o
 * {@code event_store} é imutável) e sem {@code image_data} (blob fora do ES). Datas em ISO-8601.
 */
final class EventPayloads {

    private EventPayloads() {
    }

    /**
     * Só os metadados do evento ({@code EventUpdated}). Capacidade/janela de venda NÃO entram: são estado de
     * {@code reservation} ({@code eventcap}), mudam em outra transação ({@code updatePolicy}) e podem ser recusadas
     * (CHECK) depois do append.
     */
    static ObjectNode evento(long id, Event e) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("eventId", id);
        p.put("creatorId", e.getCreator_id());
        p.put("eventName", e.getEvent_name());
        p.put("isEad", Boolean.TRUE.equals(e.getIs_EAD()));
        String address = e.getAddress() == null || e.getAddress().isBlank() ? null : e.getAddress().trim();
        p.put("address", address);
        p.put("eventDate", e.getEvent_date().toString());
        p.put("description", e.getDescription() != null ? e.getDescription() : "");
        return p;
    }

    /** {@code EventCreated}: metadados + capacidade/janela de venda iniciais (o {@code eventcap} nasce logo depois do commit, por chamada ao reservation; o reservation o reconcilia por este evento). */
    static ObjectNode criado(long id, Event e) {
        ObjectNode p = evento(id, e);
        p.put("capacity", e.getLot_quantity());
        p.put("buyTimeLimit",
                (e.getBuy_time_limit() != null ? e.getBuy_time_limit() : e.getEvent_date()).toString());
        return p;
    }

    static ObjectNode apresentador(long id, String nome) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("eventId", id);
        p.put("presenterName", nome);
        return p;
    }

    static ObjectNode cancelado(long id) {
        return JsonNodeFactory.instance.objectNode().put("eventId", id);
    }
}
