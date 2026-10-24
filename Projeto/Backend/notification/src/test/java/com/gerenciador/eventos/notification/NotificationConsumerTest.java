package com.gerenciador.eventos.notification;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import jakarta.mail.MessagingException;
import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventEnvelope;
import com.gerenciador.eventos.shared.events.NotificationEvents;

/**
 * Serviço {@code notification} (issue #32) contra Postgres e Redpanda reais e um {@link JavaMailSender} fake (sem SMTP).
 * Publica no tópico {@code identity.notification} o mesmo envelope que o monólito publica pelo relay e confere o e-mail
 * montado, a idempotência da reentrega, o retry de falha de SMTP e o dead-letter de rejeição permanente.
 *
 * <p>O group do teste ({@code notification-test}) é próprio: o teste nunca entra no group {@code notification} do
 * serviço vivo. O e-mail que chega ANTES do contexto subir ({@link #publicadoComOServicoFora}) prova o "e-mail sai
 * quando ele volta": o evento fica no tópico até o consumer existir. Cada teste usa destinatários únicos e confere
 * por destinatário (o tópico guarda o que sobrou de execuções anteriores e dos testes do monólito).</p>
 */
@SpringBootTest(classes = NotificationApplication.class, properties = {
        "spring.kafka.consumer.group-id=notification-test",
        "events.consumer.retry.interval-ms=200", "events.consumer.retry.max-attempts=2"})
public class NotificationConsumerTest {

    private static final String BOOTSTRAP = System.getenv().getOrDefault("SPRING_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
    private static final String RODADA = UUID.randomUUID().toString().substring(0, 8);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String ANTES_DE_SUBIR = "antes-" + RODADA + "@example.com";
    private static final EventEnvelope EVENTO_ANTES = envelope(NotificationEvents.VERIFICATION_CODE_REQUESTED,
            ANTES_DE_SUBIR, "Maria", "111111", 5);

    @Autowired private KafkaTemplate<String, String> template;
    @Autowired private DatabaseConnection db;
    @MockitoBean private JavaMailSender mailSender;

    /** Roda ANTES de o contexto Spring (e portanto o consumer) existir: o serviço está "fora do ar". */
    @BeforeAll
    static void publicadoComOServicoFora() throws Exception {
        Properties p = new Properties();
        p.put("bootstrap.servers", BOOTSTRAP);
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            producer.send(new ProducerRecord<>(NotificationEvents.STREAM_TYPE, EVENTO_ANTES.streamId(),
                    JSON.writeValueAsString(EVENTO_ANTES))).get();
        }
    }

    /** Código que vence daqui a {@code minutos} (null = a validade do MFA). */
    private static EventEnvelope envelope(String tipo, String email, String nome, String codigo, Integer minutos) {
        int validade = minutos != null ? minutos : (int) NotificationEvents.MFA_VALIDADE.toMinutes();
        return envelope(tipo, email, nome, codigo, validade, Instant.now().plusSeconds(validade * 60L));
    }

    private static EventEnvelope envelope(String tipo, String email, String nome, String codigo, int minutos,
            Instant expiresAt) {
        var payload = JSON.createObjectNode().put(NotificationEvents.EMAIL, email)
                .put(NotificationEvents.USER_NAME, nome).put(NotificationEvents.CODE, codigo)
                .put(NotificationEvents.EXPIRATION_MINUTES, minutos)
                .put(NotificationEvents.EXPIRES_AT, expiresAt.toString());
        return new EventEnvelope(UUID.randomUUID(), NotificationEvents.STREAM_TYPE, UUID.randomUUID().toString(), 1, 0L,
                tipo, 1, Instant.now(), payload, JSON.createObjectNode());
    }

    private void publicar(EventEnvelope e) throws Exception {
        template.send(NotificationEvents.STREAM_TYPE, e.streamId(), JSON.writeValueAsString(e)).get();
    }

    private static ArgumentMatcher<SimpleMailMessage> para(String email) {
        return m -> m.getTo() != null && m.getTo().length == 1 && email.equals(m.getTo()[0]);
    }

    /** Espera o e-mail do destinatário sair e devolve o que foi enviado. */
    private SimpleMailMessage enviadoPara(String email) {
        verify(mailSender, timeout(30_000)).send(argThat(para(email)));
        return mockingDetails(mailSender).getInvocations().stream()
                .map(inv -> inv.getArguments()[0])
                .filter(arg -> arg instanceof SimpleMailMessage m && para(email).matches(m))
                .map(SimpleMailMessage.class::cast).findFirst().orElseThrow();
    }

    private int processados(UUID eventId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM notification.processed_event WHERE event_id = ?")) {
            ps.setObject(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void emailPublicadoComOServicoForaSaiQuandoOConsumerSobe() throws Exception {
        // O envio acontece logo que o contexto sobe, dentro de qualquer teste da classe (o mock é zerado a cada teste),
        // então a prova é o checkpoint: processed_event só ganha a linha no commit, depois do envio.
        long fim = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (processados(EVENTO_ANTES.eventId()) == 0 && System.nanoTime() < fim) {
            Thread.sleep(100);
        }
        assertEquals(1, processados(EVENTO_ANTES.eventId()));
    }

    @Test
    void codigoDeVerificacaoViraEmailComOCodigoEOPrazo() throws Exception {
        String email = "verif-" + RODADA + "@example.com";
        publicar(envelope(NotificationEvents.VERIFICATION_CODE_REQUESTED, email, "Joana", "482913", 5));

        SimpleMailMessage enviado = enviadoPara(email);
        assertEquals(EmailTemplates.getVerificationCodeSubject(), enviado.getSubject());
        assertTrue(enviado.getText().contains("482913"), "o texto leva o código");
        assertTrue(enviado.getText().contains("Joana"), "o texto leva o nome");
        assertTrue(enviado.getText().contains("5 minutos"), "o texto leva o prazo");
    }

    @Test
    void codigoMfaViraEmailDeResetDeSenha() throws Exception {
        String email = "mfa-" + RODADA + "@example.com";
        publicar(envelope(NotificationEvents.PASSWORD_RESET_MFA_REQUESTED, email, "Carlos", "Ab3dE9gH", null));

        SimpleMailMessage enviado = enviadoPara(email);
        assertEquals(EmailTemplates.getPasswordResetMfaSubject(), enviado.getSubject());
        assertTrue(enviado.getText().contains("Ab3dE9gH"));
        assertTrue(enviado.getText().contains(NotificationEvents.MFA_VALIDADE.toMinutes() + " minutos"), "o prazo vem do evento");
    }

    @Test
    void reentregaDoMesmoEventoNaoMandaDoisEmails() throws Exception {
        String email = "dup-" + RODADA + "@example.com";
        String sentinela = "dup-fim-" + RODADA + "@example.com";
        EventEnvelope e = envelope(NotificationEvents.VERIFICATION_CODE_REQUESTED, email, "Ana", "123456", 5);
        publicar(e);
        publicar(e);
        publicar(e);
        publicar(envelope(NotificationEvents.VERIFICATION_CODE_REQUESTED, sentinela, "Fim", "654321", 5));

        verify(mailSender, timeout(30_000)).send(argThat(para(sentinela)));
        verify(mailSender, times(1)).send(argThat(para(email)));
        assertEquals(1, processados(e.eventId()));
    }

    @Test
    void falhaTransitoriaDeSmtpReentregaAteEnviarSemDuplicar() throws Exception {
        String email = "smtp-" + RODADA + "@example.com";
        doThrow(new MailSendException("SMTP fora (simulado)", new MessagingException("connect timed out")))
                .doThrow(new MailSendException("SMTP fora (simulado)", new MessagingException("connect timed out")))
                .doNothing().when(mailSender).send(argThat(para(email)));
        EventEnvelope e = envelope(NotificationEvents.VERIFICATION_CODE_REQUESTED, email, "Rui", "777777", 5);
        publicar(e);

        verify(mailSender, timeout(30_000).times(3)).send(argThat(para(email)));
        Thread.sleep(500); // dá tempo de um envio a mais aparecer, se houvesse
        verify(mailSender, times(3)).send(argThat(para(email)));
        assertEquals(1, processados(e.eventId()), "o checkpoint só vale depois do envio que deu certo");
    }

    @Test
    void rejeicaoPermanenteDoSmtpVaiParaODeadLetterSemTravarOsProximos() throws Exception {
        String ruim = "ruim-" + RODADA + "@example.com";
        String bom = "bom-" + RODADA + "@example.com";
        Address[] invalidos = {new InternetAddress(ruim)};
        doThrow(new MailSendException("endereço rejeitado (simulado)",
                new SendFailedException("550 user unknown", null, new Address[0], new Address[0], invalidos)))
                .when(mailSender).send(argThat(para(ruim)));
        EventEnvelope e = envelope(NotificationEvents.VERIFICATION_CODE_REQUESTED, ruim, "Zé", "000000", 5);
        publicar(e);
        publicar(envelope(NotificationEvents.VERIFICATION_CODE_REQUESTED, bom, "Bia", "999999", 5));

        verify(mailSender, timeout(30_000)).send(argThat(para(bom)));
        verify(mailSender, times(1)).send(argThat(para(ruim))); // sem retry: reentregar não conserta
        assertEquals(0, processados(e.eventId()), "não foi enviado, então não vira checkpoint");
        assertTrue(noDeadLetter(e.eventId().toString(), Duration.ofSeconds(30)), "a mensagem ruim foi para o .DLT");
    }

    @Test
    void mensagemQueNaoEUmEnvelopeVaiParaODeadLetterSemTravar() throws Exception {
        String bom = "apos-lixo-" + RODADA + "@example.com";
        template.send(NotificationEvents.STREAM_TYPE, "lixo-" + RODADA, "isto não é json " + RODADA).get();
        publicar(envelope(NotificationEvents.VERIFICATION_CODE_REQUESTED, bom, "Lia", "101010", 5));

        verify(mailSender, timeout(30_000)).send(argThat(para(bom)));
        assertTrue(noDeadLetter("isto não é json " + RODADA, Duration.ofSeconds(30)));
    }

    @Test
    void codigoJaVencidoNaoEEnviadoEOProximoSegue() throws Exception {
        String vencido = "vencido-" + RODADA + "@example.com";
        String valido = "valido-" + RODADA + "@example.com";
        EventEnvelope e = envelope(NotificationEvents.VERIFICATION_CODE_REQUESTED, vencido, "Tarde", "424242", 5,
                Instant.now().minusSeconds(1));
        publicar(e);
        publicar(envelope(NotificationEvents.VERIFICATION_CODE_REQUESTED, valido, "Cedo", "535353", 5));

        verify(mailSender, timeout(30_000)).send(argThat(para(valido)));
        verify(mailSender, never()).send(argThat(para(vencido)));
        assertEquals(0, processados(e.eventId()), "descartado sem enviar: não vira checkpoint");
    }

    @Test
    void eventoSemExpiresAtEMalformadoEVaiParaODeadLetter() throws Exception {
        String email = "semprazo-" + RODADA + "@example.com";
        EventEnvelope e = envelope(NotificationEvents.VERIFICATION_CODE_REQUESTED, email, "Sem", "111222", 5);
        ((com.fasterxml.jackson.databind.node.ObjectNode) e.payload()).remove(NotificationEvents.EXPIRES_AT);
        publicar(e);

        assertTrue(noDeadLetter(e.eventId().toString(), Duration.ofSeconds(30)));
        verify(mailSender, never()).send(argThat(para(email)));
    }

    @Test
    void eventoDeTipoDesconhecidoNaoEnviaNada() throws Exception {
        String email = "tipo-" + RODADA + "@example.com";
        String sentinela = "tipo-fim-" + RODADA + "@example.com";
        publicar(envelope("TipoQueNaoExiste", email, "Nope", "1", null));
        publicar(envelope(NotificationEvents.VERIFICATION_CODE_REQUESTED, sentinela, "Fim", "202020", 5));

        verify(mailSender, timeout(30_000)).send(argThat(para(sentinela)));
        verify(mailSender, never()).send(argThat(para(email)));
    }

    /** Lê o {@code .DLT} do início (sem group) até achar uma mensagem cujo valor contém o trecho. */
    private boolean noDeadLetter(String trecho, Duration timeout) {
        Properties p = new Properties();
        p.put("bootstrap.servers", BOOTSTRAP);
        p.put("key.deserializer", StringDeserializer.class.getName());
        p.put("value.deserializer", StringDeserializer.class.getName());
        String dlt = NotificationEvents.STREAM_TYPE + ".DLT";
        long fim = System.nanoTime() + timeout.toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            while (System.nanoTime() < fim) {
                var infos = consumer.partitionsFor(dlt, Duration.ofSeconds(2));
                if (infos == null || infos.isEmpty()) {
                    continue;
                }
                List<TopicPartition> partes = infos.stream().map(i -> new TopicPartition(i.topic(), i.partition())).toList();
                consumer.assign(partes);
                consumer.seekToBeginning(partes);
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (r.value().contains(trecho)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
