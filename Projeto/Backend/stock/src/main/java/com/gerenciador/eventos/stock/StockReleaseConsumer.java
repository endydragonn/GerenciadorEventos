package com.gerenciador.eventos.stock;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventEnvelope;

/**
 * Devolução da vaga ao cancelar ou expirar (issue #47, D4 = por evento): consome {@code ReservationCancelled} e
 * {@code ReservationExpired} do tópico {@code reservation.wallet} (que já são appendados na transação do wallet e levam o
 * {@code holdId}) e libera o hold. Sem escrita nem salto novo no wallet, sem devolução perdida (at-least-once). A vaga volta com o lag
 * do consumer: vende-se a menos por um tempo, nunca a mais. Com o stock fora, o wallet segue respondendo e as devoluções esperam no tópico.
 *
 * <p><b>Idempotência</b> pelo {@code UPDATE ... WHERE status = 'held'} por {@code holdId}, sem {@code IdempotentConsumer}: reentrega e
 * rebalance são no-op, e o preenchimento de buraco leria o {@code event_store} de outro schema. Por lote, UMA transação: primeiro os
 * holds, depois UM {@code UPDATE eventcap} por evento (em ordem de {@code event_id}) como últimos comandos, para o lote não segurar a
 * linha quente enquanto atualiza os holds.</p>
 *
 * <p>Ignora (log DEBUG) os outros tipos de evento e os {@code Cancelled}/{@code Expired} v1 sem {@code holdId} (anteriores à migração).
 * Mensagem que não é um envelope JSON válido, ou com {@code holdId} que não é UUID, é descartada com WARN: um NPE ali seria tratado como
 * transitório (retry sem limite) e travaria a partição por causa de uma mensagem que reentregar não conserta. Falha de banco é
 * transitória e propaga, o lote inteiro é reentregue.</p>
 *
 * <p><b>Group e tópico configuráveis</b> ({@code stock.release-consumer.group}/{@code .topic}; os testes usam os seus). {@code
 * auto.offset.reset=earliest} fixo: com {@code latest}, um cancelamento publicado antes da 1ª atribuição do group seria pulado e a vaga
 * ficaria presa. Só liga com {@code events.consumer.enabled} (política de erro/DLT da {@code shared}) e
 * {@code stock.release-consumer.enabled}.</p>
 */
@Component
@ConditionalOnProperty(name = {"events.consumer.enabled", "stock.release-consumer.enabled"}, havingValue = "true")
public class StockReleaseConsumer {

    public static final String LISTENER_ID = "stock-release";
    static final String CANCELLED = "ReservationCancelled";
    static final String EXPIRED = "ReservationExpired";

    private static final Logger log = LoggerFactory.getLogger(StockReleaseConsumer.class);

    private final DatabaseConnection databaseConnection;
    private final EventCapRepository eventCapRepository;
    private final HoldRepository holdRepository;
    private final ObjectMapper json;

    public StockReleaseConsumer(DatabaseConnection databaseConnection, EventCapRepository eventCapRepository,
            HoldRepository holdRepository, ObjectMapper json) {
        this.databaseConnection = databaseConnection;
        this.eventCapRepository = eventCapRepository;
        this.holdRepository = holdRepository;
        this.json = json;
    }

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, batch = "true",
            topics = "${stock.release-consumer.topic:reservation.wallet}",
            groupId = "${stock.release-consumer.group:stock}",
            properties = {"auto.offset.reset=earliest", "max.poll.records=${events.consumer.batch-size:500}"})
    void ouvir(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        liberar(records.stream().map(ConsumerRecord::value).toList());
        ack.acknowledge();
    }

    /** @return quantos holds passaram de {@code held} para {@code released} neste lote */
    int liberar(List<String> mensagens) {
        Set<UUID> holds = new HashSet<>();
        for (String m : mensagens) {
            UUID holdId = holdIdDe(m);
            if (holdId != null) {
                holds.add(holdId);
            }
        }
        if (holds.isEmpty()) {
            return 0;
        }
        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<HoldRepository.Liberado> liberados = holdRepository.liberarLote(conn, holds);
                Map<Long, Integer> devolver = new TreeMap<>();
                for (HoldRepository.Liberado l : liberados) {
                    if (l.decremented()) {
                        devolver.merge(l.eventId(), 1, Integer::sum);
                    }
                }
                for (Map.Entry<Long, Integer> e : devolver.entrySet()) {
                    eventCapRepository.devolver(conn, e.getKey(), e.getValue());
                }
                conn.commit();
                return liberados.size();
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new RuntimeException("Erro ao liberar holds do lote: " + ex.getMessage(), ex);
        }
    }

    /** @return o {@code holdId} de um Cancelled/Expired, ou {@code null} se a mensagem não deve liberar nada */
    private UUID holdIdDe(String mensagem) {
        EventEnvelope e;
        try {
            e = mensagem == null ? null : json.readValue(mensagem, EventEnvelope.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.warn("[StockRelease] mensagem descartada: não é um envelope de evento válido ({})", ex.getOriginalMessage());
            return null;
        }
        if (e == null || (!CANCELLED.equals(e.eventType()) && !EXPIRED.equals(e.eventType()))) {
            log.debug("[StockRelease] ignorada (tipo {})", e == null ? null : e.eventType());
            return null;
        }
        JsonNode id = e.payload() == null ? null : e.payload().get("holdId");
        if (id == null || id.isNull() || id.asText().isBlank()) {
            log.debug("[StockRelease] {} {} sem holdId (v1, anterior à migração): ignorada", e.eventType(), e.streamId());
            return null;
        }
        try {
            return UUID.fromString(id.asText());
        } catch (IllegalArgumentException ex) {
            log.warn("[StockRelease] {} {} descartada: holdId '{}' não é UUID", e.eventType(), e.streamId(), id.asText());
            return null;
        }
    }
}
