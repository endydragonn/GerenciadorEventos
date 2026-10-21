package com.gerenciador.eventos.TestSupport;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import com.gerenciador.eventos.shared.DatabaseConnection;

/** Helpers dos testes do backbone de eventos no serviço reservation (cópia enxuta do TestSupport do monólito, issue #27/#33). */
public final class EventsTestSupport {

    private EventsTestSupport() {
    }

    /**
     * Esvazia as tabelas de event sourcing e a projeção de disponibilidade do serviço reservation (RESTART IDENTITY:
     * global_position previsível).
     */
    public static void limpar(DatabaseConnection db) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE TABLE reservation.event_store, reservation.event_outbox, reservation.event_snapshot, "
                    + "reservation.projection_checkpoint, reservation.event_availability RESTART IDENTITY");
        }
    }

    /** Espera a condição ficar verdadeira, com timeout (nunca dorme "no escuro" sem limite). */
    public static void aguardar(Duration timeout, BooleanSupplier condicao, String descricao) throws Exception {
        long fim = System.nanoTime() + timeout.toNanos();
        while (!condicao.getAsBoolean()) {
            if (System.nanoTime() > fim) {
                throw new AssertionError("Timeout (" + timeout + ") esperando: " + descricao);
            }
            Thread.sleep(50);
        }
    }

    /**
     * Lê o tópico do início (sem consumer group: {@code assign} + {@code seekToBeginning}) até juntar
     * {@code esperadas} mensagens que passam no filtro, ou estourar o timeout.
     */
    public static List<ConsumerRecord<String, String>> lerTopico(String bootstrap, String topico, int esperadas,
            Duration timeout, Predicate<ConsumerRecord<String, String>> filtro) throws Exception {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("key.deserializer", StringDeserializer.class.getName());
        p.put("value.deserializer", StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> achadas = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            long fim = System.nanoTime() + timeout.toNanos();
            List<TopicPartition> partes = new ArrayList<>();
            while (partes.isEmpty() && System.nanoTime() < fim) {
                List<PartitionInfo> infos = consumer.partitionsFor(topico, Duration.ofSeconds(2));
                if (infos != null) {
                    infos.forEach(i -> partes.add(new TopicPartition(i.topic(), i.partition())));
                }
            }
            consumer.assign(partes);
            consumer.seekToBeginning(partes);
            while (achadas.size() < esperadas && System.nanoTime() < fim) {
                ConsumerRecords<String, String> lote = consumer.poll(Duration.ofMillis(300));
                for (ConsumerRecord<String, String> r : lote) {
                    if (filtro.test(r)) {
                        achadas.add(r);
                    }
                }
            }
        }
        return achadas;
    }
}
