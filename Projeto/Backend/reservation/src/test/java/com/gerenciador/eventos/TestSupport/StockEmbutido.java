package com.gerenciador.eventos.TestSupport;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.stock.StockApplication;
import com.gerenciador.eventos.stock.StockReleaseConsumer;
import com.gerenciador.eventos.stockclient.StockClient;

/**
 * O serviço stock REAL dentro da JVM de teste do reservation (issue #47): a compensação da saga é provada contra ele, não contra um fake.
 * Sobe UMA vez por JVM (porta aleatória, o mesmo Postgres de teste, schema {@code stock}) e fica atrás de um {@link StockProxy}, que é a URL
 * que os contextos do reservation recebem em {@code stock.service.url} (ver {@code ReservationTestSupport}).
 *
 * <p><b>Configuração.</b> Os dois módulos têm {@code application.properties}; o do reservation vence no classpath. Por isso o stock sobe com
 * {@code --spring.config.name} apontando para um arquivo que não existe (nenhum {@code application.properties} é carregado) e recebe como
 * propriedades-padrão as do próprio arquivo do módulo stock, achado pelo conteúdo ({@code spring.application.name=stock}) entre os
 * {@code application.properties} do classpath (diretório {@code target/classes} no reactor; o jar repackaged do Spring Boot não serve
 * como dependência de teste, e o {@code mvn test} do reactor não o gera). Env vars (banco, token, broker) valem como em qualquer serviço.
 * O listener Kafka do consumer de devolução NÃO sobe ({@code auto-startup=false}): a devolução é exercida chamando o MESMO caminho do
 * consumer ({@link #liberarDevolucoes}), sem depender do tópico real nem de group.</p>
 */
public final class StockEmbutido {

    private static StockEmbutido instancia;

    private final ConfigurableApplicationContext ctx;
    private final StockProxy proxy;
    private final StockClient direto;
    private final StockReleaseConsumer consumer;
    private final DatabaseConnection db;
    private final ObjectMapper json;

    private StockEmbutido() {
        Map<String, Object> props = new HashMap<>(propriedadesDoModuloStock());
        props.put("server.port", "0");
        props.put("spring.datasource.hikari.maximum-pool-size", "40");
        props.put("spring.datasource.hikari.minimum-idle", "1");
        props.put("events.consumer.enabled", "true");
        props.put("stock.release-consumer.enabled", "true");
        props.put("stock.catalog-reconciliation.enabled", "false");
        props.put("spring.kafka.listener.auto-startup", "false");
        props.put("spring.main.banner-mode", "off");
        this.ctx = new SpringApplicationBuilder(StockApplication.class).properties(props)
                .run("--spring.config.name=stock-embutido-sem-arquivo");
        Environment env = ctx.getEnvironment();
        int porta = Integer.parseInt(env.getProperty("local.server.port"));
        this.proxy = new StockProxy(porta);
        this.direto = new StockClient("http://localhost:" + porta, env.getProperty("internal.api.token"), 20,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20));
        this.consumer = ctx.getBean(StockReleaseConsumer.class);
        this.db = ctx.getBean(DatabaseConnection.class);
        this.json = ctx.getBean(ObjectMapper.class);
    }

    public static synchronized StockEmbutido get() {
        if (instancia == null) {
            instancia = new StockEmbutido();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> instancia.ctx.close(), "stock-embutido-close"));
        }
        return instancia;
    }

    /** A URL que o reservation usa: a do proxy. */
    public static String urlDoProxy() {
        return get().proxy.url();
    }

    public StockProxy proxy() {
        return proxy;
    }

    /** Cliente DIRETO ao stock (sem proxy): cria e lê capacidade nos testes sem sofrer as falhas injetadas. */
    public StockClient direto() {
        return direto;
    }

    /**
     * O mesmo caminho do {@code StockReleaseConsumer}: lê os {@code ReservationCancelled}/{@code ReservationExpired} do
     * {@code reservation.event_store} e os entrega ao consumer real, que libera os holds (idempotente: o que já foi liberado é no-op).
     * Faz, de uma vez, o que o listener faria com o tópico em dia. Eventos v1 (sem {@code holdId}) o consumer ignora.
     * @return quantos holds passaram de {@code held} para {@code released}
     */
    public int liberarDevolucoes() throws Exception {
        List<String> mensagens = new ArrayList<>();
        String sql = "SELECT event_type, stream_id, payload::text FROM reservation.event_store "
                + "WHERE stream_type = 'reservation.wallet' AND event_type IN ('ReservationCancelled', 'ReservationExpired') "
                + "ORDER BY global_position";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ObjectNode envelope = json.createObjectNode();
                envelope.put("eventType", rs.getString(1));
                envelope.put("streamId", rs.getString(2));
                envelope.set("payload", json.readTree(rs.getString(3)));
                mensagens.add(json.writeValueAsString(envelope));
            }
        }
        return consumer.liberar(mensagens);
    }

    private static Map<String, Object> propriedadesDoModuloStock() {
        try {
            Enumeration<URL> urls = StockEmbutido.class.getClassLoader().getResources("application.properties");
            for (URL u : Collections.list(urls)) {
                Properties p = new Properties();
                try (InputStream in = u.openStream()) {
                    p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                }
                if ("stock".equals(p.getProperty("spring.application.name"))) {
                    Map<String, Object> m = new HashMap<>();
                    p.forEach((k, v) -> m.put(String.valueOf(k), v));
                    return m;
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("não li o application.properties do módulo stock: " + ex.getMessage(), ex);
        }
        throw new IllegalStateException("application.properties do módulo stock não está no classpath de teste "
                + "(a dependência de teste resolveu para o jar repackaged do Spring Boot?)");
    }
}
