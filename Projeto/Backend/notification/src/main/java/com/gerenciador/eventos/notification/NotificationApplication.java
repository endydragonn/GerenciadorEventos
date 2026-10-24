package com.gerenciador.eventos.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventsKafkaConfig;

/**
 * Serviço {@code notification} (issue #32, Fase 7): consumer do tópico {@code identity.notification} que envia
 * e-mail por SMTP. Processo e imagem próprios; sem HTTP. O component scan cobre só este pacote: da lib {@code shared}
 * entram só o que o serviço usa (conexão com o banco e a política de erro/DLT do consumer), por {@code @Import} — o
 * resto da infra de event sourcing (relay, event_store...) é do monólito e não faz sentido aqui.
 */
@SpringBootApplication
@Import({DatabaseConnection.class, EventsKafkaConfig.class})
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
