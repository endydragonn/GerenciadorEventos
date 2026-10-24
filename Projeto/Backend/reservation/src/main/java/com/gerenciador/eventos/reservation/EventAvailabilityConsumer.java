package com.gerenciador.eventos.reservation;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.gerenciador.eventos.shared.events.EventEnvelope;
import com.gerenciador.eventos.shared.events.IdempotentConsumer;

/**
 * Listener do tópico {@code reservation.wallet} que alimenta {@link EventAvailabilityProjection} (issue #29). Só com
 * {@code events.consumer.enabled=true}. Toda a idempotência (checkpoint por stream, conferência do {@code eventId},
 * ack manual depois do commit, política de erro) é do {@link IdempotentConsumer} da #27.
 *
 * <p><b>Em lote (#146):</b> o listener recebe até {@code events.consumer.batch-size} registros por poll
 * ({@code EVENTS_CONSUMER_BATCH_SIZE}, default 500; vira o {@code max.poll.records} só deste listener) e o
 * {@link IdempotentConsumer#onBatch} aplica o lote numa transação e dá um ack. Com pouca carga o lote tem 1 registro
 * (igual a antes); com o consumer atrasado, até o tamanho máximo. Só este listener é em lote. O lag por evento só é
 * registrado quando o lote inteiro tem sucesso: se ele falha, o prefixo comitado pelo {@code onBatch} não aparece no
 * {@code events.lag}.</p>
 *
 * <p>{@code idIsGroup = false} é obrigatório: por padrão o {@code id} do listener vira o {@code group.id}, e aí o
 * {@code spring.kafka.consumer.group-id} ({@code EVENTS_CONSUMER_GROUP}) não valeria.</p>
 *
 * <p><b>Lag por evento</b> (ADR, seção 4), no logger {@code events.lag} (ligue com
 * {@code LOGGING_LEVEL_EVENTS_LAG=DEBUG}; DESLIGADO por padrão, porque
 * um log por evento no thread do único consumer infla o {@code consumer_ms} que a Rodada 2 mede): {@code relay_ms} = {@code publishedAt - occurredAt},
 * {@code consumer_ms} = aplicação - {@code publishedAt}, {@code total_ms} = aplicação - {@code occurredAt}. A aplicação
 * é medida logo depois do commit (o {@code applied_at} da linha é o {@code clock_timestamp()} um pouco antes dele).</p>
 */
@Component
@ConditionalOnProperty(name = "events.consumer.enabled", havingValue = "true")
public class EventAvailabilityConsumer {

    public static final String LISTENER_ID = "reservation-availability";
    private static final Logger lagLog = LoggerFactory.getLogger("events.lag");

    private final IdempotentConsumer consumer;
    private final EventAvailabilityProjection projection = new EventAvailabilityProjection();

    public EventAvailabilityConsumer(IdempotentConsumer consumer) {
        this.consumer = consumer;
    }

    /** Tópico com 1 partição: o evento quente fica numa partição só, mais partições não ajudam (ver a ADR). */
    @Bean
    NewTopic reservationWalletTopic() {
        return TopicBuilder.name(ReservationEvents.STREAM_TYPE).partitions(1).replicas(1).build();
    }

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = ReservationEvents.STREAM_TYPE, batch = "true",
            properties = "max.poll.records=${events.consumer.batch-size:500}")
    void ouvir(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        List<Optional<EventEnvelope>> aplicadas = consumer.onBatch(projection,
                records.stream().map(ConsumerRecord::value).toList(), ack);
        if (lagLog.isDebugEnabled()) {
            for (int i = 0; i < records.size(); i++) {
                registrarLag(aplicadas.get(i), records.get(i));
            }
        }
    }

    private void registrarLag(Optional<EventEnvelope> aplicada, ConsumerRecord<String, String> record) {
        if (aplicada.isEmpty()) {
            return;
        }
        EventEnvelope e = aplicada.get();
        Instant aplicado = Instant.now();
        Header h = record.headers().lastHeader("publishedAt");
        Instant publicado = h == null ? null : Instant.parse(new String(h.value(), StandardCharsets.UTF_8));
        lagLog.debug("event={} type={} stream={} v={} relay_ms={} consumer_ms={} total_ms={}",
                e.payload().get("eventId").asLong(), e.eventType(), e.streamId(), e.version(),
                publicado == null ? -1 : Duration.between(e.occurredAt(), publicado).toMillis(),
                publicado == null ? -1 : Duration.between(publicado, aplicado).toMillis(),
                Duration.between(e.occurredAt(), aplicado).toMillis());
    }
}
