package com.gerenciador.eventos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.identity.EmailVerificationService;
import com.gerenciador.eventos.identity.IdentityNotifications;
import com.gerenciador.eventos.identity.UserService;
import com.gerenciador.eventos.identityclient.api.LoginResult;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.NotificationEvents;
import com.gerenciador.eventos.shared.events.OutboxRelay;

/**
 * Issue #32: o monólito NÃO envia e-mail; {@code identity} só appenda um evento no stream
 * {@code identity.notification} (event_store + event_outbox, na MESMA transação do cadastro/código). Quem envia é o
 * serviço {@code notification}, que nem existe neste processo: cadastro e login funcionam sem ele, e o evento fica no
 * outbox/tópico até ele voltar. O {@code @Scheduled} do relay fica de 1h; o teste chama {@code drain()} na mão.
 */
@SpringBootTest(properties = {"events.relay.enabled=true", "events.relay.interval-ms=3600000"})
@Import(GlobalDbTruncator.class)
public class IdentityNotificationEventsTest {

    @Autowired private EmailVerificationService verificacao;
    @Autowired private UserService userService;
    @Autowired private DatabaseConnection db;
    @Autowired private ObjectMapper json;
    @Autowired private OutboxRelay relay;
    @MockitoSpyBean private IdentityNotifications notificacoes;
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrap;

    private final String rodada = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void limpar() throws Exception {
        EventsTestSupport.limpar(db);
        try (Connection c = db.getConnection(); var st = c.createStatement()) {
            st.execute("DELETE FROM public.pending_verifications");
        }
    }

    private String email(String tag) {
        return tag + "-" + rodada + "@example.com";
    }

    private String fone(String tag) {
        return "11" + String.format("%09d", Math.abs((rodada + tag).hashCode()) % 1_000_000_000);
    }

    private void pedirCadastro(String email) throws Exception {
        verificacao.requestEmailVerification(email, fone(email), "Nome " + email,
                "senha123", "1990-01-01");
    }

    /** Eventos do stream de notificação no event_store, em ordem de posição. */
    private List<JsonNode> eventosDeNotificacao() throws Exception {
        String sql = "SELECT event_type, version, payload::text FROM public.event_store WHERE stream_type = ? "
                + "ORDER BY global_position";
        List<JsonNode> lista = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, NotificationEvents.STREAM_TYPE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    var no = json.createObjectNode().put("eventType", rs.getString(1)).put("version", rs.getInt(2));
                    no.set("payload", json.readTree(rs.getString(3)));
                    lista.add(no);
                }
            }
        }
        return lista;
    }

    private int contar(String sql) throws Exception {
        try (Connection c = db.getConnection(); var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void pedidoDeCadastroAppendaVerificationCodeRequestedComOutboxNaMesmaTransacao() throws Exception {
        String email = email("cad");
        pedirCadastro(email);

        List<JsonNode> eventos = eventosDeNotificacao();
        assertEquals(1, eventos.size());
        JsonNode e = eventos.get(0);
        assertEquals(NotificationEvents.VERIFICATION_CODE_REQUESTED, e.get("eventType").asText());
        assertEquals(1, e.get("version").asInt(), "cada notificação é um stream de uma versão só");
        JsonNode p = e.get("payload");
        assertEquals(email, p.get(NotificationEvents.EMAIL).asText());
        assertEquals("Nome " + email, p.get(NotificationEvents.USER_NAME).asText());
        assertEquals(5, p.get(NotificationEvents.EXPIRATION_MINUTES).asInt());
        Instant expira = Instant.parse(p.get(NotificationEvents.EXPIRES_AT).asText());
        assertTrue(expira.isAfter(Instant.now().plusSeconds(4 * 60)) && expira.isBefore(Instant.now().plusSeconds(5 * 60 + 5)),
                "expiresAt = agora + a validade do cadastro (5 min): " + expira);
        String codigo = p.get(NotificationEvents.CODE).asText();
        assertEquals(6, codigo.length());
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT verification_code FROM public.pending_verifications WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(codigo, rs.getString(1), "o evento leva o mesmo código gravado em pending_verifications");
            }
        }
        assertEquals(1, contar("SELECT count(*) FROM public.event_outbox"), "outbox na mesma transação do append");
    }

    @Test
    void reenvioDoCodigoAppendaOutroEventoComOCodigoNovo() throws Exception {
        String email = email("reenvio");
        pedirCadastro(email);
        verificacao.resendVerificationCode(email);

        List<JsonNode> eventos = eventosDeNotificacao();
        assertEquals(2, eventos.size());
        assertEquals(NotificationEvents.VERIFICATION_CODE_REQUESTED, eventos.get(1).get("eventType").asText());
        String novo = eventos.get(1).get("payload").get(NotificationEvents.CODE).asText();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT verification_code FROM public.pending_verifications WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(novo, rs.getString(1));
                assertFalse(rs.next(), "o reenvio troca a linha pendente, não duplica");
            }
        }
    }

    @Test
    void pedidoDeMfaAppendaPasswordResetMfaRequestedComOCodigoGravadoNoUsuario() throws Exception {
        User criado = userService.createNewUser("Mfa " + rodada, email("mfa"), "senha123", fone("mfa"));
        userService.requestPasswordResetMfa(criado.getId());

        List<JsonNode> eventos = eventosDeNotificacao();
        assertEquals(1, eventos.size());
        assertEquals(NotificationEvents.PASSWORD_RESET_MFA_REQUESTED, eventos.get(0).get("eventType").asText());
        JsonNode p = eventos.get(0).get("payload");
        assertEquals(criado.getEmail(), p.get(NotificationEvents.EMAIL).asText());
        assertEquals(criado.getName(), p.get(NotificationEvents.USER_NAME).asText());
        assertEquals(NotificationEvents.MFA_VALIDADE.toMinutes(), p.get(NotificationEvents.EXPIRATION_MINUTES).asLong());
        assertTrue(Instant.parse(p.get(NotificationEvents.EXPIRES_AT).asText())
                .isAfter(Instant.now().plus(NotificationEvents.MFA_VALIDADE).minusSeconds(10)),
                "expiresAt do MFA = agora + a validade do MFA");
        String codigo = p.get(NotificationEvents.CODE).asText();
        assertTrue(userService.verifyPasswordResetMfaCode(criado.getId(), codigo),
                "o código do evento é o gravado em users.password_reset_mfa_code");
        assertEquals(1, contar("SELECT count(*) FROM public.event_outbox"));
    }

    @Test
    void falhaNoAppendDesfazOCadastroPendenteNaMesmaTransacao() throws Exception {
        doThrow(new SQLException("append falhou (simulado)")).when(notificacoes)
                .verificationCodeRequested(any(), anyString(), anyString(), anyString(), anyInt());
        String email = email("atomico");

        assertThrows(SQLException.class, () -> pedirCadastro(email));

        assertEquals(0, contar("SELECT count(*) FROM public.pending_verifications WHERE email = '" + email + "'"),
                "sem evento, sem linha pendente: tudo na mesma transação");
        assertEquals(0, contar("SELECT count(*) FROM public.event_store"));
    }

    @Test
    void cadastroELoginFuncionamSemOServicoNotificationEOEventoFicaNoTopicoAteEleVoltar() throws Exception {
        String email = email("completo");
        pedirCadastro(email);
        String codigo = eventosDeNotificacao().get(0).get("payload").get(NotificationEvents.CODE).asText();

        // nenhum consumer do notification neste processo: nada além do monólito
        LoginResult cadastro = verificacao.verifyEmailAndCreateUser(email, codigo);
        User criado = cadastro.user();
        assertNotNull(criado.getId());
        assertNotNull(cadastro.token(), "o cadastro devolve o token do login automático");
        LoginResult login = userService.login(email, "senha123");
        assertNotNull(login, "login funciona com o notification fora do ar");
        assertNotNull(login.token());

        assertEquals(1, contar("SELECT count(*) FROM public.event_outbox"), "o evento espera no outbox");
        assertEquals(1, relay.drain());
        assertEquals(0, contar("SELECT count(*) FROM public.event_outbox"));
        var noTopico = EventsTestSupport.lerTopico(bootstrap, NotificationEvents.STREAM_TYPE, 1, Duration.ofSeconds(20),
                r -> r.value().contains(email));
        assertEquals(1, noTopico.size(), "o evento ficou no tópico, esperando o notification");
        assertTrue(noTopico.get(0).value().contains("\"schemaVersion\":1"));
    }
}
