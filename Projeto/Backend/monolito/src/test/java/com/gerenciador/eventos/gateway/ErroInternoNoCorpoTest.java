package com.gerenciador.eventos.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.gerenciador.eventos.catalogclient.api.EventApi;
import com.gerenciador.eventos.gateway.security.HostAuthorizationApi;
import com.gerenciador.eventos.identityclient.api.EmailVerificationApi;
import com.gerenciador.eventos.identityclient.api.IdentityUnavailableException;
import com.gerenciador.eventos.identityclient.api.RecaptchaApi;
import com.gerenciador.eventos.identityclient.api.UserApi;
import com.gerenciador.eventos.reservationclient.api.EventWallet;

/**
 * Issue #89: todo 500 do {@code gateway/*} devolve corpo genérico; a mensagem da exceção (aqui, um detalhe de SQL) só vai
 * para o log do servidor, com o stack trace. Os controllers sobem sem Spring/banco (MockMvc standalone) com as dependências
 * mockadas falhando, então o teste roda em qualquer {@code mvn test}.
 */
public class ErroInternoNoCorpoTest {

    private static final String DETALHE =
            "ERROR: duplicate key value violates unique constraint \"users_email_key\" (segredo-interno-89)";
    private static final String JSON_EVENTO = "{\"event_name\":\"E\",\"is_EAD\":true,\"event_date\":\"2030-01-01T10:00:00\","
            + "\"lot_quantity\":5,\"presenters\":[]}";

    private UserApi userApi;
    private EmailVerificationApi emailApi;
    private RecaptchaApi recaptchaApi;
    private EventApi eventApi;
    private HostAuthorizationApi hostApi;
    private EventWalletGateway walletGateway;
    private BffUserController userController;
    private MockMvc mvc;
    private ListAppender<ILoggingEvent> logs;
    private Logger root;

    @BeforeEach
    void montar() {
        userApi = mock(UserApi.class);
        emailApi = mock(EmailVerificationApi.class);
        recaptchaApi = mock(RecaptchaApi.class);
        eventApi = mock(EventApi.class);
        hostApi = mock(HostAuthorizationApi.class);
        walletGateway = mock(EventWalletGateway.class);
        when(hostApi.currentUserId()).thenReturn(5L);
        userController = new BffUserController(userApi, emailApi, recaptchaApi, hostApi);
        mvc = MockMvcBuilders.standaloneSetup(userController,
                new BffEventController(eventApi, hostApi, userApi, mock(CreatorNames.class)),
                new BffEventWalletController(walletGateway, hostApi)).setControllerAdvice(new IdentityIndisponivelHandler()).build();
        logs = new ListAppender<>();
        logs.start();
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
    }

    @AfterEach
    void limpar() {
        root.detachAppender(logs);
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------- usuário

    @Test
    void criarUsuario() throws Exception {
        when(userApi.createUser(any())).thenThrow(new RuntimeException(DETALHE));
        esperar500(post("/bff/users").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"));
    }

    @Test
    void atualizarUsuario() throws Exception {
        when(userApi.updateUser(any())).thenThrow(new RuntimeException(DETALHE));
        esperar500(put("/bff/users/1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"));
    }

    @Test
    void alterarSenha() throws Exception {
        when(userApi.changePassword(anyLong(), any(), any())).thenThrow(new RuntimeException(DETALHE));
        esperar500(put("/bff/users/1/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"oldPassword\":\"a\",\"newPassword\":\"b\"}"));
    }

    @Test
    void deletarUsuario() throws Exception {
        doThrow(new RuntimeException(DETALHE)).when(userApi).deactivateUser(anyLong());
        esperar500(delete("/bff/users/1"));
    }

    @Test
    void buscarUsuarioLogado() throws Exception {
        // /me resolve pelo uid do token (hostApi.currentUserId() = 5), não pelo e-mail (#36)
        when(userApi.findById(5L)).thenThrow(new RuntimeException(DETALHE));
        esperar500(get("/bff/users/me"));
    }

    @Test
    void solicitarVerificacaoDeEmail() throws Exception {
        doThrow(new SQLException(DETALHE)).when(emailApi).requestEmailVerification(any(), any(), any(), any(), any());
        esperar500(post("/bff/users/request-verification").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"A\",\"email\":\"a@example.com\",\"fone\":\"11999990000\",\"password\":\"senha123\","
                        + "\"birthdate\":\"2000-01-01\"}"));
    }

    @Test
    void verificarEmail() throws Exception {
        when(emailApi.verifyEmailAndCreateUser(any(), any())).thenThrow(new SQLException(DETALHE));
        esperar500(post("/bff/users/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.com\",\"verificationCode\":\"123456\"}"));
    }

    @Test
    void reenviarCodigo() throws Exception {
        doThrow(new SQLException(DETALHE)).when(emailApi).resendVerificationCode(any());
        esperar500(post("/bff/users/resend-verification-code").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.com\"}"));
    }

    @Test
    void requisitarMfa() throws Exception {
        // o catch (RuntimeException) vira 404; o 500 é o catch (Exception): só se chega com uma exceção checada
        when(userApi.requestPasswordResetMfa(anyLong())).thenAnswer(inv -> {
            throw new Exception(DETALHE);
        });
        esperar500(post("/bff/users/1/request-password-reset-mfa"));
    }

    @Test
    void validarMfa() throws Exception {
        when(userApi.verifyPasswordResetMfaCode(anyLong(), any())).thenThrow(new RuntimeException(DETALHE));
        esperar500(post("/bff/users/1/verify-password-reset-mfa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mfaCode\":\"123456\"}"));
    }

    @Test
    void alterarSenhaComMfa() throws Exception {
        doThrow(new RuntimeException(DETALHE)).when(userApi).changePasswordWithMfa(anyLong(), any(), any());
        esperar500(put("/bff/users/1/password-with-mfa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mfaCode\":\"123456\",\"newPassword\":\"nova123\"}"));
    }

    @Test
    void recaptchaMantemOFormatoDoMapComErroGenerico() {
        when(recaptchaApi.verify(any(), any())).thenThrow(new IllegalStateException(DETALHE));
        BffUserController.RecaptchaRequest falha = new BffUserController.RecaptchaRequest("t", "login");

        ResponseEntity<?> resposta = userController.verifyRecaptcha(falha);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resposta.getStatusCode());
        assertEquals(Map.of("success", false, "error", ErroInterno.MENSAGEM), resposta.getBody());
        assertTrue(detalheNoLog(), "o detalhe com stack trace tem de ir para o log");
    }

    // ------------------------------------------------------- identity indisponível (#36)

    private static final String EMAIL_NA_URI = "ana.secreta@example.com";
    private static final String MENSAGEM_DO_CLIENTE =
            "I/O error on GET request for \"http://identity:8084/internal/users/by-email?email=ana.secreta%40example.com\": " + EMAIL_NA_URI;

    /**
     * {@link IdentityUnavailableException} carrega a URI chamada (e o e-mail, no {@code by-email}). Em TODA rota de usuário que tem
     * {@code catch (RuntimeException)}/{@code catch (Exception)} ela tem de escapar até o advice: 503 genérico (nunca 404, 400 nem
     * 500), sem a URI nem o e-mail no corpo, e SEM a mensagem crua no log (só a classe).
     */
    @Test
    void identityForaViraTresZeroTresGenericoEmTodaRotaDeUsuarioESemVazarNoLog() throws Exception {
        IdentityUnavailableException fora = new IdentityUnavailableException(MENSAGEM_DO_CLIENTE, new RuntimeException(EMAIL_NA_URI));
        when(userApi.findByEmail(any())).thenThrow(fora);
        when(userApi.findById(anyLong())).thenThrow(fora);
        when(userApi.createUser(any())).thenThrow(fora);
        when(userApi.updateUser(any())).thenThrow(fora);
        when(userApi.changePassword(anyLong(), any(), any())).thenThrow(fora);
        doThrow(fora).when(userApi).deactivateUser(anyLong());
        when(userApi.requestPasswordResetMfa(anyLong())).thenThrow(fora);
        when(userApi.verifyPasswordResetMfaCode(anyLong(), any())).thenThrow(fora);
        doThrow(fora).when(userApi).changePasswordWithMfa(anyLong(), any(), any());
        when(userApi.login(any(), any())).thenThrow(fora);
        when(recaptchaApi.verify(any(), any())).thenThrow(fora);

        List<MockHttpServletRequestBuilder> rotas = List.of(
                get("/bff/users/email/" + EMAIL_NA_URI),
                get("/bff/users/1"),
                get("/bff/users/me"),
                post("/bff/users").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"),
                put("/bff/users/1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"),
                put("/bff/users/1/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"a\",\"newPassword\":\"b\"}"),
                delete("/bff/users/1"),
                post("/bff/users/1/request-password-reset-mfa"),
                post("/bff/users/1/verify-password-reset-mfa").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaCode\":\"123456\"}"),
                put("/bff/users/1/password-with-mfa").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaCode\":\"123456\",\"newPassword\":\"nova123\"}"),
                post("/bff/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.com\",\"password\":\"x\"}"),
                post("/bff/users/verify-recaptcha").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\",\"action\":\"login\"}"));
        for (MockHttpServletRequestBuilder rota : rotas) {
            String corpo = mvc.perform(rota).andExpect(status().isServiceUnavailable()).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            assertEquals(IdentityIndisponivelHandler.INDISPONIVEL, corpo);
        }

        for (ILoggingEvent e : logs.list) {
            String tudo = e.getFormattedMessage() + (e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getMessage());
            assertFalse(tudo.contains(EMAIL_NA_URI) || tudo.contains("identity:8084") || tudo.contains("/internal/"),
                    "o log não pode carregar a mensagem crua da exceção do cliente: " + tudo);
        }
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("IdentityUnavailableException")),
                "o 503 tem de ser logado (só a classe)");
    }

    // ------------------------------------------------------------------ evento

    @Test
    void criarEvento() throws Exception {
        when(eventApi.createEvent(any())).thenThrow(new RuntimeException(DETALHE));
        esperar500(post("/bff/events").contentType(MediaType.APPLICATION_JSON).content(JSON_EVENTO));
    }

    @Test
    void deletarEvento() throws Exception {
        when(eventApi.findCreatorId(1L)).thenReturn(5L);
        doThrow(new RuntimeException(DETALHE)).when(eventApi).delete(1L);
        esperar500(delete("/bff/events/1"));
    }

    @Test
    void atualizarEvento() throws Exception {
        when(eventApi.findCreatorId(1L)).thenReturn(5L);
        when(eventApi.updateEvent(any())).thenThrow(new RuntimeException(DETALHE));
        esperar500(put("/bff/events/1").contentType(MediaType.APPLICATION_JSON).content(JSON_EVENTO));
    }

    @Test
    void imagemDoEvento() throws Exception {
        when(eventApi.findCreatorId(1L)).thenReturn(5L);
        MockMultipartFile arquivo = new MockMultipartFile("image", "a.png", "image/png", new byte[] {1, 2, 3}) {
            @Override
            public byte[] getBytes() throws IOException {
                throw new IOException(DETALHE);
            }
        };
        esperar500(multipart("/bff/events/1/image").file(arquivo));
    }

    // ----------------------------------------------------------- event-wallets

    @Test
    void reservar() throws Exception {
        when(walletGateway.addLink(any(EventWallet.class), any())).thenThrow(new RuntimeException(DETALHE));
        esperar500(post("/bff/event-wallets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":5,\"eventId\":2}"));
    }

    @Test
    void confirmarReserva() throws Exception {
        when(walletGateway.confirm(anyLong(), anyLong())).thenThrow(new RuntimeException(DETALHE));
        esperar500(post("/bff/event-wallets/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":2}"));
    }

    // ------------------------------------------------------------------ comuns

    private void esperar500(MockHttpServletRequestBuilder requisicao) throws Exception {
        String corpo = mvc.perform(requisicao).andExpect(status().isInternalServerError()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertEquals(ErroInterno.MENSAGEM, corpo);
        assertFalse(corpo.contains("segredo-interno-89") || corpo.contains("users_email_key"),
                "o corpo do 500 não pode carregar a mensagem da exceção");
        assertTrue(detalheNoLog(), "o detalhe com stack trace tem de ir para o log");
    }

    /** Há um log ERROR cuja exceção (último argumento, logo com stack trace) carrega o detalhe? */
    private boolean detalheNoLog() {
        return logs.list.stream().anyMatch(e -> e.getLevel().isGreaterOrEqual(Level.ERROR)
                && e.getThrowableProxy() != null
                && e.getThrowableProxy().getMessage() != null
                && e.getThrowableProxy().getMessage().contains("segredo-interno-89")
                && e.getThrowableProxy().getStackTraceElementProxyArray().length > 0);
    }
}
