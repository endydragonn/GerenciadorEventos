package com.gerenciador.eventos.TestSupport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;

/**
 * Infra dos testes de rebalance (issue #112): tópico de TESTE com várias partições criado na mão (o auto-create do Redpanda dá 1), containers
 * Spring Kafka no MESMO group (duas "réplicas" do serviço), conferência de offsets comitados e leitura do {@code .DLT}. Só mexe em tópicos que
 * o próprio teste criou (prefixo {@code t112-}); nunca em tópico real do broker.
 */
public final class KafkaDeTeste {

    private KafkaDeTeste() {
    }

    private static AdminClient admin(String bootstrap) {
        return AdminClient.create(Map.of("bootstrap.servers", bootstrap, "request.timeout.ms", "10000"));
    }

    public static void criarTopico(String bootstrap, String nome, int particoes) throws Exception {
        if (!nome.startsWith("t112-")) {
            throw new IllegalArgumentException("só tópicos de teste (t112-*): " + nome);
        }
        try (AdminClient a = admin(bootstrap)) {
            a.createTopics(List.of(new NewTopic(nome, particoes, (short) 1))).all().get();
        }
    }

    /**
     * Apaga só tópicos {@code t112-*} e os consumer groups {@code t112-*} (os containers do teste já foram parados; group com membro vivo não
     * é apagado). Erro ao apagar não derruba o teste.
     */
    public static void apagarTopicos(String bootstrap, String... nomes) {
        try (AdminClient a = admin(bootstrap)) {
            Set<String> existentes = a.listTopics().names().get();
            List<String> alvo = new ArrayList<>();
            for (String n : nomes) {
                if (n.startsWith("t112-") && existentes.contains(n)) {
                    alvo.add(n);
                }
            }
            if (!alvo.isEmpty()) {
                a.deleteTopics(alvo).all().get();
            }
            List<String> grupos = a.listConsumerGroups().all().get().stream()
                    .map(g -> g.groupId()).filter(g -> g.startsWith("t112-")).collect(Collectors.toList());
            if (!grupos.isEmpty()) {
                a.deleteConsumerGroups(grupos).all().get();
            }
        } catch (Exception ignorado) {
            // limpeza do broker de teste: o prefixo t112- deixa a sobra fácil de achar
        }
    }

    public static KafkaMessageListenerContainer<String, String> container(String bootstrap, String grupo, String topico,
            Map<String, Object> extras, ContainerProperties.AckMode ack, Object listener, CommonErrorHandler handler) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, grupo);
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.putAll(extras);
        ContainerProperties props = new ContainerProperties(topico);
        props.setAckMode(ack);
        props.setMessageListener(listener);
        KafkaMessageListenerContainer<String, String> c = new KafkaMessageListenerContainer<>(
                new DefaultKafkaConsumerFactory<>(cfg), props);
        c.setCommonErrorHandler(handler);
        return c;
    }

    /** Todos os offsets comitados do group chegaram ao fim de TODAS as partições do tópico (nenhuma partição parada). */
    public static boolean grupoEmDia(String bootstrap, String grupo, String topico) throws Exception {
        try (AdminClient a = admin(bootstrap)) {
            int n = a.describeTopics(List.of(topico)).allTopicNames().get().get(topico).partitions().size();
            Map<TopicPartition, OffsetSpec> fim = new HashMap<>();
            for (int p = 0; p < n; p++) {
                fim.put(new TopicPartition(topico, p), OffsetSpec.latest());
            }
            Map<TopicPartition, OffsetAndMetadata> comitado = a.listConsumerGroupOffsets(grupo)
                    .partitionsToOffsetAndMetadata().get();
            for (Map.Entry<TopicPartition, OffsetSpec> e : fim.entrySet()) {
                long end = a.listOffsets(Map.of(e.getKey(), e.getValue())).all().get().get(e.getKey()).offset();
                OffsetAndMetadata c = comitado.get(e.getKey());
                if ((c == null ? 0 : c.offset()) < end) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Valores do tópico lidos do início, sem group. Tópico que não existe = lista vazia (e NÃO é criado). */
    public static List<String> lerValores(String bootstrap, String topico) throws Exception {
        try (AdminClient a = admin(bootstrap)) {
            if (!a.listTopics().names().get().contains(topico)) {
                return List.of();
            }
        }
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("key.deserializer", StringDeserializer.class.getName());
        p.put("value.deserializer", StringDeserializer.class.getName());
        p.put("allow.auto.create.topics", "false");
        List<String> valores = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            List<TopicPartition> partes = consumer.partitionsFor(topico, Duration.ofSeconds(5)).stream()
                    .map(i -> new TopicPartition(i.topic(), i.partition())).collect(Collectors.toList());
            consumer.assign(partes);
            consumer.seekToBeginning(partes);
            Map<TopicPartition, Long> fim = consumer.endOffsets(partes);
            long limite = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (partes.stream().anyMatch(tp -> consumer.position(tp) < fim.get(tp)) && System.nanoTime() < limite) {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> valores.add(r.value()));
            }
        }
        return valores;
    }
}
