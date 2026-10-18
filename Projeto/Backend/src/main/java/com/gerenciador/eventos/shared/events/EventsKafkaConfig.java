package com.gerenciador.eventos.shared.events;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Política de erro dos listeners das projeções (só com {@code events.consumer.enabled=true}). O Spring Boot aplica
 * este {@code CommonErrorHandler} ao container factory padrão. O DLT é só para o que reentregar não conserta:
 * <ul>
 *   <li><b>erro transitório</b> (qualquer outro: {@code SQLException}, conexão caída, pool esgotado): retry com
 *       backoff exponencial (começa em {@code events.consumer.retry.interval-ms}, teto de 30 s) e SEM LIMITE, sem
 *       DLT, sem avançar o offset. Mandar para o DLT depois de poucos segundos perderia, por exemplo, o último evento
 *       de um stream (nenhum evento seguinte o preencheria como "buraco");</li>
 *   <li><b>buraco irrecuperável</b> ({@link EventGapException}): retry limitado ({@code max-attempts}, intervalo fixo)
 *       e depois DLT;</li>
 *   <li><b>payload inválido</b> ({@link InvalidEventMessageException}): DLT direto, sem retry.</li>
 * </ul>
 * O {@code RetryListener} loga em ERROR a partir da {@code events.consumer.retry.error-after-attempts}-ésima tentativa
 * (default 10) de uma mesma mensagem, para o retry sem limite não esconder um consumer travado.
 * O DLT é {@code <tópico>.DLT}, com partição escolhida pela chave (não precisa ter as mesmas partições do tópico).
 */
@Configuration
@ConditionalOnProperty(name = "events.consumer.enabled", havingValue = "true")
public class EventsKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(EventsKafkaConfig.class);
    private static final long TETO_TRANSITORIO_MS = 30_000;

    @Bean
    public DefaultErrorHandler eventsErrorHandler(KafkaOperations<Object, Object> template,
            @Value("${events.consumer.retry.interval-ms:1000}") long intervalMs,
            @Value("${events.consumer.retry.max-attempts:5}") long retries,
            @Value("${events.consumer.retry.error-after-attempts:10}") int erroAPartirDe) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (rec, ex) -> new TopicPartition(rec.topic() + ".DLT", -1));
        // padrão (BackOff do construtor) = transitório: exponencial e sem limite (maxElapsedTime default = infinito)
        ExponentialBackOff transitorio = new ExponentialBackOff(intervalMs, 2.0);
        transitorio.setMaxInterval(TETO_TRANSITORIO_MS);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, transitorio);
        handler.setBackOffFunction((rec, ex) -> temCausa(ex, EventGapException.class)
                ? (BackOff) new FixedBackOff(intervalMs, retries) : null);
        handler.addNotRetryableExceptions(InvalidEventMessageException.class);
        // O retry transitório não tem limite nem DLT: sem isto um consumer travado só apareceria como lag crescendo.
        // A partir da N-ésima tentativa falha, cada nova tentativa loga em ERROR (a cada 30 s no teto do backoff).
        handler.setRetryListeners(logarErroAPartirDe(erroAPartirDe));
        return handler;
    }

    /** Loga em ERROR cada tentativa falha de uma mesma mensagem a partir da {@code n}-ésima (a 1ª é a entrega original). */
    static RetryListener logarErroAPartirDe(int n) {
        return (rec, ex, tentativa) -> {
            if (tentativa >= n) {
                log.error("[EventsConsumer] {}-{}@{} falhou na tentativa {} (retry segue, sem DLT): {}",
                        rec.topic(), rec.partition(), rec.offset(), tentativa, ex.toString());
            }
        };
    }

    private static boolean temCausa(Throwable ex, Class<? extends Throwable> tipo) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (tipo.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
