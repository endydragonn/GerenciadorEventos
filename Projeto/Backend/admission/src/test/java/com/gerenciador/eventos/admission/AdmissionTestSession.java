package com.gerenciador.eventos.admission;

import java.io.IOException;
import java.util.UUID;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

/**
 * Prepara a JVM de teste do admission (issue #42), antes de qualquer contexto Spring (registrado em
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}): sobe o {@link FakeReservationServer} (URL em
 * {@code reservation.service.url}), sorteia o prefixo desta JVM para os nomes do RabbitMQ ({@code admission.test.prefix}; os testes
 * montam {@code admission.amqp.prefix=${admission.test.prefix}<sufixo>}, nunca as filas do serviço) e DESLIGA o
 * worker pool por padrão (só os testes do worker o ligam: contextos em cache com consumers vivos roubariam mensagens uns dos outros). Ao fim,
 * apaga do broker as filas e exchanges de todos os sufixos usados. As classes que usam o RabbitMQ são {@code @DirtiesContext}: o contexto
 * (e os consumers) fecha ao fim de cada uma; um consumer vivo REDECLARARIA no broker o que a limpeza apagasse.
 */
public class AdmissionTestSession implements LauncherSessionListener {

    /** Um sufixo por classe de teste que usa o RabbitMQ. */
    static final String[] SUFIXOS = {"geral-", "fila-", "cheia-", "worker-", "dlx-", "log-"};

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            System.setProperty("reservation.service.url", FakeReservationServer.iniciar().url());
        } catch (IOException ex) {
            throw new IllegalStateException("Não consegui subir o servidor falso do reservation", ex);
        }
        System.setProperty("admission.test.prefix", "t" + UUID.randomUUID().toString().substring(0, 8) + ".");
        // prefixo PADRÃO da JVM: um contexto sem prefixo próprio (ApiTest, ConfigTest...) nunca usaria as admission.* do serviço
        System.setProperty("admission.amqp.prefix", prefixo("geral-"));
        System.setProperty("admission.worker.enabled", "false");
    }

    private static String prefixo(String sufixo) {
        return System.getProperty("admission.test.prefix") + sufixo;
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        String base = System.getProperty("admission.test.prefix");
        if (base == null) {
            return;
        }
        CachingConnectionFactory fabrica = RabbitDeTeste.fabrica();
        try {
            RabbitAdmin admin = new RabbitAdmin(fabrica);
            for (String sufixo : SUFIXOS) {
                String prefixo = base + sufixo;
                admin.deleteQueue(prefixo + "admission.requests");
                admin.deleteQueue(prefixo + "admission.timed_out");
                admin.deleteExchange(prefixo + "admission");
                admin.deleteExchange(prefixo + "admission.dlx");
            }
        } finally {
            fabrica.destroy();
        }
    }
}
