package com.gerenciador.eventos.stock;

import java.time.DateTimeException;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.stockclient.api.EventCap;
import com.gerenciador.eventos.shared.events.EventEnvelope;
import com.gerenciador.eventos.shared.events.InvalidEventMessageException;

/**
 * Reconciliação do {@code eventcap} pelo tópico {@code catalog.event} (issue #33, decisão 3; movida do reservation para o stock na #47): o catalog cria a
 * capacidade por chamada síncrona DEPOIS de comitar o evento; se essa chamada falhar (serviço fora, rede), o evento já existe
 * sem {@code eventcap} e este consumer o cria a partir do {@code EventCreated} (que leva {@code capacity} e
 * {@code buyTimeLimit}). A criação é idempotente por {@code event_id} ({@code ON CONFLICT DO NOTHING}), então quem chega
 * primeiro vence, a repetição e a reentrega são no-op e NÃO é preciso checkpoint — por isso este consumer não usa o
 * {@code IdempotentConsumer} (que conferiria o {@code eventId} contra o {@code event_store} do catalog, que este serviço
 * não lê). Só trata {@code EventCreated}; os outros tipos do stream são ignorados. Cancelamento de evento NÃO passa por aqui:
 * o catalog remove a capacidade por chamada síncrona (com a recusa se houver reservas).
 *
 * <p><b>Group próprio e {@code auto.offset.reset=latest}</b>: um group novo NÃO relê o histórico do tópico (ids de evento
 * são reaproveitados depois de {@code TRUNCATE ... RESTART IDENTITY}, e um {@code EventCreated} antigo criaria uma capacidade
 * errada para o evento novo de mesmo id). Só vale se ambas as flags estiverem ligadas: {@code events.consumer.enabled}
 * (política de erro/DLT da {@code shared}) e {@code stock.catalog-reconciliation.enabled}. Janela de falha
 * documentada: evento criado com o stock fora e o group nunca criado só é reconciliado a partir do primeiro start.</p>
 */
@Component
@ConditionalOnProperty(name = {"events.consumer.enabled", "stock.catalog-reconciliation.enabled"}, havingValue = "true")
public class CatalogReconciliationConsumer {

    public static final String LISTENER_ID = "stock-catalog-reconciliation";
    public static final String TOPICO = "catalog.event";
    static final String EVENT_CREATED = "EventCreated";

    private static final Logger log = LoggerFactory.getLogger(CatalogReconciliationConsumer.class);

    private final EventCapRepository eventCapRepository;
    private final ObjectMapper json;

    public CatalogReconciliationConsumer(EventCapRepository eventCapRepository, ObjectMapper json) {
        this.eventCapRepository = eventCapRepository;
        this.json = json;
    }

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = TOPICO,
            groupId = "${stock.catalog-reconciliation.group:stock-catalog}",
            properties = "auto.offset.reset=latest")
    void ouvir(String value, Acknowledgment ack) {
        reconciliar(value);
        ack.acknowledge();
    }

    /** @return {@code true} se criou o {@code eventcap} que faltava; {@code false} se já existia ou não é {@code EventCreated}. */
    boolean reconciliar(String value) {
        EventEnvelope e = parse(value);
        if (!EVENT_CREATED.equals(e.eventType())) {
            return false;
        }
        JsonNode p = e.payload();
        if (p == null || !p.hasNonNull("eventId") || !p.hasNonNull("capacity")) {
            throw new InvalidEventMessageException("EventCreated sem eventId/capacity", null);
        }
        LocalDateTime janela = null;
        if (p.hasNonNull("buyTimeLimit")) {
            try {
                janela = LocalDateTime.parse(p.get("buyTimeLimit").asText());
            } catch (DateTimeException ex) {
                throw new InvalidEventMessageException("EventCreated com buyTimeLimit inválido", ex);
            }
        }
        int capacity = p.get("capacity").asInt();
        if (capacity != EventCap.UNLIMITED && capacity < 1) { // viola eventcap_capacity_valid: SQLException seria retentada sem limite
            throw new InvalidEventMessageException("EventCreated com capacity inválida (" + capacity + "): esperado >= 1 ou -1", null);
        }
        boolean criou = eventCapRepository.create(new EventCap(p.get("eventId").asLong(), capacity, capacity, janela));
        if (criou) {
            log.warn("[CatalogReconciliation] eventcap do evento {} criado pelo EventCreated: a criação síncrona do catalog "
                    + "não chegou", p.get("eventId").asLong());
        }
        return criou;
    }

    private EventEnvelope parse(String value) {
        if (value == null) {
            throw new InvalidEventMessageException("Mensagem sem corpo", null);
        }
        try {
            EventEnvelope e = json.readValue(value, EventEnvelope.class);
            if (e == null || e.eventType() == null) {
                throw new InvalidEventMessageException("Envelope sem eventType", null);
            }
            return e;
        } catch (JsonProcessingException ex) {
            throw new InvalidEventMessageException("Mensagem não é um envelope de evento válido", ex);
        }
    }
}
