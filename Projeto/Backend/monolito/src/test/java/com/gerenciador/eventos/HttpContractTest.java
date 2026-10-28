package com.gerenciador.eventos;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.gerenciador.eventos.catalogclient.api.Event;
import com.gerenciador.eventos.catalogclient.api.EventApi;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.identityclient.api.MyWallet;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.identityclient.api.RecaptchaResponse;
import com.gerenciador.eventos.identityclient.api.UserApi;
import com.gerenciador.eventos.TestSupport.FakeIdentityServer;
import com.gerenciador.eventos.TestSupport.FakeCatalogServer;
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
 * issue #11 vai efetivamente exercitar. Desde a #32 o monólito não envia e-mail (o cadastro e o MFA só appendam evento no backbone), então não há SMTP a mockar; {@code RECAPTCHA_SECRET_KEY} é forçada
 * vazia para exercitar o caminho determinístico (sem chamar a API do Google).</p>
 *
 * <p>PR #79 (issue #78): {@code SecurityConfig} deixou de liberar {@code /bff/**} por inteiro — só a
 * lista explícita de rotas públicas continua sem token, o resto exige
 * {@code Authorization: Bearer <jwt>}. O fixture {@link #createUser(String)} passou a criar o
 * usuário direto no {@link FakeIdentityServer} (não HTTP) porque {@code POST /bff/users} agora
 * exige auth e a maioria dos testes usa esse fixture só para ter um usuário/token disponível
 * antes de exercitar a rota que o teste realmente cobre; o contrato HTTP de
 * {@code POST /bff/users} continua coberto por {@link #createUser_returns201WithUserId()}.</p>
 *
 * <p>Issue #35 (fatia 3): o catalog é outro serviço; aqui as chamadas de evento vão ao {@code FakeCatalogServer} (HTTP de
 * verdade, mesmo contrato do serviço) pelo {@code CatalogClient} real, como as de reserva vão ao {@code FakeReservationServer}.
 * {@code POST /bff/seed} só existe com {@code seed.enabled=true} (ligado aqui; desligado = 404, em {@code SeedDesligadoTest}).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
@TestPropertySource(properties = {"RECAPTCHA_SECRET_KEY=", "seed.enabled=true",
        // Fixa a lista de CORS (issue #133) para os testes não dependerem do CORS_ALLOWED_ORIGINS do .env
        "CORS_ALLOWED_ORIGINS=http://localhost:8088,http://localhost:3000"})
public class HttpContractTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    /** O {@code IdentityClient} do gateway, contra o {@code FakeIdentityServer} (issue #36: o identity é outro serviço). */
    @Autowired
    private UserApi userApi;

    private FakeIdentityServer identity() {
        return FakeIdentityServer.get();
    }

    /** O {@code CatalogClient} do gateway, contra o {@code FakeCatalogServer} (issue #35: o catalog é outro serviço). */
    @Autowired
    private EventApi eventService;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ======================= fixtures =======================

    /**
     * Cria usuário direto no {@link FakeIdentityServer} (não HTTP): desde o PR #79 (issue #78)
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
        User created = identity().criarUsuario(u);
        assertNotNull(created.getId());
        return created;
    }

    /** Igual {@link #createUser(String)}, mas promove o usuário a host via SQL direto —
     * não existe (de propósito) rota HTTP que promova host, para não abrir
     * auto-promoção via {@code PUT /bff/users/{id}}. */
    private User createHostUser(String tag) {
        User created = createUser(tag);
        identity().promoverHost(created.getId()); // equivale ao UPDATE users SET host = true de antes
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

        // Issue #166: id que não é o do token é 403 mesmo quando não existe (a negação não revela se o outro usuário existe)
        ResponseEntity<String> outroId = restTemplate.exchange(
                url("/bff/users/999999999"), HttpMethod.GET, authed, String.class);
        assertEquals(HttpStatus.FORBIDDEN, outroId.getStatusCode());
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

    /**
     * Mudança de contrato na #171: o corpo do {@code PUT /bff/users/{id}} continua aceitando {@code password} (200, sem 400), mas o campo é
     * IGNORADO: a senha só muda por {@code /password} e {@code /password-with-mfa}. A resposta não devolve senha nem hash.
     */
    @Test
    public void updateUser_passwordNoCorpoEIgnoradoELoginAntigoSegueValendo() {
        User created = createUser("update-ignora-senha");
        String token = loginAndGetToken(created.getEmail(), "senha123");
        created.setName("Nome Sem Trocar Senha");
        created.setPassword("senhaNovaNoPut1");

        ResponseEntity<User> resp = restTemplate.exchange(
                url("/bff/users/" + created.getId()), HttpMethod.PUT, new HttpEntity<>(created, authHeaders(token)), User.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("Nome Sem Trocar Senha", resp.getBody().getName(), "o resto do PUT vale");
        assertNull(resp.getBody().getPassword(), "a resposta não devolve a senha enviada nem o hash");
        assertNotNull(loginAndGetToken(created.getEmail(), "senha123"), "a senha antiga segue valendo");
        Map<String, String> creds = new HashMap<>();
        creds.put("email", created.getEmail());
        creds.put("password", "senhaNovaNoPut1");
        assertEquals(HttpStatus.UNAUTHORIZED,
                restTemplate.postForEntity(url("/bff/users/login"), creds, String.class).getStatusCode(),
                "a senha enviada no PUT não vira a senha da conta");
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

        // código só existe no banco (vai por e-mail, enviado pelo serviço notification) — lido aqui só para
        // montar o fixture do passo seguinte, não para testar o envio de e-mail em si.
        String code = identity().codigoPendente(email);
        assertNotNull(code, "cadastro pendente no identity");

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

        // O código MFA é enviado só por e-mail (pelo serviço notification); geramos um novo direto pelo
        // service (mesmo autowired usado pelo controller) só para montar o fixture do
        // passo seguinte — não estamos testando a geração do código aqui.
        String mfaCode = identity().gerarMfa(user.getId());

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

        String mfaCode2 = identity().gerarMfa(user.getId());
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

    // ======================= /bff/events: dono, creator_id e creator_name (issue #35, fatia 1) =======================

    /** GET cru (Map): {@code creator_name} é somente-leitura no DTO e não volta num {@code Event} desserializado. */
    private Map<String, Object> getEventJson(Long eventId) {
        ResponseEntity<Map> resp = restTemplate.getForEntity(url("/bff/events/" + eventId), Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        return resp.getBody();
    }

    /** Caso 1 do spec: host B (não dono) se autopromove a admin e mesmo assim não edita o evento de A (sem exceção de admin). */
    @Test
    public void updateEvent_hostNaoDono_mesmoComIsAdmin_returns403() {
        User a = createHostUser("owner-a-case1");
        String tokenA = loginAndGetToken(a.getEmail(), "senha123");
        Event doA = createEvent(a.getId(), "Evento de A caso1 " + System.nanoTime(), 5, tokenA);
        User b = createHostUser("intruso-b-case1");
        String tokenB = loginAndGetToken(b.getEmail(), "senha123");

        b.setIsAdmin(true);
        b.setPassword(null); // preserva a senha gravada
        ResponseEntity<User> promovido = restTemplate.exchange(url("/bff/users/" + b.getId()), HttpMethod.PUT,
                new HttpEntity<>(b, authHeaders(tokenB)), User.class);
        assertEquals(HttpStatus.OK, promovido.getStatusCode());

        doA.setDescription("editado por B");
        ResponseEntity<String> resp = restTemplate.exchange(url("/bff/events/" + doA.getEvent_id()), HttpMethod.PUT,
                new HttpEntity<>(doA, authHeaders(tokenB)), String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertEquals("desc", getEventJson(doA.getEvent_id()).get("description"), "nada foi gravado");
    }

    /** Caso 2 do spec: o dono manda creator_id de outro no PUT; o creator_id do corpo é ignorado (vale o gravado). */
    @Test
    public void updateEvent_donoComCreatorIdDeOutro_mantemOGravado() {
        User a = createHostUser("owner-a-case2");
        String tokenA = loginAndGetToken(a.getEmail(), "senha123");
        User b = createUser("outro-b-case2");
        Event e = createEvent(a.getId(), "Evento caso2 " + System.nanoTime(), 5, tokenA);

        e.setCreator_id(b.getId());
        e.setDescription("dono editou");
        ResponseEntity<Map> resp = restTemplate.exchange(url("/bff/events/" + e.getEvent_id()), HttpMethod.PUT,
                new HttpEntity<>(e, authHeaders(tokenA)), Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Map<String, Object> lido = getEventJson(e.getEvent_id());
        assertEquals(a.getId(), ((Number) lido.get("creator_id")).longValue());
        assertEquals("dono editou", lido.get("description"));
    }

    /** Caso 3 do spec: POST com creator_id de outro usuário -> o evento nasce com o creator_id do token. */
    @Test
    public void createEvent_creatorIdDeOutro_nasceComODoToken() {
        User host = createHostUser("host-case3");
        String token = loginAndGetToken(host.getEmail(), "senha123");
        User outro = createUser("outro-case3");

        Event criado = createEvent(outro.getId(), "Evento caso3 " + System.nanoTime(), 5, token);

        assertEquals(host.getId(), criado.getCreator_id());
        Map<String, Object> lido = getEventJson(criado.getEvent_id());
        assertEquals(host.getId(), ((Number) lido.get("creator_id")).longValue());
        assertEquals(host.getName(), lido.get("creator_name"), "creator_name composto no gateway");
    }

    /** Caso 5 do spec: conta do criador desativada -> creator_name nulo (findNames só devolve isactive=true). */
    @Test
    public void getEvent_criadorDesativado_creatorNameNulo() {
        User criador = createHostUser("desativado-case5");
        Event e = new Event();
        e.setCreator_id(criador.getId());
        e.setEvent_name("Evento caso5 " + System.nanoTime());
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        e.setLot_quantity(5);
        e.setDescription("desc");
        // direto pelo service: nenhuma resposta do gateway compôs (nem cacheou) o nome antes da desativação
        Event criado = eventService.createEvent(e);
        userApi.deactivateUser(criador.getId());

        Map<String, Object> lido = getEventJson(criado.getEvent_id());
        assertTrue(lido.containsKey("creator_name"), "o campo existe no contrato");
        assertEquals(null, lido.get("creator_name"));
        assertEquals(criador.getId(), ((Number) lido.get("creator_id")).longValue());

        // sem cache negativo: reativada a conta, o próximo GET já traz o nome
        identity().reativar(criador.getId());
        assertEquals(criador.getName(), getEventJson(criado.getEvent_id()).get("creator_name"));
    }

    /** Caso 6 (parte da fatia 1) do spec: DELETE de id inexistente como host -> 404 (era 204). */
    @Test
    public void deleteEvent_idInexistente_returns404() {
        User host = createHostUser("host-delete-404");
        String token = loginAndGetToken(host.getEmail(), "senha123");
        ResponseEntity<String> resp = restTemplate.exchange(url("/bff/events/999999"), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(token)), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    /** DELETE e upload de imagem também exigem dono: outro host -> 403, e o evento continua lá. */
    @Test
    public void deleteEUploadImagem_naoDono_returns403() {
        User a = createHostUser("owner-a-del403");
        String tokenA = loginAndGetToken(a.getEmail(), "senha123");
        Event e = createEvent(a.getId(), "Evento del403 " + System.nanoTime(), 5, tokenA);
        User b = createHostUser("intruso-b-del403");
        String tokenB = loginAndGetToken(b.getEmail(), "senha123");

        ResponseEntity<String> del = restTemplate.exchange(url("/bff/events/" + e.getEvent_id()), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(tokenB)), String.class);
        assertEquals(HttpStatus.FORBIDDEN, del.getStatusCode());

        MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
        multipart.add("image", new org.springframework.core.io.ByteArrayResource(new byte[] {1, 2, 3}) {
            @Override
            public String getFilename() {
                return "foto.png";
            }
        });
        HttpHeaders headers = authHeaders(tokenB);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> upload = restTemplate.postForEntity(url("/bff/events/" + e.getEvent_id() + "/image"),
                new HttpEntity<>(multipart, headers), String.class);
        assertEquals(HttpStatus.FORBIDDEN, upload.getStatusCode());

        assertEquals(HttpStatus.OK, restTemplate.getForEntity(url("/bff/events/" + e.getEvent_id()), String.class)
                .getStatusCode());
    }

    /** image_data sai das listas (busca e por criador); continua no detalhe e em /image. */
    @Test
    public void listasSemImageData_imagemSoNoDetalheENoEndpointDaImagem() {
        User host = createHostUser("host-img-listas");
        String token = loginAndGetToken(host.getEmail(), "senha123");
        Event e = createEvent(host.getId(), "Evento ImgListas " + System.nanoTime(), 5, token);
        eventService.saveImage(e.getEvent_id(), new byte[] {7, 7, 7});

        ResponseEntity<Map[]> busca = restTemplate.getForEntity(
                url("/bff/events/search?term=" + e.getEvent_name()), Map[].class);
        assertEquals(1, busca.getBody().length);
        assertEquals(null, busca.getBody()[0].get("image_data"), "busca sem image_data");
        assertEquals(host.getName(), busca.getBody()[0].get("creator_name"));
        ResponseEntity<Map[]> doCriador = restTemplate.getForEntity(
                url("/bff/events?creator_id=" + host.getId()), Map[].class);
        assertEquals(1, doCriador.getBody().length);
        assertEquals(null, doCriador.getBody()[0].get("image_data"), "lista do criador sem image_data");

        assertNotNull(getEventJson(e.getEvent_id()).get("image_data"), "o detalhe continua com a imagem");
        ResponseEntity<byte[]> imagem = restTemplate.getForEntity(
                url("/bff/events/" + e.getEvent_id() + "/image"), byte[].class);
        assertEquals(HttpStatus.OK, imagem.getStatusCode());
        assertEquals(3, imagem.getBody().length);
    }

    @Test
    public void updateEvent_nonExistentId_returns404() {
        // Issue #86: PUT num event_id inexistente hoje devolve 200 (falso-positivo) porque
        // EventService.updateEvent nunca confere se a linha existe antes de gravar. Deletamos
        // o evento recém-criado pra garantir um id que definitivamente não existe mais.
        User creator = createHostUser("event-update-404");
        String token = loginAndGetToken(creator.getEmail(), "senha123");
        Event event = createEvent(creator.getId(), "Evento Para Atualizar 404 " + System.nanoTime(), 5, token);
        Long eventId = event.getEvent_id();

        HttpEntity<Void> deleteEntity = new HttpEntity<>(authHeaders(token));
        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                url("/bff/events/" + eventId), HttpMethod.DELETE, deleteEntity, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResp.getStatusCode());

        event.setDescription("tentativa de atualizar evento inexistente");
        HttpEntity<Event> updateEntity = new HttpEntity<>(event, authHeaders(token));
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/bff/events/" + eventId), HttpMethod.PUT, updateEntity, String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());

        // Nenhuma linha foi recriada pelo PUT: GET no mesmo id continua 404.
        ResponseEntity<String> getResp = restTemplate.getForEntity(url("/bff/events/" + eventId), String.class);
        assertEquals(HttpStatus.NOT_FOUND, getResp.getStatusCode());
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
        //
        // Issue #133: a origem é a do Flutter (:8088, default de CORS_ALLOWED_ORIGINS); com o
        // bean "*" removido, uma origem qualquer deixou de ser aceita.
        ResponseEntity<String> resp = preflight("http://localhost:8088", "/bff/my-wallets/1");

        assertTrue(!resp.getStatusCode().equals(HttpStatus.UNAUTHORIZED),
                "preflight numa rota protegida não deve ser barrado por auth, veio " + resp.getStatusCode());
        assertEquals("http://localhost:8088", resp.getHeaders().getFirst("Access-Control-Allow-Origin"));
        assertEquals("true", resp.getHeaders().getFirst("Access-Control-Allow-Credentials"));
    }

    @Test
    public void corsPreflight_origemNegada_naoRecebeAllowOrigin() {
        // Issue #133: origem fora de CORS_ALLOWED_ORIGINS. Conferir só o status não basta (o Spring
        // responde 403 "Invalid CORS request"); o que protege é o header estar AUSENTE.
        ResponseEntity<String> resp = preflight("http://localhost:9999", "/bff/my-wallets/1");

        assertNull(resp.getHeaders().getFirst("Access-Control-Allow-Origin"),
                "origem negada não pode receber Access-Control-Allow-Origin");
        assertNull(resp.getHeaders().getFirst("Access-Control-Allow-Credentials"));
    }

    @Test
    public void cors_respostaReal_expoeAuthorizationContentTypeEIdempotencyKey() {
        // Issue #133: exposedHeaders vêm da config única. Rota protegida sem token = 401, e o
        // CorsFilter (que roda antes da autorização) ainda anexa os headers de CORS à resposta.
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "http://localhost:8088");

        ResponseEntity<String> resp = restTemplate.exchange(
                url("/bff/my-wallets/1"), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("http://localhost:8088", resp.getHeaders().getFirst("Access-Control-Allow-Origin"));
        String expostos = String.join(",", resp.getHeaders().getOrEmpty("Access-Control-Expose-Headers")).toLowerCase();
        assertTrue(expostos.contains("authorization") && expostos.contains("content-type")
                && expostos.contains("idempotency-key"), "Access-Control-Expose-Headers = " + expostos);
    }

    private ResponseEntity<String> preflight(String origem, String caminho) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", origem);
        headers.set("Access-Control-Request-Method", "DELETE");
        headers.set("Access-Control-Request-Headers", "authorization,content-type");
        return restTemplate.exchange(url(caminho), HttpMethod.OPTIONS, new HttpEntity<Void>(headers), String.class);
    }

    // ======================= /actuator (issue #92) =======================

    @Test
    public void actuatorHealth_semToken_returns200Up() {
        ResponseEntity<Map> resp = restTemplate.getForEntity(url("/actuator/health"), Map.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("UP", resp.getBody().get("status"));
        assertEquals(1, resp.getBody().size(), "health público não deve expor detalhes (components)");
    }

    @Test
    public void actuatorOutrosEndpoints_semToken_naoExpoemDados() {
        // Só `health` está exposto (management.endpoints.web.exposure.include=health).
        for (String path : new String[] {"/actuator/env", "/actuator/beans", "/actuator/metrics"}) {
            HttpStatus status = HttpStatus.valueOf(restTemplate.getForEntity(url(path), String.class)
                    .getStatusCode().value());
            assertTrue(status == HttpStatus.NOT_FOUND || status == HttpStatus.UNAUTHORIZED,
                    path + " não deve devolver dados sem token, veio " + status);
        }
    }

    // ======================= catalog fora (issue #35, caso 6 do spec) =======================

    /**
     * Caso 6 do spec: com o serviço catalog fora, {@code GET /bff/events/{id}} é 503 (não 404), a reserva falha com 503 e NUNCA
     * com "Evento não encontrado" (o {@code exists} não vira {@code false}), a busca é 503 e criar evento é 503 "resultado
     * incerto". O corpo é genérico: não ecoa host/URL interna nem a mensagem da exceção do cliente.
     */
    @Test
    public void catalogFora_respostas503Genericas_nuncaNaoEncontrado() {
        User host = createHostUser("host-catalog-fora");
        String hostToken = loginAndGetToken(host.getEmail(), "senha123");
        User attendee = createUser("attendee-catalog-fora");
        String token = loginAndGetToken(attendee.getEmail(), "senha123");
        long nuncaConferido = 1_800_000_000L + (System.nanoTime() % 100_000_000L); // fora do cache positivo de exists
        FakeCatalogServer catalog = FakeCatalogServer.get();
        catalog.foraDoAr(true);
        try {
            ResponseEntity<String> get = restTemplate.getForEntity(url("/bff/events/1"), String.class);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, get.getStatusCode());
            assertCorpoGenerico(get.getBody());

            ResponseEntity<String> reserva = restTemplate.exchange(url("/bff/event-wallets"), HttpMethod.POST,
                    new HttpEntity<>(new EventWallet(attendee.getId(), nuncaConferido), authHeaders(token)), String.class);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, reserva.getStatusCode());
            assertFalse(String.valueOf(reserva.getBody()).contains("não encontrado"), "veio: " + reserva.getBody());
            assertCorpoGenerico(reserva.getBody());

            ResponseEntity<String> validar = restTemplate.exchange(url("/bff/event-wallets/validate"), HttpMethod.POST,
                    new HttpEntity<>(new EventWallet(attendee.getId(), nuncaConferido), authHeaders(token)), String.class);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, validar.getStatusCode());

            ResponseEntity<String> busca = restTemplate.getForEntity(url("/bff/events/search?term=x"), String.class);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, busca.getStatusCode());

            Event novo = new Event();
            novo.setEvent_name("Evento catalog fora " + System.nanoTime());
            novo.setIs_EAD(true);
            novo.setEvent_date(LocalDateTime.now().plusDays(5));
            novo.setLot_quantity(5);
            novo.setPresenters(new ArrayList<>());
            ResponseEntity<String> criar = restTemplate.exchange(url("/bff/events"), HttpMethod.POST,
                    new HttpEntity<>(novo, authHeaders(hostToken)), String.class);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, criar.getStatusCode());
            assertTrue(criar.getBody().contains("resultado incerto") && criar.getBody().contains("Meus eventos"),
                    "veio: " + criar.getBody());
            assertCorpoGenerico(criar.getBody());

            ResponseEntity<String> apagar = restTemplate.exchange(url("/bff/events/1"), HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(hostToken)), String.class);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, apagar.getStatusCode(), "dono indeterminado: nem 404 nem 403");
        } finally {
            catalog.foraDoAr(false);
        }
    }

    private static void assertCorpoGenerico(String corpo) {
        String c = String.valueOf(corpo);
        assertFalse(c.contains("http") || c.contains("127.0.0.1") || c.contains("Serviço catalog") || c.contains("/internal"),
                "o 503 não pode ecoar a mensagem interna da exceção, veio: " + corpo);
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

    /**
     * Issue #57 (IDOR): o usuário vem do token, nunca do corpo/query/path. O token do usuário A com {@code userId} do B
     * (corpo, query ou path) dá 403 nos 5 endpoints e não mexe na inscrição do B; o 403 vale mesmo com o B inexistente.
     * Mudança de contrato registrada aqui: antes a requisição passava com o userId que o cliente mandasse.
     */
    @Test
    public void eventWallet_tokenDeAnaoAgeEmNomeDeBeto_returns403NosEndpoints() {
        User host = createHostUser("idor-host");
        String hostToken = loginAndGetToken(host.getEmail(), "senha123");
        User ana = createUser("idor-ana");
        String anaToken = loginAndGetToken(ana.getEmail(), "senha123");
        User beto = createUser("idor-beto");
        String betoToken = loginAndGetToken(beto.getEmail(), "senha123");
        Event event = createEvent(host.getId(), "Evento IDOR " + System.nanoTime(), 5, hostToken);
        long eventId = event.getEvent_id();

        // o Beto se inscreve (pending) e a Ana tenta agir em nome dele
        EventWallet doBeto = new EventWallet(beto.getId(), eventId);
        assertEquals(HttpStatus.CREATED, restTemplate.exchange(url("/bff/event-wallets"), HttpMethod.POST,
                new HttpEntity<>(doBeto, authHeaders(betoToken)), String.class).getStatusCode());

        HttpEntity<EventWallet> comoAna = new HttpEntity<>(doBeto, authHeaders(anaToken));
        assertEquals(HttpStatus.FORBIDDEN, restTemplate.exchange(url("/bff/event-wallets"), HttpMethod.POST, comoAna,
                String.class).getStatusCode(), "addLink");
        assertEquals(HttpStatus.FORBIDDEN, restTemplate.exchange(url("/bff/event-wallets/confirm"), HttpMethod.POST,
                comoAna, String.class).getStatusCode(), "confirm");
        assertEquals(HttpStatus.FORBIDDEN, restTemplate.exchange(url("/bff/event-wallets/validate"), HttpMethod.POST,
                comoAna, String.class).getStatusCode(), "validate");
        assertEquals(HttpStatus.FORBIDDEN, restTemplate.exchange(
                url("/bff/event-wallets?userId=" + beto.getId() + "&eventId=" + eventId), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(anaToken)), String.class).getStatusCode(), "removeLink");
        assertEquals(HttpStatus.FORBIDDEN, restTemplate.exchange(url("/bff/event-wallets/user/" + beto.getId()),
                HttpMethod.GET, new HttpEntity<>(authHeaders(anaToken)), String.class).getStatusCode(), "listByUser");
        // userId inexistente: continua 403 (a negação não depende do alvo existir)
        assertEquals(HttpStatus.FORBIDDEN, restTemplate.exchange(url("/bff/event-wallets/user/999999999"),
                HttpMethod.GET, new HttpEntity<>(authHeaders(anaToken)), String.class).getStatusCode());

        // nada mudou para o Beto: segue pending (não confirmou nem cancelou) e a Ana não ganhou inscrição
        ResponseEntity<EventWallet[]> listaBeto = restTemplate.exchange(url("/bff/event-wallets/user/" + beto.getId()),
                HttpMethod.GET, new HttpEntity<>(authHeaders(betoToken)), EventWallet[].class);
        assertEquals(1, listaBeto.getBody().length);
        assertEquals("pending", listaBeto.getBody()[0].getStatus());
        ResponseEntity<EventWallet[]> listaAna = restTemplate.exchange(url("/bff/event-wallets/user/" + ana.getId()),
                HttpMethod.GET, new HttpEntity<>(authHeaders(anaToken)), EventWallet[].class);
        assertEquals(0, listaAna.getBody().length);
    }

    /** Issue #57: o próprio usuário segue funcionando com o userId (igual ao do token) e sem ele (o do token). */
    @Test
    public void eventWallet_proprioUsuario_comESemUserId() {
        User host = createHostUser("idor-ok-host");
        String hostToken = loginAndGetToken(host.getEmail(), "senha123");
        User ana = createUser("idor-ok-ana");
        String token = loginAndGetToken(ana.getEmail(), "senha123");
        Event e1 = createEvent(host.getId(), "Evento IDOR ok 1 " + System.nanoTime(), 5, hostToken);
        Event e2 = createEvent(host.getId(), "Evento IDOR ok 2 " + System.nanoTime(), 5, hostToken);
        HttpHeaders h = authHeaders(token);

        // sem userId no corpo: vale o do token, e a resposta traz o dono real
        Map<String, Object> semUserId = Map.of("eventId", e1.getEvent_id());
        assertEquals(HttpStatus.OK, restTemplate.exchange(url("/bff/event-wallets/validate"), HttpMethod.POST,
                new HttpEntity<>(semUserId, h), String.class).getStatusCode());
        ResponseEntity<EventWallet> criada = restTemplate.exchange(url("/bff/event-wallets"), HttpMethod.POST,
                new HttpEntity<>(semUserId, h), EventWallet.class);
        assertEquals(HttpStatus.CREATED, criada.getStatusCode());
        assertEquals(ana.getId(), criada.getBody().getUserId());
        ResponseEntity<EventWallet> confirmada = restTemplate.exchange(url("/bff/event-wallets/confirm"),
                HttpMethod.POST, new HttpEntity<>(semUserId, h), EventWallet.class);
        assertEquals(HttpStatus.OK, confirmada.getStatusCode());
        assertEquals("confirmed", confirmada.getBody().getStatus());
        assertEquals(HttpStatus.OK, restTemplate.exchange(url("/bff/event-wallets?eventId=" + e1.getEvent_id()),
                HttpMethod.DELETE, new HttpEntity<>(h), Void.class).getStatusCode());

        // com userId igual ao do token: segue como antes
        EventWallet proprio = new EventWallet(ana.getId(), e2.getEvent_id());
        assertEquals(HttpStatus.CREATED, restTemplate.exchange(url("/bff/event-wallets"), HttpMethod.POST,
                new HttpEntity<>(proprio, h), String.class).getStatusCode());
        assertEquals(HttpStatus.OK, restTemplate.exchange(url("/bff/event-wallets?userId=" + ana.getId()
                + "&eventId=" + e2.getEvent_id()), HttpMethod.DELETE, new HttpEntity<>(h), Void.class).getStatusCode());
        assertEquals(HttpStatus.OK, restTemplate.exchange(url("/bff/event-wallets/user/" + ana.getId()),
                HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode());
    }

    /** Issue #57 (regressão): sem token, 401 nos 4 endpoints (e no validate), antes de qualquer lógica do controller. */
    @Test
    public void eventWallet_semToken_returns401NosEndpoints() {
        HttpHeaders semAuth = new HttpHeaders();
        semAuth.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<EventWallet> corpo = new HttpEntity<>(new EventWallet(1L, 1L), semAuth);
        assertEquals(HttpStatus.UNAUTHORIZED, restTemplate.exchange(url("/bff/event-wallets"), HttpMethod.POST, corpo,
                String.class).getStatusCode(), "addLink");
        assertEquals(HttpStatus.UNAUTHORIZED, restTemplate.exchange(url("/bff/event-wallets/confirm"), HttpMethod.POST,
                corpo, String.class).getStatusCode(), "confirm");
        assertEquals(HttpStatus.UNAUTHORIZED, restTemplate.exchange(url("/bff/event-wallets/validate"), HttpMethod.POST,
                corpo, String.class).getStatusCode(), "validate");
        assertEquals(HttpStatus.UNAUTHORIZED, restTemplate.exchange(url("/bff/event-wallets?userId=1&eventId=1"),
                HttpMethod.DELETE, new HttpEntity<>(semAuth), String.class).getStatusCode(), "removeLink");
        assertEquals(HttpStatus.UNAUTHORIZED, restTemplate.exchange(url("/bff/event-wallets/user/1"), HttpMethod.GET,
                new HttpEntity<>(semAuth), String.class).getStatusCode(), "listByUser");
    }

    /**
     * Issue #24: {@code Idempotency-Key} é aditiva. Sem o header o servidor gera uma e devolve no header
     * da resposta (corpo igual ao de sempre); com o mesmo header a repetição devolve 201 e o mesmo corpo
     * sem reservar de novo; a mesma chave com outro evento dá 422.
     */
    @Test
    public void eventWalletAddLink_idempotencyKey() {
        User creator = createHostUser("idem-http-creator");
        String hostToken = loginAndGetToken(creator.getEmail(), "senha123");
        User attendee = createUser("idem-http-attendee");
        String token = loginAndGetToken(attendee.getEmail(), "senha123");
        Event e1 = createEvent(creator.getId(), "Evento Idem 1 " + System.nanoTime(), 5, hostToken);
        Event e2 = createEvent(creator.getId(), "Evento Idem 2 " + System.nanoTime(), 5, hostToken);

        // sem header: o servidor gera e devolve
        User outro = createUser("idem-http-outro");
        ResponseEntity<EventWallet> semHeader = restTemplate.exchange(url("/bff/event-wallets"), HttpMethod.POST,
                new HttpEntity<>(new EventWallet(outro.getId(), e1.getEvent_id()),
                        authHeaders(loginAndGetToken(outro.getEmail(), "senha123"))), EventWallet.class);
        assertEquals(HttpStatus.CREATED, semHeader.getStatusCode());
        assertNotNull(semHeader.getHeaders().getFirst("Idempotency-Key"), "chave gerada devolvida no header");
        assertEquals("pending", semHeader.getBody().getStatus());

        // com header: repetição devolve o mesmo resultado
        HttpHeaders h = authHeaders(token);
        h.set("Idempotency-Key", "http-chave-" + System.nanoTime());
        HttpEntity<EventWallet> req = new HttpEntity<>(new EventWallet(attendee.getId(), e1.getEvent_id()), h);
        ResponseEntity<EventWallet> primeira = restTemplate.exchange(
                url("/bff/event-wallets"), HttpMethod.POST, req, EventWallet.class);
        ResponseEntity<EventWallet> repetida = restTemplate.exchange(
                url("/bff/event-wallets"), HttpMethod.POST, req, EventWallet.class);
        // o desserializador de EventWallet ignora campo desconhecido e mascararia um vazamento no JSON:
        // confere o corpo cru (a chave só vai no header)
        ResponseEntity<String> repetidaCrua = restTemplate.exchange(
                url("/bff/event-wallets"), HttpMethod.POST, req, String.class);
        assertEquals(HttpStatus.CREATED, repetidaCrua.getStatusCode());
        assertFalse(repetidaCrua.getBody().toLowerCase().contains("idempotency"),
                "corpo não deve conter a chave, veio: " + repetidaCrua.getBody());
        assertEquals(HttpStatus.CREATED, primeira.getStatusCode());
        assertEquals(HttpStatus.CREATED, repetida.getStatusCode());
        assertEquals(h.getFirst("Idempotency-Key"), repetida.getHeaders().getFirst("Idempotency-Key"));
        assertEquals(primeira.getBody().getExpiresAt(), repetida.getBody().getExpiresAt());

        // mesma chave, outro evento: 422
        ResponseEntity<String> conflito = restTemplate.exchange(url("/bff/event-wallets"), HttpMethod.POST,
                new HttpEntity<>(new EventWallet(attendee.getId(), e2.getEvent_id()), h), String.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, conflito.getStatusCode());
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

    // ======================= IDOR em /bff/users/{id} e /bff/my-wallets/{userId} (issue #166) =======================

    private static final String NEGADO_BODY = "Acesso negado: o usuário informado não é o usuário autenticado";

    private ResponseEntity<String> chamar(HttpMethod metodo, String caminho, String token, Object corpo) {
        return restTemplate.exchange(url(caminho), metodo, new HttpEntity<>(corpo, authHeaders(token)), String.class);
    }

    /**
     * O token de A não lê nem altera nada de B em NENHUMA rota com id de usuário no path: 403 com o mesmo corpo da #57, a negação
     * vem ANTES de qualquer chamada ao identity (nenhuma chamada interna com o id de B) e o estado de B fica intacto. A própria
     * conta segue funcionando nas mesmas rotas.
     */
    @Test
    public void usuarioA_naoLeNemAlteraNadaDoUsuarioB_emUsersEMyWallets() {
        User a = createUser("idor-a");
        User b = createUser("idor-b");
        String tokenA = loginAndGetToken(a.getEmail(), "senha123");
        String tokenB = loginAndGetToken(b.getEmail(), "senha123");
        String ub = "/bff/users/" + b.getId();
        String idB = String.valueOf(b.getId());

        User alterado = copiaSemSenha(b);
        alterado.setName("Invasor altera");
        Map<String, String> senha = Map.of("oldPassword", "senha123", "newPassword", "invasora99");
        Map<String, String> mfaCodigo = Map.of("mfaCode", "123456");
        Map<String, String> mfaSenha = Map.of("mfaCode", "123456", "newPassword", "invasora99");

        record Rota(HttpMethod metodo, String caminho, Object corpo) { }
        List<Rota> rotas = List.of(
                new Rota(HttpMethod.GET, ub, null),
                new Rota(HttpMethod.PUT, ub, alterado),
                new Rota(HttpMethod.PUT, ub + "/password", senha),
                new Rota(HttpMethod.DELETE, ub, null),
                new Rota(HttpMethod.POST, ub + "/request-password-reset-mfa", null),
                new Rota(HttpMethod.POST, ub + "/verify-password-reset-mfa", mfaCodigo),
                new Rota(HttpMethod.PUT, ub + "/password-with-mfa", mfaSenha),
                new Rota(HttpMethod.GET, "/bff/my-wallets/" + idB, null),
                new Rota(HttpMethod.POST, "/bff/my-wallets/ensure/" + idB, null));
        int antes = identity().chamadas().size();
        for (Rota r : rotas) {
            ResponseEntity<String> resp = chamar(r.metodo(), r.caminho(), tokenA, r.corpo());
            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(), r.metodo() + " " + r.caminho());
            assertEquals(NEGADO_BODY, resp.getBody(), r.metodo() + " " + r.caminho());
        }
        assertEquals(antes, identity().chamadas().size(), "a negação vem antes de qualquer chamada ao identity");

        // B intacto: nome igual, ativo (o login do B continua valendo, com a senha antiga) e a senha não mudou
        ResponseEntity<User> deB = restTemplate.exchange(url(ub), HttpMethod.GET, new HttpEntity<>(authHeaders(tokenB)), User.class);
        assertEquals(HttpStatus.OK, deB.getStatusCode());
        assertEquals(b.getName(), deB.getBody().getName());
        assertEquals(HttpStatus.OK, restTemplate.postForEntity(url("/bff/users/login"),
                Map.of("email", b.getEmail(), "password", "senha123"), String.class).getStatusCode());

        // o próprio A segue funcionando nas rotas com id (só as que não mudam estado que outros testes cobrem)
        String ua = "/bff/users/" + a.getId();
        assertEquals(HttpStatus.OK, chamar(HttpMethod.GET, ua, tokenA, null).getStatusCode());
        assertEquals(HttpStatus.OK, chamar(HttpMethod.GET, "/bff/my-wallets/" + a.getId(), tokenA, null).getStatusCode());
        assertEquals(HttpStatus.OK, chamar(HttpMethod.POST, "/bff/my-wallets/ensure/" + a.getId(), tokenA, null).getStatusCode());
    }

    private User copiaSemSenha(User origem) {
        User c = new User();
        c.setName(origem.getName());
        c.setEmail(origem.getEmail());
        c.setFone(origem.getFone());
        c.setBirthDate(origem.getBirthDate());
        return c;
    }

    /** {@code PUT /bff/users/{id}} com e-mail de outra conta: 400 sem diferenciar maiúsculas (a regra real é do identity). */
    @Test
    public void putUsuario_emailDeOutraConta_returns400() {
        User a = createUser("dup-a");
        User b = createUser("dup-b");
        String tokenB = loginAndGetToken(b.getEmail(), "senha123");

        User alterado = copiaSemSenha(b);
        alterado.setEmail(a.getEmail().toUpperCase());
        ResponseEntity<String> resp = chamar(HttpMethod.PUT, "/bff/users/" + b.getId(), tokenB, alterado);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());

        alterado.setEmail(b.getEmail().toUpperCase());
        assertEquals(HttpStatus.OK, chamar(HttpMethod.PUT, "/bff/users/" + b.getId(), tokenB, alterado).getStatusCode(),
                "o próprio e-mail, com outra caixa, segue");
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

    /** O gateway valida o criador (UserApi.exists): no seed, creator_id inexistente vira aviso (200), não erro (207). */
    @Test
    public void seed_eventoComCreatorInexistente_viraAvisoENaoCria() {
        Map<String, Object> evento = new HashMap<>();
        evento.put("creator_id", 987_654_321L);
        evento.put("event_name", "Seed criador inexistente " + System.nanoTime());
        evento.put("is_EAD", true);
        evento.put("event_date", LocalDateTime.now().plusDays(5).toString());
        evento.put("lot_quantity", 5);
        evento.put("description", "desc");
        ResponseEntity<Map> resp = restTemplate.postForEntity(url("/bff/seed"),
                Map.of("events", java.util.List.of(evento)), Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, ((Number) resp.getBody().get("eventsCreated")).intValue());
        assertTrue(resp.getBody().get("warnings").toString().contains("creator_id não existe"),
                "veio: " + resp.getBody());
    }

    /** /validate: a checagem de existência do criador agora é do gateway, com a mesma mensagem de antes. */
    @Test
    public void validateEvent_creatorInexistente_returns400ComAMensagemDeAntes() {
        User caller = createUser("validate-creator-404");
        String token = loginAndGetToken(caller.getEmail(), "senha123");
        Event e = new Event();
        e.setCreator_id(987_654_321L);
        e.setEvent_name("Evento validate criador " + System.nanoTime());
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(5));
        e.setLot_quantity(5);
        e.setPresenters(new ArrayList<>());
        ResponseEntity<String> resp = restTemplate.exchange(url("/bff/events/validate"), HttpMethod.POST,
                new HttpEntity<>(e, authHeaders(token)), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().contains("creator_id não existe em users."), "veio: " + resp.getBody());
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

    // ======================= reCAPTCHA delegado ao identity (issue #36) =======================

    @Test
    public void verifyRecaptcha_scoreAlto_returns200ComSuccessScoreEAction() {
        identity().recaptcha(new RecaptchaResponse(200, Map.of("success", true, "score", 0.9, "action", "login")));
        ResponseEntity<Map> resp = restTemplate.postForEntity(url("/bff/users/verify-recaptcha"),
                Map.of("token", "t", "action", "login"), Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(Boolean.TRUE, resp.getBody().get("success"));
        assertEquals("login", resp.getBody().get("action"));
        assertNotNull(resp.getBody().get("score"));
    }

    // ======================= JWT RS256 validado pelo JWKS do identity (issue #36) =======================

    private ResponseEntity<String> getComBearer(String path, String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + bearer);
        return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    /** Bearer inválido, vencido ou de outra chave numa rota PÚBLICA segue como anônimo (200), nunca 401 (contrato de hoje). */
    @Test
    public void bearerInvalidoEmRotaPublica_segueAnonimoComo200() {
        User u = createUser("jwt-publica");
        for (String bearer : List.of("lixo", "a.b.c", identity().tokenDeOutraChave(u.getId()),
                FakeIdentityServer.tokenHs256(u.getEmail(), u.getId()),
                identity().tokenCustom(u.getEmail(), u.getId(), false, FakeIdentityServer.ISSUER,
                        Instant.now().minusSeconds(7200)))) {
            assertEquals(HttpStatus.OK, getComBearer("/bff/events/search?term=", bearer).getStatusCode(), bearer);
            assertEquals(HttpStatus.OK, getComBearer("/actuator/health", bearer).getStatusCode(), bearer);
        }
    }

    /** Em rota protegida: outra chave, HS256, sem uid, vencido, outro emissor e lixo dão 401; o token bom passa. */
    @Test
    public void tokenInvalidoEmRotaProtegida_returns401_eOTokenBomPassa() {
        User u = createUser("jwt-protegida");
        String bom = loginAndGetToken(u.getEmail(), "senha123");
        assertEquals(HttpStatus.OK, getComBearer("/bff/users/me", bom).getStatusCode());

        Map<String, String> invalidos = new LinkedHashMap<>();
        invalidos.put("outra chave RS256", identity().tokenDeOutraChave(u.getId()));
        invalidos.put("HS256", FakeIdentityServer.tokenHs256(u.getEmail(), u.getId()));
        invalidos.put("sem uid", identity().tokenCustom(u.getEmail(), null, false, FakeIdentityServer.ISSUER,
                Instant.now().plusSeconds(3600)));
        invalidos.put("vencido", identity().tokenCustom(u.getEmail(), u.getId(), false, FakeIdentityServer.ISSUER,
                Instant.now().minusSeconds(7200)));
        invalidos.put("outro emissor", identity().tokenCustom(u.getEmail(), u.getId(), false, "outro-emissor",
                Instant.now().plusSeconds(3600)));
        invalidos.put("lixo", "isto-nao-e-um-jwt");
        invalidos.forEach((caso, token) -> assertEquals(HttpStatus.UNAUTHORIZED,
                getComBearer("/bff/users/me", token).getStatusCode(), caso));
        assertEquals(HttpStatus.UNAUTHORIZED, restTemplate.getForEntity(url("/bff/users/me"), String.class).getStatusCode(),
                "sem token");
    }

    /** O papel de host vem do claim {@code isHost} NA EMISSÃO (sem consulta): token anterior à promoção segue sem host. */
    @Test
    public void hostVemDoClaimDoToken_promocaoSoValePraTokenNovo() {
        User u = createUser("jwt-host-claim");
        String antes = loginAndGetToken(u.getEmail(), "senha123");
        identity().promoverHost(u.getId());
        Event e = new Event();
        e.setEvent_name("Evento host claim " + System.nanoTime());
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(5));
        e.setLot_quantity(5);
        e.setPresenters(new ArrayList<>());

        ResponseEntity<String> semHost = restTemplate.exchange(url("/bff/events"), HttpMethod.POST,
                new HttpEntity<>(e, authHeaders(antes)), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, semHost.getStatusCode());
        assertTrue(semHost.getBody().contains("host"), "veio: " + semHost.getBody());

        String depois = loginAndGetToken(u.getEmail(), "senha123");
        assertEquals(HttpStatus.CREATED, restTemplate.exchange(url("/bff/events"), HttpMethod.POST,
                new HttpEntity<>(e, authHeaders(depois)), String.class).getStatusCode());
    }

    /**
     * {@code PUT /bff/users/{id}} com {@code isHost: true} não promove: o login seguinte continua sem host (anti auto-promoção). A regra
     * REAL é do serviço e está provada em {@code IdentityApiE2ETest#isHostDoTokenVemDoBancoNaEmissaoEOPutNaoGravaHost}; aqui só se confere
     * o fluxo do gateway com o contrato (o {@code FakeIdentityServer} reproduz a regra).
     */
    @Test
    public void putComIsHostTrue_naoPromove_tokenSeguinteSemHost() {
        User u = createUser("jwt-nao-promove");
        String token = loginAndGetToken(u.getEmail(), "senha123");
        u.setIsHost(true);
        u.setPassword(null);
        assertEquals(HttpStatus.OK, restTemplate.exchange(url("/bff/users/" + u.getId()), HttpMethod.PUT,
                new HttpEntity<>(u, authHeaders(token)), User.class).getStatusCode());

        String novo = loginAndGetToken(u.getEmail(), "senha123");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(novo.split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"isHost\":false"), "claims: " + payload);
        assertTrue(payload.contains("\"uid\":" + u.getId()), "claims: " + payload);
    }

    /**
     * Identity fora do ar: {@code /bff/users/**} e {@code /bff/my-wallets/**} e o login dão 503 GENÉRICO (nunca 401/404/500, sem a URI
     * interna), mas a reserva com token já validado segue 201 SEM nenhuma chamada ao identity (o {@code uid} do token é o
     * {@code userId}; a chave está no cache do JWKS, que tolera a queda).
     */
    @Test
    public void identityFora_users503_eReservaComTokenValidoSegue201SemChamarOIdentity() {
        User host = createHostUser("host-identity-fora");
        String hostToken = loginAndGetToken(host.getEmail(), "senha123");
        User attendee = createUser("attendee-identity-fora");
        String token = loginAndGetToken(attendee.getEmail(), "senha123");
        Event event = createEvent(host.getId(), "Evento identity fora " + System.nanoTime(), 5, hostToken);
        assertEquals(HttpStatus.OK, getComBearer("/bff/users/me", token).getStatusCode(), "esquenta o cache do JWKS");

        FakeIdentityServer identidade = identity();
        identidade.foraDoAr(true);
        try {
            for (String rota : List.of("/bff/users/me", "/bff/users/" + attendee.getId(), "/bff/my-wallets/" + attendee.getId())) {
                ResponseEntity<String> r = getComBearer(rota, token);
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, r.getStatusCode(), rota);
                assertFalse(String.valueOf(r.getBody()).contains("http") || String.valueOf(r.getBody()).contains("/internal"),
                        "corpo genérico, veio: " + r.getBody());
            }
            ResponseEntity<String> login = restTemplate.postForEntity(url("/bff/users/login"),
                    Map.of("email", attendee.getEmail(), "password", "senha123"), String.class);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, login.getStatusCode(), "login com identity fora: 503, não 401");

            int antes = identidade.chamadas().size();
            ResponseEntity<EventWallet> reserva = restTemplate.exchange(url("/bff/event-wallets"), HttpMethod.POST,
                    new HttpEntity<>(new EventWallet(attendee.getId(), event.getEvent_id()), authHeaders(token)),
                    EventWallet.class);
            assertEquals(HttpStatus.CREATED, reserva.getStatusCode());
            assertEquals(attendee.getId(), reserva.getBody().getUserId(), "o userId da reserva é o uid do token");
            assertEquals(antes, identidade.chamadas().size(), "a reserva não chamou o identity");
        } finally {
            identidade.foraDoAr(false);
        }
    }

    /**
     * {@code uid} de usuário que não existe (mais): a reserva NÃO dá mais 401 (validação sem estado; o teste da #57 mudou de
     * expectativa) e o gateway não confere a carteira: o userId do token é o da reserva.
     */
    @Test
    public void reservaComUidDeUsuarioInexistente_naoDa401_semConferirCarteira() {
        User host = createHostUser("host-uid-fantasma");
        String hostToken = loginAndGetToken(host.getEmail(), "senha123");
        Event event = createEvent(host.getId(), "Evento uid fantasma " + System.nanoTime(), 5, hostToken);
        long uidFantasma = 987_654_321L;
        String token = identity().tokenCustom("fantasma@example.com", uidFantasma, false, FakeIdentityServer.ISSUER,
                Instant.now().plusSeconds(3600));

        ResponseEntity<EventWallet> reserva = restTemplate.exchange(url("/bff/event-wallets"), HttpMethod.POST,
                new HttpEntity<>(Map.of("eventId", event.getEvent_id()), authHeaders(token)), EventWallet.class);

        assertEquals(HttpStatus.CREATED, reserva.getStatusCode());
        assertEquals(uidFantasma, reserva.getBody().getUserId());
    }
}
