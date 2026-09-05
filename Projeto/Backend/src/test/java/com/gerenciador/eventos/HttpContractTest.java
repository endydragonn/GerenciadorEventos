package com.gerenciador.eventos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.gerenciador.eventos.POJO.Event;
import com.gerenciador.eventos.POJO.EventWallet;
import com.gerenciador.eventos.POJO.MyWallet;
import com.gerenciador.eventos.POJO.PendingVerification;
import com.gerenciador.eventos.POJO.User;
import com.gerenciador.eventos.Repository.PendingVerificationRepository;
import com.gerenciador.eventos.Service.UserService;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.config.DatabaseConnection;

/**
 * Teste de contrato da superfície HTTP única {@code /bff/**} (issue #78).
 *
 * <p>Fixa path + método + forma de request/response de cada endpoint que o Flutter
 * consome (e, mais superficialmente, dos endpoints que só fazem parte da superfície).
 * Serve de rede de segurança para futuras mudanças (ex.: issue #25, gateway real) e para o
 * harness de carga da issue #11 — se este teste quebrar, a superfície HTTP mudou de forma
 * que o experimento de Fase 2 não deveria absorver sem querer.</p>
 *
 * <p>Usa {@code webEnvironment = RANDOM_PORT} + {@link TestRestTemplate} (bate no HTTP real,
 * incluindo o filtro de segurança) em vez de MockMvc — mais próximo do que o harness da
 * issue #11 vai efetivamente exercitar. {@code JavaMailSender} é mockado para não depender
 * de SMTP real nem dos timeouts de rede configurados; {@code RECAPTCHA_SECRET_KEY} é forçada
 * vazia para exercitar o caminho determinístico (sem chamar a API do Google).</p>
 *
 * <p>PR #79 (issue #78): {@code SecurityConfig} deixou de liberar {@code /bff/**} por inteiro — só a
 * lista explícita de rotas públicas continua sem token, o resto exige
 * {@code Authorization: Bearer <jwt>}. O fixture {@link #createUser(String)} passou a criar o
 * usuário via {@link UserService} direto (não HTTP) porque {@code POST /bff/users} agora
 * exige auth e a maioria dos testes usa esse fixture só para ter um usuário/token disponível
 * antes de exercitar a rota que o teste realmente cobre; o contrato HTTP de
 * {@code POST /bff/users} continua coberto por {@link #createUser_returns201WithUserId()}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
@TestPropertySource(properties = "RECAPTCHA_SECRET_KEY=")
public class HttpContractTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PendingVerificationRepository pendingVerificationRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private DatabaseConnection databaseConnection;

    @MockitoBean
    private JavaMailSender mailSender;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ======================= fixtures =======================

    /**
     * Cria usuário direto via {@link UserService} (não HTTP): desde o PR #79 (issue #78)
     * {@code POST /bff/users} exige auth, e este fixture é usado por quase todo teste só
     * para ter um usuário disponível antes de logar e exercitar a rota que o teste cobre de
     * fato. O contrato HTTP de criação continua coberto por
     * {@link #createUser_returns201WithUserId()}.
     */
    private User createUser(String tag) {
        User u = new User();
        u.setName("Contract User " + tag);
        u.setEmail(tag + "@example.com");
        u.setFone("119" + String.format("%08d", Math.abs(tag.hashCode()) % 100000000));
        u.setPassword("senha123");
        u.setBirthDate("1990-01-01");
        User created = userService.createUser(u);
        assertNotNull(created.getId());
        return created;
    }

    /** Igual {@link #createUser(String)}, mas promove o usuário a host via SQL direto —
     * não existe (de propósito) rota HTTP que promova host, para não abrir
     * auto-promoção via {@code PUT /bff/users/{id}}. */
    private User createHostUser(String tag) {
        User created = createUser(tag);
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement("UPDATE users SET host = true WHERE user_id = ?")) {
            stmt.setLong(1, created.getId());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Falha ao promover usuário de teste a host: " + e.getMessage(), e);
        }
        created.setIsHost(true);
        return created;
    }

    private String loginAndGetToken(String email, String password) {
        Map<String, String> creds = new HashMap<>();
        creds.put("email", email);
        creds.put("password", password);
        ResponseEntity<Map> resp = restTemplate.postForEntity(url("/bff/users/login"), creds, Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        return (String) resp.getBody().get("token");
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        return headers;
    }

    private Event createEvent(Long creatorId, String name, int capacity, String hostToken) {
        Event e = new Event();
        e.setCreator_id(creatorId);
        e.setEvent_name(name);
        e.setIs_EAD(true);
        // Truncado pra segundos: event_date fica em memória (nunca é relido do banco após o
        // save), mas buy_time_limit é relido do banco (que arredonda pra microssegundo) - com
        // nanossegundos, o valor relido pode ficar levemente DEPOIS do event_date em memória e
        // derrubar a validação "buy_time_limit não pode ser após event_date" no PUT seguinte.
        e.setEvent_date(LocalDateTime.now().plusDays(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        e.setLot_quantity(capacity);
        e.setDescription("desc");
        e.setPresenters(new ArrayList<>());
        HttpEntity<Event> entity = new HttpEntity<>(e, authHeaders(hostToken));
        ResponseEntity<Event> resp = restTemplate.exchange(url("/bff/events"), HttpMethod.POST, entity, Event.class);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return resp.getBody();
    }

    // ======================= /bff/users =======================

    @Test
    public void createUser_returns201WithUserId() {
        // POST /bff/users exige auth desde o PR #79 (issue #78): loga com um usuário já existente
        // (criado via service) só para ter um token, e exercita o contrato HTTP real de
        // criação com esse token.
        User existing = createUser("create-user-auth");
        String token = loginAndGetToken(existing.getEmail(), "senha123");

        User u = new User();
        u.setName("Contract User create-user");
        u.setEmail("create-user@example.com");
        u.setFone("11955512340");
        u.setPassword("senha123");
        u.setBirthDate("1990-01-01");

        HttpEntity<User> entity = new HttpEntity<>(u, authHeaders(token));
        ResponseEntity<User> resp = restTemplate.exchange(url("/bff/users"), HttpMethod.POST, entity, User.class);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertNotNull(resp.getBody().getId());
        assertEquals("create-user@example.com", resp.getBody().getEmail());
    }

    @Test
    public void getUserById_and_byEmail() {
        User created = createUser("get-user");
        String token = loginAndGetToken(created.getEmail(), "senha123");
        HttpEntity<Void> authed = new HttpEntity<>(authHeaders(token));

        ResponseEntity<User> byId = restTemplate.exchange(
                url("/bff/users/" + created.getId()), HttpMethod.GET, authed, User.class);
        assertEquals(HttpStatus.OK, byId.getStatusCode());
        assertEquals(created.getEmail(), byId.getBody().getEmail());

        ResponseEntity<User> byEmail = restTemplate.exchange(
                url("/bff/users/email/" + created.getEmail()), HttpMethod.GET, authed, User.class);
        assertEquals(HttpStatus.OK, byEmail.getStatusCode());
        assertEquals(created.getId(), byEmail.getBody().getId());

        ResponseEntity<String> notFound = restTemplate.exchange(
                url("/bff/users/999999999"), HttpMethod.GET, authed, String.class);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
    }

    @Test
    public void updateUser_returns200WithUpdatedFields() {
        User created = createUser("update-user");
        String token = loginAndGetToken(created.getEmail(), "senha123");
        created.setName("Nome Atualizado");

        HttpEntity<User> entity = new HttpEntity<>(created, authHeaders(token));
        ResponseEntity<User> resp = restTemplate.exchange(
                url("/bff/users/" + created.getId()), HttpMethod.PUT, entity, User.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("Nome Atualizado", resp.getBody().getName());
    }

    @Test
    public void changePassword_returns200PlainTextOnSuccess() {
        User created = createUser("change-password");
        String token = loginAndGetToken(created.getEmail(), "senha123");

        Map<String, String> body = new HashMap<>();
        body.put("oldPassword", "senha123");
        body.put("newPassword", "novaSenha456");
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, authHeaders(token));

        ResponseEntity<String> resp = restTemplate.exchange(
                url("/bff/users/" + created.getId() + "/password"), HttpMethod.PUT, entity, String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("Senha alterada"));
    }

    @Test
    public void deleteUser_returns204() {
        User created = createUser("delete-user");
        String token = loginAndGetToken(created.getEmail(), "senha123");
        HttpEntity<Void> entity = new HttpEntity<>(authHeaders(token));
        ResponseEntity<Void> resp = restTemplate.exchange(
                url("/bff/users/" + created.getId()), HttpMethod.DELETE, entity, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    @Test
    public void validateUser_returns200ForValidAnd400ForInvalid() {
        User caller = createUser("validate-user-auth");
        String token = loginAndGetToken(caller.getEmail(), "senha123");

        User valid = new User();
        valid.setName("Validate User");
        valid.setEmail("validate-user@example.com");
        valid.setFone("11955500001");
        HttpEntity<User> validEntity = new HttpEntity<>(valid, authHeaders(token));
        ResponseEntity<String> ok = restTemplate.exchange(
                url("/bff/users/validate"), HttpMethod.POST, validEntity, String.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());

        User invalid = new User();
        HttpEntity<User> invalidEntity = new HttpEntity<>(invalid, authHeaders(token));
        ResponseEntity<String> bad = restTemplate.exchange(
                url("/bff/users/validate"), HttpMethod.POST, invalidEntity, String.class);
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
    }

    @Test
    public void login_returns200WithUserAndToken_andRejectsBadCredentials() {
        createUser("login-user");

        Map<String, String> creds = new HashMap<>();
        creds.put("email", "login-user@example.com");
        creds.put("password", "senha123");

        ResponseEntity<Map> ok = restTemplate.postForEntity(url("/bff/users/login"), creds, Map.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertTrue(ok.getBody().containsKey("user"), "resposta de login deve ter a chave 'user'");
        assertTrue(ok.getBody().containsKey("token"), "resposta de login deve ter a chave 'token'");
        String token = (String) ok.getBody().get("token");
        assertNotNull(token);
        assertEquals(3, token.split("\\.").length, "token deve ser um JWT (3 partes)");

        Map<String, String> wrongPassword = new HashMap<>();
        wrongPassword.put("email", "login-user@example.com");
        wrongPassword.put("password", "senha-errada");
        ResponseEntity<String> unauthorized = restTemplate.postForEntity(
                url("/bff/users/login"), wrongPassword, String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, unauthorized.getStatusCode());
    }

    @Test
    public void me_returnsUserWhenAuthenticated_and401WhenNot() {
        createUser("me-user");

        Map<String, String> creds = new HashMap<>();
        creds.put("email", "me-user@example.com");
        creds.put("password", "senha123");
        Map loginBody = restTemplate.postForEntity(url("/bff/users/login"), creds, Map.class).getBody();
        String token = (String) loginBody.get("token");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        ResponseEntity<User> authed = restTemplate.exchange(
                url("/bff/users/me"), HttpMethod.GET, new HttpEntity<>(headers), User.class);
        assertEquals(HttpStatus.OK, authed.getStatusCode());
        assertEquals("me-user@example.com", authed.getBody().getEmail());

        // Desde o PR #79 (issue #78) /bff/users/me exige auth no próprio SecurityConfig: sem token,
        // a requisição é barrada ANTES do controller (401 fixo via HttpStatusEntryPoint), e
        // o corpo "Não autenticado" (que o controller monta pro caso "autenticado, mas com
        // authentication anônima") não é mais alcançado nesse caminho.
        ResponseEntity<String> anon = restTemplate.getForEntity(url("/bff/users/me"), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, anon.getStatusCode());
    }

    @Test
    public void registrationFlow_requestVerification_verifyEmail_resendCode() throws Exception {
        String email = "register-flow@example.com";
        Map<String, String> payload = new HashMap<>();
        payload.put("name", "Fluxo Cadastro");
        payload.put("email", email);
        payload.put("fone", "11988887777");
        payload.put("password", "senha123");
        payload.put("birthdate", "1995-05-20");

        ResponseEntity<Map> requestResp = restTemplate.postForEntity(
                url("/bff/users/request-verification"), payload, Map.class);
        assertEquals(HttpStatus.CREATED, requestResp.getStatusCode());
        assertTrue(requestResp.getBody().containsKey("message"));
        assertEquals(email, requestResp.getBody().get("email"));

        // resend-verification-code: 200 + message, sem expor o código
        ResponseEntity<Map> resendResp = restTemplate.postForEntity(
                url("/bff/users/resend-verification-code"), Map.of("email", email), Map.class);
        assertEquals(HttpStatus.OK, resendResp.getStatusCode());
        assertTrue(resendResp.getBody().containsKey("message"));

        // código só existe no banco (é enviado por e-mail, mockado) — lido aqui só para
        // montar o fixture do passo seguinte, não para testar o envio de e-mail em si.
        Optional<PendingVerification> pending = pendingVerificationRepository.findByEmail(email);
        assertTrue(pending.isPresent());
        String code = pending.get().getVerificationCode();

        Map<String, String> verifyBody = new HashMap<>();
        verifyBody.put("email", email);
        verifyBody.put("verificationCode", code);
        ResponseEntity<Map> verifyResp = restTemplate.postForEntity(
                url("/bff/users/verify-email"), verifyBody, Map.class);
        assertEquals(HttpStatus.CREATED, verifyResp.getStatusCode());
        assertTrue(verifyResp.getBody().containsKey("token"));
        assertTrue(verifyResp.getBody().containsKey("user"));
        assertTrue(verifyResp.getBody().containsKey("message"));

        // código inválido -> 400
        ResponseEntity<String> invalidCode = restTemplate.postForEntity(
                url("/bff/users/verify-email"), Map.of("email", email, "verificationCode", "000000"), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, invalidCode.getStatusCode());
    }

    @Test
    public void passwordResetMfa_requestVerifyAndChange() {
        User user = createUser("mfa-user");
        String token = loginAndGetToken(user.getEmail(), "senha123");
        HttpEntity<Void> noBodyAuthed = new HttpEntity<>(authHeaders(token));

        ResponseEntity<Map> requestResp = restTemplate.exchange(
                url("/bff/users/" + user.getId() + "/request-password-reset-mfa"),
                HttpMethod.POST, noBodyAuthed, Map.class);
        assertEquals(HttpStatus.OK, requestResp.getStatusCode());
        assertTrue(requestResp.getBody().containsKey("message"));

        // O código MFA é enviado só por e-mail (mockado); geramos um novo direto pelo
        // service (mesmo autowired usado pelo controller) só para montar o fixture do
        // passo seguinte — não estamos testando a geração do código aqui.
        String mfaCode = userService.generatePasswordResetMfa(user.getId());

        HttpEntity<Map<String, String>> verifyEntity = new HttpEntity<>(Map.of("mfaCode", mfaCode), authHeaders(token));
        ResponseEntity<Map> verifyResp = restTemplate.exchange(
                url("/bff/users/" + user.getId() + "/verify-password-reset-mfa"),
                HttpMethod.POST, verifyEntity, Map.class);
        assertEquals(HttpStatus.OK, verifyResp.getStatusCode());
        assertTrue(verifyResp.getBody().containsKey("message"));

        HttpEntity<Map<String, String>> invalidEntity = new HttpEntity<>(Map.of("mfaCode", "invalido"), authHeaders(token));
        ResponseEntity<String> invalidMfa = restTemplate.exchange(
                url("/bff/users/" + user.getId() + "/verify-password-reset-mfa"),
                HttpMethod.POST, invalidEntity, String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, invalidMfa.getStatusCode());

        String mfaCode2 = userService.generatePasswordResetMfa(user.getId());
        Map<String, String> changeBody = new HashMap<>();
        changeBody.put("mfaCode", mfaCode2);
        changeBody.put("newPassword", "senhaComMfa789");
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(changeBody, authHeaders(token));
        ResponseEntity<Map> changeResp = restTemplate.exchange(
                url("/bff/users/" + user.getId() + "/password-with-mfa"), HttpMethod.PUT, entity, Map.class);
        assertEquals(HttpStatus.OK, changeResp.getStatusCode());
        assertTrue(changeResp.getBody().containsKey("message"));
    }

    @Test
    public void verifyRecaptcha_returns500WhenSecretNotConfigured() {
        // RECAPTCHA_SECRET_KEY forçada vazia via @TestPropertySource: caminho
        // determinístico, sem chamar a API do Google.
        Map<String, String> body = new HashMap<>();
        body.put("token", "qualquer-token");
        body.put("action", "login");

        ResponseEntity<Map> resp = restTemplate.postForEntity(url("/bff/users/verify-recaptcha"), body, Map.class);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals(Boolean.FALSE, resp.getBody().get("success"));
        assertTrue(resp.getBody().containsKey("error"));
    }

    // ======================= /bff/events =======================

    @Test
    public void eventCrud_createGetUpdateSearchListByCreator() {
        User creator = createHostUser("event-creator");
        String token = loginAndGetToken(creator.getEmail(), "senha123");
        Event created = createEvent(creator.getId(), "Evento Contrato " + System.nanoTime(), 10, token);
        assertNotNull(created.getEvent_id());
        assertEquals(10, created.getLot_quantity());

        // GET é público (não exige token) — permanece assim (rota consumida sem login pelo
        // Flutter na tela de detalhes do evento).
        ResponseEntity<Event> byId = restTemplate.getForEntity(
                url("/bff/events/" + created.getEvent_id()), Event.class);
        assertEquals(HttpStatus.OK, byId.getStatusCode());
        assertEquals(created.getEvent_name(), byId.getBody().getEvent_name());

        created.setDescription("descrição atualizada");
        HttpEntity<Event> updateEntity = new HttpEntity<>(created, authHeaders(token));
        ResponseEntity<Event> updated = restTemplate.exchange(
                url("/bff/events/" + created.getEvent_id()), HttpMethod.PUT, updateEntity, Event.class);
        assertEquals(HttpStatus.OK, updated.getStatusCode());
        assertEquals("descrição atualizada", updated.getBody().getDescription());

        ResponseEntity<Event[]> search = restTemplate.getForEntity(
                url("/bff/events/search?term=" + created.getEvent_name()), Event[].class);
        assertEquals(HttpStatus.OK, search.getStatusCode());
        assertTrue(search.getBody().length >= 1);

        ResponseEntity<Event[]> byCreator = restTemplate.getForEntity(
                url("/bff/events?creator_id=" + creator.getId()), Event[].class);
        assertEquals(HttpStatus.OK, byCreator.getStatusCode());
        assertTrue(byCreator.getBody().length >= 1);
    }

    @Test
    public void validateEvent_returns200ForValidAnd400ForInvalid() {
        // /bff/events/validate não é GET, então não está na lista pública de eventos —
        // exige um usuário autenticado (não precisa ser host: só createEvent/updateEvent/
        // deleteEvent ganharam o gate de host do PR #79 (issue #78)).
        User creator = createUser("event-validate-creator");
        String token = loginAndGetToken(creator.getEmail(), "senha123");

        Event valid = new Event();
        valid.setCreator_id(creator.getId());
        valid.setEvent_name("Evento Válido " + System.nanoTime());
        valid.setIs_EAD(true);
        valid.setEvent_date(LocalDateTime.now().plusDays(5));
        valid.setLot_quantity(5);
        valid.setPresenters(new ArrayList<>());

        HttpEntity<Event> validEntity = new HttpEntity<>(valid, authHeaders(token));
        ResponseEntity<String> ok = restTemplate.exchange(
                url("/bff/events/validate"), HttpMethod.POST, validEntity, String.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());

        Event invalid = new Event();
        HttpEntity<Event> invalidEntity = new HttpEntity<>(invalid, authHeaders(token));
        ResponseEntity<String> bad = restTemplate.exchange(
                url("/bff/events/validate"), HttpMethod.POST, invalidEntity, String.class);
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
    }

    @Test
    public void eventImage_getReturns404WhenAbsent_uploadRejectsNonImage() {
        User creator = createHostUser("event-image-creator");
        String token = loginAndGetToken(creator.getEmail(), "senha123");
        Event event = createEvent(creator.getId(), "Evento Imagem " + System.nanoTime(), 5, token);

        // GET da imagem continua público (servido via <img>/Image.network no Flutter, que
        // nunca manda header customizado).
        ResponseEntity<byte[]> getImage = restTemplate.getForEntity(
                url("/bff/events/" + event.getEvent_id() + "/image"), byte[].class);
        assertEquals(HttpStatus.NOT_FOUND, getImage.getStatusCode());

        // Upload (POST) não é GET, exige auth — mas não exige host (não ganhou o gate de
        // ensureUserIsHost(), só createEvent/updateEvent/deleteEvent).
        MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
        org.springframework.core.io.ByteArrayResource notAnImage =
                new org.springframework.core.io.ByteArrayResource("nao é imagem".getBytes()) {
                    @Override
                    public String getFilename() {
                        return "arquivo.txt";
                    }
                };
        multipart.add("image", notAnImage);
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> uploadEntity = new HttpEntity<>(multipart, headers);

        ResponseEntity<String> upload = restTemplate.postForEntity(
                url("/bff/events/" + event.getEvent_id() + "/image"), uploadEntity, String.class);
        assertEquals(HttpStatus.BAD_REQUEST, upload.getStatusCode());
    }

    @Test
    public void deleteEvent_returns204() {
        User creator = createHostUser("event-delete-creator");
        String token = loginAndGetToken(creator.getEmail(), "senha123");
        Event event = createEvent(creator.getId(), "Evento Para Deletar " + System.nanoTime(), 5, token);

        HttpEntity<Void> entity = new HttpEntity<>(authHeaders(token));
        ResponseEntity<Void> resp = restTemplate.exchange(
                url("/bff/events/" + event.getEvent_id()), HttpMethod.DELETE, entity, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    @Test
    public void createEvent_nonHostUser_returns400() {
        // Finding 2 (PR #79, issue #78): usuário autenticado que NÃO é host não pode criar evento.
        User nonHost = createUser("event-non-host-creator");
        String token = loginAndGetToken(nonHost.getEmail(), "senha123");

        Event event = new Event();
        event.setCreator_id(nonHost.getId());
        event.setEvent_name("Evento Não-Host " + System.nanoTime());
        event.setIs_EAD(true);
        event.setEvent_date(LocalDateTime.now().plusDays(10));
        event.setLot_quantity(5);
        event.setPresenters(new ArrayList<>());

        HttpEntity<Event> entity = new HttpEntity<>(event, authHeaders(token));
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/bff/events"), HttpMethod.POST, entity, String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().contains("host"),
                "esperado erro de ensureUserIsHost() mencionando 'host', veio: " + resp.getBody());
    }

    @Test
    public void protectedRoute_withoutToken_returns401() {
        // Caso novo do PR #79 (issue #78): rota que passou a exigir auth (event-wallets, por
        // exemplo) bate 401 sem token — SecurityConfig barra ANTES do controller.
        ResponseEntity<String> resp = restTemplate.getForEntity(url("/bff/my-wallets/1"), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    public void corsPreflight_onProtectedRoute_isNotBlockedByAuth() {
        // Rodada de fix da issue #78 (achado do revisor): preflight CORS (OPTIONS +
        // Origin + Access-Control-Request-*) numa rota protegida não pode cair em
        // .authenticated() — o browser nunca manda Authorization no preflight. Este teste é
        // a rede de segurança pra isso: guarda que o preflight nunca vira 401, mesmo que no
        // futuro alguém reordene os filtros ou mude a config de CORS.
        // TestRestTemplate não emite preflight sozinho para uma chamada normal, mas nada
        // impede montar a requisição OPTIONS à mão como o browser faria — é isso que este
        // teste faz.
        //
        // Verificado manualmente (comentando a regra .requestMatchers(HttpMethod.OPTIONS,
        // "/**").permitAll() e rodando só este teste) que este caso já passava ANTES da
        // regra nova: o CorsFilter que .cors(...) registra no filter chain intercepta e
        // responde o preflight antes do AuthorizationFilter, então .authenticated() nunca
        // chega a barrar. A regra OPTIONS→permitAll continua sendo mantida como defesa em
        // profundidade (ver comentário em SecurityConfig), não como correção de um 401 real.
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "http://localhost:9999");
        headers.set("Access-Control-Request-Method", "DELETE");
        headers.set("Access-Control-Request-Headers", "authorization,content-type");
        HttpEntity<Void> preflight = new HttpEntity<>(headers);

        ResponseEntity<String> resp = restTemplate.exchange(
                url("/bff/my-wallets/1"), HttpMethod.OPTIONS, preflight, String.class);

        assertTrue(!resp.getStatusCode().equals(HttpStatus.UNAUTHORIZED),
                "preflight numa rota protegida não deve ser barrado por auth, veio " + resp.getStatusCode());
        assertEquals("http://localhost:9999", resp.getHeaders().getFirst("Access-Control-Allow-Origin"));
    }

    // ======================= /bff/event-wallets =======================

    @Test
    public void eventWalletFlow_addConfirmListRemove() {
        // Todo /bff/event-wallets/** exige auth desde o PR #79 (issue #78) — usa o token do attendee
        // (quem está se inscrevendo) em cada chamada.
        User creator = createHostUser("wallet-event-creator");
        String hostToken = loginAndGetToken(creator.getEmail(), "senha123");
        User attendee = createUser("wallet-attendee");
        String attendeeToken = loginAndGetToken(attendee.getEmail(), "senha123");
        Event event = createEvent(creator.getId(), "Evento Carteira " + System.nanoTime(), 5, hostToken);

        EventWallet link = new EventWallet(attendee.getId(), event.getEvent_id());
        HttpEntity<EventWallet> linkEntity = new HttpEntity<>(link, authHeaders(attendeeToken));

        // /validate checa se o vínculo PODERIA ser criado agora (duplicidade via
        // existsActive), não se um vínculo já existente é válido - por isso roda ANTES do
        // addLink, enquanto ainda não há vínculo pending/confirmed pra esse par usuário/evento.
        ResponseEntity<String> valid = restTemplate.exchange(
                url("/bff/event-wallets/validate"), HttpMethod.POST, linkEntity, String.class);
        assertEquals(HttpStatus.OK, valid.getStatusCode());

        ResponseEntity<EventWallet> added = restTemplate.exchange(
                url("/bff/event-wallets"), HttpMethod.POST, linkEntity, EventWallet.class);
        assertEquals(HttpStatus.CREATED, added.getStatusCode());
        assertEquals("pending", added.getBody().getStatus());

        ResponseEntity<EventWallet> confirmed = restTemplate.exchange(
                url("/bff/event-wallets/confirm"), HttpMethod.POST, linkEntity, EventWallet.class);
        assertEquals(HttpStatus.OK, confirmed.getStatusCode());
        assertEquals("confirmed", confirmed.getBody().getStatus());

        HttpEntity<Void> authedGet = new HttpEntity<>(authHeaders(attendeeToken));
        ResponseEntity<EventWallet[]> byUser = restTemplate.exchange(
                url("/bff/event-wallets/user/" + attendee.getId()), HttpMethod.GET, authedGet, EventWallet[].class);
        assertEquals(HttpStatus.OK, byUser.getStatusCode());
        assertTrue(byUser.getBody().length >= 1);

        HttpEntity<Void> authedDelete = new HttpEntity<>(authHeaders(attendeeToken));
        ResponseEntity<Void> removed = restTemplate.exchange(
                url("/bff/event-wallets?userId=" + attendee.getId() + "&eventId=" + event.getEvent_id()),
                HttpMethod.DELETE, authedDelete, Void.class);
        assertEquals(HttpStatus.OK, removed.getStatusCode());
    }

    // ======================= /bff/my-wallets =======================

    @Test
    public void myWallet_getByUser_validate_ensure() {
        // Todo /bff/my-wallets/** exige auth desde o PR #79 (issue #78).
        User user = createUser("my-wallet-user");
        String token = loginAndGetToken(user.getEmail(), "senha123");
        HttpEntity<Void> authedGet = new HttpEntity<>(authHeaders(token));

        // criada automaticamente pelo trigger no cadastro do usuário
        ResponseEntity<MyWallet> byUser = restTemplate.exchange(
                url("/bff/my-wallets/" + user.getId()), HttpMethod.GET, authedGet, MyWallet.class);
        assertEquals(HttpStatus.OK, byUser.getStatusCode());
        assertEquals(user.getId(), byUser.getBody().getUserId());

        MyWallet toValidate = new MyWallet(user.getId());
        HttpEntity<MyWallet> validateEntity = new HttpEntity<>(toValidate, authHeaders(token));
        ResponseEntity<String> valid = restTemplate.exchange(
                url("/bff/my-wallets/validate"), HttpMethod.POST, validateEntity, String.class);
        assertEquals(HttpStatus.OK, valid.getStatusCode());

        HttpEntity<Void> ensureEntity = new HttpEntity<>(authHeaders(token));
        ResponseEntity<MyWallet> ensured = restTemplate.exchange(
                url("/bff/my-wallets/ensure/" + user.getId()), HttpMethod.POST, ensureEntity, MyWallet.class);
        assertEquals(HttpStatus.OK, ensured.getStatusCode());
        assertEquals(user.getId(), ensured.getBody().getUserId());
    }

    // ======================= /bff/seed =======================

    @Test
    public void seed_acceptsEmptyPayload_returns200WithSummary() {
        Map<String, Object> emptyPayload = Map.of();
        ResponseEntity<Map> resp = restTemplate.postForEntity(url("/bff/seed"), emptyPayload, Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("success", resp.getBody().get("status"));
        assertEquals(0, ((Number) resp.getBody().get("usersCreated")).intValue());
    }

    // ======================= gate público / superfície antiga removida =======================

    @Test
    public void bffSurface_isPublic_noAuthorizationHeaderNeeded() {
        // /bff/events/search continua público mesmo depois do PR #79 (issue #78) (deny-by-default
        // dentro de /bff/**): está na lista explícita "GET /bff/events/**" liberada em
        // SecurityConfig, pois é chamada sem login pela tela de busca do Flutter.
        ResponseEntity<Event[]> resp = restTemplate.getForEntity(url("/bff/events/search?term="), Event[].class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    public void oldApiSurface_noLongerServesBusinessRoutes() {
        // As rotas de negócio migraram de /api/** para /bff/** (issue #78). Não há mais
        // dispatch para controller nenhum sob /api/**: SecurityConfig não lista /api/** em
        // nenhum matcher público, então "anyRequest().authenticated()" barra a chamada ANTES
        // do dispatch (401 fixo, via HttpStatusEntryPoint desde o PR #79, issue #78) — nunca cai no
        // controller antigo. Sem token, o importante é que NUNCA seja 2xx: não deve haver
        // rota de negócio viva sob /api/**.
        ResponseEntity<String> resp = restTemplate.getForEntity(url("/api/users/999"), String.class);
        assertTrue(!resp.getStatusCode().is2xxSuccessful(),
                "esperado não-2xx para rota /api/** removida, veio " + resp.getStatusCode());

        ResponseEntity<String> seedResp = restTemplate.getForEntity(url("/api/seed"), String.class);
        assertTrue(!seedResp.getStatusCode().is2xxSuccessful(),
                "esperado não-2xx para /api/seed removido, veio " + seedResp.getStatusCode());
    }
}
