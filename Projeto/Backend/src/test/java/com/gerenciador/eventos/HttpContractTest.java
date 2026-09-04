package com.gerenciador.eventos;

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

    @MockitoBean
    private JavaMailSender mailSender;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ======================= fixtures =======================

    private User createUser(String tag) {
        User u = new User();
        u.setName("Contract User " + tag);
        u.setEmail(tag + "@example.com");
        u.setFone("119" + String.format("%08d", Math.abs(tag.hashCode()) % 100000000));
        u.setPassword("senha123");
        u.setBirthDate("1990-01-01");
        ResponseEntity<User> resp = restTemplate.postForEntity(url("/bff/users"), u, User.class);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return resp.getBody();
    }

    private Event createEvent(Long creatorId, String name, int capacity) {
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
        ResponseEntity<Event> resp = restTemplate.postForEntity(url("/bff/events"), e, Event.class);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return resp.getBody();
    }

    // ======================= /bff/users =======================

    @Test
    public void createUser_returns201WithUserId() {
        User created = createUser("create-user");
        assertNotNull(created.getId());
        assertEquals("create-user@example.com", created.getEmail());
    }

    @Test
    public void getUserById_and_byEmail() {
        User created = createUser("get-user");

        ResponseEntity<User> byId = restTemplate.getForEntity(url("/bff/users/" + created.getId()), User.class);
        assertEquals(HttpStatus.OK, byId.getStatusCode());
        assertEquals(created.getEmail(), byId.getBody().getEmail());

        ResponseEntity<User> byEmail = restTemplate.getForEntity(
                url("/bff/users/email/" + created.getEmail()), User.class);
        assertEquals(HttpStatus.OK, byEmail.getStatusCode());
        assertEquals(created.getId(), byEmail.getBody().getId());

        ResponseEntity<String> notFound = restTemplate.getForEntity(url("/bff/users/999999999"), String.class);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
    }

    @Test
    public void updateUser_returns200WithUpdatedFields() {
        User created = createUser("update-user");
        created.setName("Nome Atualizado");

        HttpEntity<User> entity = new HttpEntity<>(created);
        ResponseEntity<User> resp = restTemplate.exchange(
                url("/bff/users/" + created.getId()), HttpMethod.PUT, entity, User.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("Nome Atualizado", resp.getBody().getName());
    }

    @Test
    public void changePassword_returns200PlainTextOnSuccess() {
        User created = createUser("change-password");

        Map<String, String> body = new HashMap<>();
        body.put("oldPassword", "senha123");
        body.put("newPassword", "novaSenha456");
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body);

        ResponseEntity<String> resp = restTemplate.exchange(
                url("/bff/users/" + created.getId() + "/password"), HttpMethod.PUT, entity, String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("Senha alterada"));
    }

    @Test
    public void deleteUser_returns204() {
        User created = createUser("delete-user");
        ResponseEntity<Void> resp = restTemplate.exchange(
                url("/bff/users/" + created.getId()), HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    @Test
    public void validateUser_returns200ForValidAnd400ForInvalid() {
        User valid = new User();
        valid.setName("Validate User");
        valid.setEmail("validate-user@example.com");
        valid.setFone("11955500001");
        ResponseEntity<String> ok = restTemplate.postForEntity(url("/bff/users/validate"), valid, String.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());

        User invalid = new User();
        ResponseEntity<String> bad = restTemplate.postForEntity(url("/bff/users/validate"), invalid, String.class);
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

        ResponseEntity<String> anon = restTemplate.getForEntity(url("/bff/users/me"), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, anon.getStatusCode());
        assertEquals("Não autenticado", anon.getBody());
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

        ResponseEntity<Map> requestResp = restTemplate.postForEntity(
                url("/bff/users/" + user.getId() + "/request-password-reset-mfa"), null, Map.class);
        assertEquals(HttpStatus.OK, requestResp.getStatusCode());
        assertTrue(requestResp.getBody().containsKey("message"));

        // O código MFA é enviado só por e-mail (mockado); geramos um novo direto pelo
        // service (mesmo autowired usado pelo controller) só para montar o fixture do
        // passo seguinte — não estamos testando a geração do código aqui.
        String mfaCode = userService.generatePasswordResetMfa(user.getId());

        ResponseEntity<Map> verifyResp = restTemplate.postForEntity(
                url("/bff/users/" + user.getId() + "/verify-password-reset-mfa"),
                Map.of("mfaCode", mfaCode), Map.class);
        assertEquals(HttpStatus.OK, verifyResp.getStatusCode());
        assertTrue(verifyResp.getBody().containsKey("message"));

        ResponseEntity<String> invalidMfa = restTemplate.postForEntity(
                url("/bff/users/" + user.getId() + "/verify-password-reset-mfa"),
                Map.of("mfaCode", "invalido"), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, invalidMfa.getStatusCode());

        String mfaCode2 = userService.generatePasswordResetMfa(user.getId());
        Map<String, String> changeBody = new HashMap<>();
        changeBody.put("mfaCode", mfaCode2);
        changeBody.put("newPassword", "senhaComMfa789");
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(changeBody);
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
        User creator = createUser("event-creator");
        Event created = createEvent(creator.getId(), "Evento Contrato " + System.nanoTime(), 10);
        assertNotNull(created.getEvent_id());
        assertEquals(10, created.getLot_quantity());

        ResponseEntity<Event> byId = restTemplate.getForEntity(
                url("/bff/events/" + created.getEvent_id()), Event.class);
        assertEquals(HttpStatus.OK, byId.getStatusCode());
        assertEquals(created.getEvent_name(), byId.getBody().getEvent_name());

        created.setDescription("descrição atualizada");
        HttpEntity<Event> updateEntity = new HttpEntity<>(created);
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
        User creator = createUser("event-validate-creator");
        Event valid = new Event();
        valid.setCreator_id(creator.getId());
        valid.setEvent_name("Evento Válido " + System.nanoTime());
        valid.setIs_EAD(true);
        valid.setEvent_date(LocalDateTime.now().plusDays(5));
        valid.setLot_quantity(5);
        valid.setPresenters(new ArrayList<>());

        ResponseEntity<String> ok = restTemplate.postForEntity(url("/bff/events/validate"), valid, String.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());

        Event invalid = new Event();
        ResponseEntity<String> bad = restTemplate.postForEntity(url("/bff/events/validate"), invalid, String.class);
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
    }

    @Test
    public void eventImage_getReturns404WhenAbsent_uploadRejectsNonImage() {
        User creator = createUser("event-image-creator");
        Event event = createEvent(creator.getId(), "Evento Imagem " + System.nanoTime(), 5);

        ResponseEntity<byte[]> getImage = restTemplate.getForEntity(
                url("/bff/events/" + event.getEvent_id() + "/image"), byte[].class);
        assertEquals(HttpStatus.NOT_FOUND, getImage.getStatusCode());

        MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
        org.springframework.core.io.ByteArrayResource notAnImage =
                new org.springframework.core.io.ByteArrayResource("nao é imagem".getBytes()) {
                    @Override
                    public String getFilename() {
                        return "arquivo.txt";
                    }
                };
        multipart.add("image", notAnImage);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> uploadEntity = new HttpEntity<>(multipart, headers);

        ResponseEntity<String> upload = restTemplate.postForEntity(
                url("/bff/events/" + event.getEvent_id() + "/image"), uploadEntity, String.class);
        assertEquals(HttpStatus.BAD_REQUEST, upload.getStatusCode());
    }

    @Test
    public void deleteEvent_returns204() {
        User creator = createUser("event-delete-creator");
        Event event = createEvent(creator.getId(), "Evento Para Deletar " + System.nanoTime(), 5);

        ResponseEntity<Void> resp = restTemplate.exchange(
                url("/bff/events/" + event.getEvent_id()), HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    // ======================= /bff/event-wallets =======================

    @Test
    public void eventWalletFlow_addConfirmListRemove() {
        User creator = createUser("wallet-event-creator");
        User attendee = createUser("wallet-attendee");
        Event event = createEvent(creator.getId(), "Evento Carteira " + System.nanoTime(), 5);

        EventWallet link = new EventWallet(attendee.getId(), event.getEvent_id());

        // /validate checa se o vínculo PODERIA ser criado agora (duplicidade via
        // existsActive), não se um vínculo já existente é válido - por isso roda ANTES do
        // addLink, enquanto ainda não há vínculo pending/confirmed pra esse par usuário/evento.
        ResponseEntity<String> valid = restTemplate.postForEntity(
                url("/bff/event-wallets/validate"), link, String.class);
        assertEquals(HttpStatus.OK, valid.getStatusCode());

        ResponseEntity<EventWallet> added = restTemplate.postForEntity(
                url("/bff/event-wallets"), link, EventWallet.class);
        assertEquals(HttpStatus.CREATED, added.getStatusCode());
        assertEquals("pending", added.getBody().getStatus());

        ResponseEntity<EventWallet> confirmed = restTemplate.postForEntity(
                url("/bff/event-wallets/confirm"), link, EventWallet.class);
        assertEquals(HttpStatus.OK, confirmed.getStatusCode());
        assertEquals("confirmed", confirmed.getBody().getStatus());

        ResponseEntity<EventWallet[]> byUser = restTemplate.getForEntity(
                url("/bff/event-wallets/user/" + attendee.getId()), EventWallet[].class);
        assertEquals(HttpStatus.OK, byUser.getStatusCode());
        assertTrue(byUser.getBody().length >= 1);

        ResponseEntity<Void> removed = restTemplate.exchange(
                url("/bff/event-wallets?userId=" + attendee.getId() + "&eventId=" + event.getEvent_id()),
                HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
        assertEquals(HttpStatus.OK, removed.getStatusCode());
    }

    // ======================= /bff/my-wallets =======================

    @Test
    public void myWallet_getByUser_validate_ensure() {
        User user = createUser("my-wallet-user");

        // criada automaticamente pelo trigger no cadastro do usuário
        ResponseEntity<MyWallet> byUser = restTemplate.getForEntity(
                url("/bff/my-wallets/" + user.getId()), MyWallet.class);
        assertEquals(HttpStatus.OK, byUser.getStatusCode());
        assertEquals(user.getId(), byUser.getBody().getUserId());

        MyWallet toValidate = new MyWallet(user.getId());
        ResponseEntity<String> valid = restTemplate.postForEntity(
                url("/bff/my-wallets/validate"), toValidate, String.class);
        assertEquals(HttpStatus.OK, valid.getStatusCode());

        ResponseEntity<MyWallet> ensured = restTemplate.postForEntity(
                url("/bff/my-wallets/ensure/" + user.getId()), null, MyWallet.class);
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
        // /bff/events/search é uma chamada de negócio real (não auth/health) e deve
        // continuar pública, sem token — mesmo gate de antes da issue #78 (fora de escopo
        // mudar isso; issue #57 trata o gate de event-wallets).
        ResponseEntity<Event[]> resp = restTemplate.getForEntity(url("/bff/events/search?term="), Event[].class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    public void oldApiSurface_noLongerServesBusinessRoutes() {
        // As rotas de negócio migraram de /api/** para /bff/** (issue #78). Não há mais
        // dispatch para controller nenhum sob /api/**: SecurityConfig só deixa /bff/** e
        // /actuator/health públicos, então "anyRequest().authenticated()" barra a chamada
        // ANTES do dispatch (401/403, dependendo do AuthenticationEntryPoint padrão do
        // Spring Security) — nunca cai no controller antigo. Sem token, o importante é que
        // NUNCA seja 2xx: não deve haver rota de negócio viva sob /api/**.
        ResponseEntity<String> resp = restTemplate.getForEntity(url("/api/users/999"), String.class);
        assertTrue(!resp.getStatusCode().is2xxSuccessful(),
                "esperado não-2xx para rota /api/** removida, veio " + resp.getStatusCode());

        ResponseEntity<String> seedResp = restTemplate.getForEntity(url("/api/seed"), String.class);
        assertTrue(!seedResp.getStatusCode().is2xxSuccessful(),
                "esperado não-2xx para /api/seed removido, veio " + seedResp.getStatusCode());
    }
}
