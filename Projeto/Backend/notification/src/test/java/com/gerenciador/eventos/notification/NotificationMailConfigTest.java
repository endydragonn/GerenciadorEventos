package com.gerenciador.eventos.notification;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * SMTP do serviço notification parametrizável por ambiente (issue #37): sem variável, os valores de produção (Gmail,
 * 587, auth e STARTTLS obrigatório); com {@code MAIL_HOST}/{@code MAIL_PORT}/{@code MAIL_SMTP_AUTH}/{@code MAIL_STARTTLS}
 * o {@code JavaMailSender} aponta para outro servidor (a suíte e2e usa um captador de e-mail). O default é lido do
 * {@code application.properties} cru, resolvido sem o ambiente do processo (o {@code .env} do {@code run-tests.sh} pode
 * definir as variáveis); o override sobe o contexto de verdade, sem consumer (não entra em group nenhum do Redpanda).
 */
@SpringBootTest(classes = NotificationApplication.class, properties = {
        "MAIL_HOST=captador", "MAIL_PORT=1025", "MAIL_SMTP_AUTH=false", "MAIL_STARTTLS=false",
        "spring.kafka.listener.auto-startup=false"})
public class NotificationMailConfigTest {

    @Autowired private JavaMailSenderImpl sender;

    @Test
    void semVariaveisOsDefaultsSaoOsDoGmail() throws Exception {
        Properties p = new Properties();
        try (InputStream in = NotificationMailConfigTest.class.getResourceAsStream("/application.properties")) {
            p.load(in);
        }
        MutablePropertySources fontes = new MutablePropertySources();
        fontes.addFirst(new PropertiesPropertySource("app", p));
        PropertySourcesPropertyResolver r = new PropertySourcesPropertyResolver(fontes);
        r.setIgnoreUnresolvableNestedPlaceholders(true); // MAIL_USERNAME/MAIL_PASSWORD não têm default (vêm do .env)
        assertEquals("smtp.gmail.com", r.getProperty("spring.mail.host"));
        assertEquals("587", r.getProperty("spring.mail.port"));
        assertEquals("true", r.getProperty("spring.mail.properties.mail.smtp.auth"));
        assertEquals("true", r.getProperty("spring.mail.properties.mail.smtp.starttls.enable"));
        assertEquals("true", r.getProperty("spring.mail.properties.mail.smtp.starttls.required"));
    }

    @Test
    void overrideChegaAoJavaMailSender() {
        assertEquals("captador", sender.getHost());
        assertEquals(1025, sender.getPort());
        Properties s = sender.getJavaMailProperties();
        assertEquals("false", s.getProperty("mail.smtp.auth"));
        assertEquals("false", s.getProperty("mail.smtp.starttls.enable"));
        assertEquals("false", s.getProperty("mail.smtp.starttls.required"));
    }
}
