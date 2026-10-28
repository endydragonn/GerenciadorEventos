package com.gerenciador.eventos.identity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.FakeRecaptchaServer;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.TestSupport.IdentityTestSession;
import com.gerenciador.eventos.identityclient.IdentityClient;
import com.gerenciador.eventos.identityclient.api.IdentityNaoEncontradoException;
import com.gerenciador.eventos.identityclient.api.IdentityUnavailableException;
import com.gerenciador.eventos.identityclient.api.LoginResult;
import com.gerenciador.eventos.identityclient.api.MyWallet;
import com.gerenciador.eventos.identityclient.api.RecaptchaResponse;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.NotificationEvents;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * PONTA A PONTA do salto {@code gateway -> identity} (issue #36): o {@link IdentityClient} REAL (o que o gateway vai usar) fala por
 * HTTP com o serviço REAL, na porta sorteada, contra o Postgres real (schema {@code identity}) e o reCAPTCHA falso. Mantém o contrato
 * da API interna honesto dos dois lados: login (RS256 verificado contra o JWKS), cadastro em duas fases, MFA, troca de senha,
 * carteira, nomes em lote, {@code host} não gravável, reCAPTCHA, e o {@code InternalTokenFilter} (401 sem/errado, JWKS aberto só na
 * URI exata). O código que o e-mail levaria é lido do evento no {@code identity.event_store}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class IdentityApiE2ETest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    private static final String SENHA = "senha123";

    @LocalServerPort private int porta;
    @Value("${internal.api.token}") private String tokenInterno;
    @Value("${jwt.expiration-seconds}") private long expiracao;
    @Autowired private DatabaseConnection db;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private UserRepository userRepository;
    @MockitoSpyBean private PendingVerificationRepository pendentes;

    private IdentityClient cliente;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void abrirCliente() {
        cliente = new IdentityClient("http://localhost:" + porta, tokenInterno, 50, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(15), Duration.ofSeconds(20));
    }

    @AfterAll
    void fecharCliente() throws Exception {
        cliente.close();
    }

    // ===================== helpers =====================

    private User novo(String tag) {
        long n = SEQ.incrementAndGet();
        User u = new User();
        u.setName("E2E " + tag + " " + n);
        u.setEmail(tag + n + "@example.com");
        u.setFone("11" + String.format("%09d", n % 1_000_000_000));
        u.setPassword(SENHA);
        u.setBirthDate("1990-01-01");
        return u;
    }

    private User criar(String tag) {
        return cliente.createUser(novo(tag));
    }

    private long contar(String sql, String... params) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Último evento do tipo para o e-mail no stream identity.notification (o que o serviço notification mandaria). */
    private JsonNode ultimoPedidoDeEmail(String email, String tipo) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT payload::text FROM identity.event_store WHERE stream_type = ? AND event_type = ? "
                        + "AND payload->>'email' = ? ORDER BY global_position DESC LIMIT 1")) {
            ps.setString(1, NotificationEvents.STREAM_TYPE);
            ps.setString(2, tipo);
            ps.setString(3, email);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "sem evento " + tipo + " para " + email);
                return json.readTree(rs.getString(1));
            }
        }
    }

    private String codigoDoCadastro(String email) throws Exception {
        return ultimoPedidoDeEmail(email, NotificationEvents.VERIFICATION_CODE_REQUESTED).get(NotificationEvents.CODE).asText();
    }

    private static String outroCodigo(String certo) {
        return "000000".equals(certo) ? "111111" : "000000";
    }

    private HttpResponse<String> bruto(String metodo, String path, String corpo, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + path))
                .header("Content-Type", "application/json")
                .method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(corpo));
        if (token != null) {
            b.header("X-Internal-Token", token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JWKSet jwks() throws Exception {
        HttpResponse<String> r = bruto("GET", "/.well-known/jwks.json", null, null);
        assertEquals(200, r.statusCode(), "JWKS sem token interno");
        return JWKSet.parse(r.body());
    }

    // ===================== login e JWT =====================

    @Test
    void loginDevolveTokenRs256ComKidDoJwksEClaimsEUsuarioErradoDa401Marcado() throws Exception {
        User criado = criar("login");
        LoginResult r = cliente.login(criado.getEmail(), SENHA);

        assertNotNull(r);
        assertEquals(criado.getId(), r.user().getId());
        SignedJWT jwt = SignedJWT.parse(r.token());
        assertEquals("RS256", jwt.getHeader().getAlgorithm().getName());
        String kid = jwt.getHeader().getKeyID();
        assertNotNull(kid);
        var chavePublica = jwks().getKeyByKeyId(kid);
        assertNotNull(chavePublica, "o kid do token está no JWKS");
        assertTrue(jwt.verify(new RSASSAVerifier(chavePublica.toRSAKey())), "assinatura confere com a chave do JWKS");

        JWTClaimsSet c = jwt.getJWTClaimsSet();
        assertEquals(criado.getEmail(), c.getSubject());
        assertEquals("gerenciador-identity", c.getIssuer());
        assertEquals(criado.getId(), c.getLongClaim("uid"));
        assertEquals(Boolean.FALSE, c.getBooleanClaim("isHost"), "todo token leva isHost, inclusive o do login");
        long vida = (c.getExpirationTime().getTime() - c.getIssueTime().getTime()) / 1000;
        assertEquals(expiracao, vida, "exp = iat + JWT_EXPIRATION_SECONDS");
        assertTrue(c.getExpirationTime().after(new Date()));

        assertNull(cliente.login(criado.getEmail(), "errada"), "senha errada = 401 marcado = null no cliente");
        assertNull(cliente.login("naoexiste-" + SEQ.incrementAndGet() + "@example.com", SENHA), "e-mail inexistente também");
        assertNull(cliente.login(criado.getEmail(), null), "senha nula não vira 500");
        assertNull(cliente.login(null, SENHA), "e-mail nulo não vira 500");
    }

    @Test
    void isHostDoTokenVemDoBancoNaEmissaoEOPutNaoGravaHost() throws Exception {
        User criado = criar("host");
        criado.setIsHost(true);
        User atualizado = cliente.updateUser(criado);
        assertEquals(Boolean.FALSE, atualizado.getIsHost(), "a resposta é a linha do banco, não o que o chamador mandou");
        assertEquals(Boolean.FALSE, SignedJWT.parse(cliente.login(criado.getEmail(), SENHA).token())
                .getJWTClaimsSet().getBooleanClaim("isHost"), "PUT com isHost=true e login seguinte: isHost=false");

        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE identity.users SET host = true WHERE user_id = " + criado.getId());
        }
        assertEquals(Boolean.TRUE, SignedJWT.parse(cliente.login(criado.getEmail(), SENHA).token())
                .getJWTClaimsSet().getBooleanClaim("isHost"), "o isHost é lido do banco na emissão");

        User postado = novo("hostpost");
        postado.setIsHost(true);
        User criadoComHost = cliente.createUser(postado);
        assertEquals(Boolean.FALSE, criadoComHost.getIsHost(), "POST também não grava host");
    }

    // ===================== cadastro em duas fases =====================

    @Test
    void cadastroPedeVerificaECriaUsuarioComCarteiraEventoETokenDoLogin() throws Exception {
        User u = novo("cad");
        cliente.requestEmailVerification(u.getEmail(), u.getFone(), u.getName(), SENHA, "1991-02-03");
        String codigo = codigoDoCadastro(u.getEmail());
        assertEquals(6, codigo.length());

        IllegalArgumentException errado = assertThrows(IllegalArgumentException.class,
                () -> cliente.verifyEmailAndCreateUser(u.getEmail(), outroCodigo(codigo)));
        assertTrue(errado.getMessage().contains("inválido"), errado.getMessage());
        assertThrows(IdentityNaoEncontradoException.class, () -> cliente.findByEmail(u.getEmail()), "código errado não cria usuário");

        LoginResult r = cliente.verifyEmailAndCreateUser(u.getEmail(), codigo);
        assertNotNull(r.user().getId());
        assertEquals(u.getEmail(), r.user().getEmail());
        JWTClaimsSet c = SignedJWT.parse(r.token()).getJWTClaimsSet();
        assertEquals(r.user().getId(), c.getLongClaim("uid"));
        assertEquals(Boolean.FALSE, c.getBooleanClaim("isHost"));

        MyWallet carteira = cliente.getByUserId(r.user().getId());
        assertEquals(r.user().getId(), carteira.getUserId(), "a carteira nasceu com o usuário (trigger no schema identity)");
        assertTrue(cliente.walletExists(r.user().getId()));
        assertEquals(1, contar("SELECT count(*) FROM identity.event_store WHERE stream_type = 'identity.user' "
                + "AND event_type = 'UserRegistered' AND stream_id = ?", String.valueOf(r.user().getId())));
        assertNotNull(cliente.login(u.getEmail(), SENHA), "a senha escolhida no pedido vale no login");
        assertEquals(0, contar("SELECT count(*) FROM identity.pending_verifications WHERE email = ?", u.getEmail()));
    }

    @Test
    void codigoErradoMaxAttemptsVezesFazOCodigoCertoSerRecusado() throws Exception {
        User u = novo("brute");
        cliente.requestEmailVerification(u.getEmail(), u.getFone(), u.getName(), SENHA, "1991-02-03");
        String codigo = codigoDoCadastro(u.getEmail());

        for (int i = 1; i <= 5; i++) {
            assertThrows(IllegalArgumentException.class, () -> cliente.verifyEmailAndCreateUser(u.getEmail(), outroCodigo(codigo)));
            assertEquals(i, contar("SELECT coalesce(max(attempts), 5) FROM identity.pending_verifications WHERE email = ?",
                    u.getEmail()), "cada código errado incrementa attempts");
        }
        IllegalArgumentException recusado = assertThrows(IllegalArgumentException.class,
                () -> cliente.verifyEmailAndCreateUser(u.getEmail(), codigo));
        assertTrue(recusado.getMessage().contains("Máximo de tentativas"), recusado.getMessage());
        assertThrows(IdentityNaoEncontradoException.class, () -> cliente.findByEmail(u.getEmail()), "nenhum usuário foi criado");
    }

    @Test
    void reenvioGeraCodigoNovoEZeraOContadorDeTentativas() throws Exception {
        User u = novo("resend");
        cliente.requestEmailVerification(u.getEmail(), u.getFone(), u.getName(), SENHA, "1991-02-03");
        String primeiro = codigoDoCadastro(u.getEmail());
        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalArgumentException.class, () -> cliente.verifyEmailAndCreateUser(u.getEmail(), outroCodigo(primeiro)));
        }
        assertEquals(3, contar("SELECT attempts FROM identity.pending_verifications WHERE email = ?", u.getEmail()));

        cliente.resendVerificationCode(u.getEmail());

        assertEquals(0, contar("SELECT attempts FROM identity.pending_verifications WHERE email = ?", u.getEmail()), "reenvio zera");
        assertEquals(2, contar("SELECT count(*) FROM identity.event_store WHERE stream_type = ? AND payload->>'email' = ?",
                NotificationEvents.STREAM_TYPE, u.getEmail()), "outro VerificationCodeRequested");
        String segundo = codigoDoCadastro(u.getEmail());
        assertNotNull(cliente.verifyEmailAndCreateUser(u.getEmail(), segundo));
        assertThrows(IllegalArgumentException.class, () -> cliente.resendVerificationCode(u.getEmail()),
                "sem verificação pendente: regra de negócio (400)");
        assertThrows(IllegalArgumentException.class,
                () -> cliente.requestEmailVerification(u.getEmail(), u.getFone(), u.getName(), SENHA, "1991-02-03"),
                "e-mail já cadastrado");
        assertThrows(IllegalArgumentException.class,
                () -> cliente.requestEmailVerification("x" + SEQ.incrementAndGet() + "@example.com", "1", "n", SENHA, "data-ruim"),
                "data malformada é 400, não 500");
    }

    // ===================== MFA e troca de senha =====================

    @Test
    void mfaPedeVerificaETrocaASenhaEUsuarioInexistenteNaVerificacaoDa401NaoNotFound() throws Exception {
        User u = criar("mfa");
        User devolvido = cliente.requestPasswordResetMfa(u.getId());
        assertEquals(u.getEmail(), devolvido.getEmail());
        JsonNode pedido = ultimoPedidoDeEmail(u.getEmail(), NotificationEvents.PASSWORD_RESET_MFA_REQUESTED);
        String codigo = pedido.get(NotificationEvents.CODE).asText();
        assertEquals(8, codigo.length());

        assertFalse(cliente.verifyPasswordResetMfaCode(u.getId(), "errado!!"));
        assertTrue(cliente.verifyPasswordResetMfaCode(u.getId(), codigo));
        assertFalse(cliente.verifyPasswordResetMfaCode(u.getId(), null), "código nulo não vira 500");
        assertFalse(cliente.verifyPasswordResetMfaCode(987_654_321L, codigo),
                "usuário inexistente = 401 com o marcador (nunca 404: o /bff viraria 500)");
        assertThrows(IdentityNaoEncontradoException.class, () -> cliente.requestPasswordResetMfa(987_654_321L));

        assertThrows(IllegalArgumentException.class, () -> cliente.changePasswordWithMfa(u.getId(), "novasenha1", "errado!!"));
        assertThrows(IllegalArgumentException.class, () -> cliente.changePasswordWithMfa(u.getId(), "curta", codigo),
                "senha nova curta");
        cliente.changePasswordWithMfa(u.getId(), "novasenha1", codigo);

        assertNotNull(cliente.login(u.getEmail(), "novasenha1"), "login com a senha nova funciona");
        assertNull(cliente.login(u.getEmail(), SENHA), "a antiga não");
        assertFalse(cliente.verifyPasswordResetMfaCode(u.getId(), codigo), "o código é limpo depois do uso");
        assertThrows(IllegalArgumentException.class, () -> cliente.changePasswordWithMfa(u.getId(), "outrasenha2", codigo),
                "reuso do mesmo código é recusado");
        assertNull(cliente.login(u.getEmail(), "outrasenha2"), "e não trocou a senha de novo");
    }

    @Test
    void trocaDeSenhaExigeASenhaAtualECumpreOMinimo() throws Exception {
        User u = criar("troca");

        IllegalArgumentException atual = assertThrows(IllegalArgumentException.class,
                () -> cliente.changePassword(u.getId(), "nao-e-essa", "novasenha1"));
        assertTrue(atual.getMessage().contains("Senha atual incorreta"), atual.getMessage());
        assertThrows(IllegalArgumentException.class, () -> cliente.changePassword(u.getId(), SENHA, "123"), "nova senha curta");
        assertThrows(IllegalArgumentException.class, () -> cliente.changePassword(u.getId(), null, "novasenha1"),
                "senha atual nula é 400, não 500");
        assertNotNull(cliente.login(u.getEmail(), SENHA), "nada mudou nas recusas");

        assertTrue(cliente.changePassword(u.getId(), SENHA, "novasenha1"));
        assertNotNull(cliente.login(u.getEmail(), "novasenha1"));
        assertNull(cliente.login(u.getEmail(), SENHA));
    }

    // ===================== usuários, nomes e carteira =====================

    @Test
    void crudNomesEmLoteCarteiraEErrosTipados() throws Exception {
        User a = criar("a");
        User b = criar("b");
        assertEquals(a.getEmail(), cliente.findById(a.getId()).getEmail());
        assertEquals(a.getId(), cliente.findByEmail(a.getEmail().toUpperCase()).getId(), "busca por e-mail ignora caixa");
        User comMais = cliente.createUser(novo("mais+tag"));
        assertEquals(comMais.getId(), cliente.findByEmail(comMais.getEmail()).getId(), "'+' do e-mail atravessa como valor");

        assertEquals("", cliente.getValidationErrors(novo("valido")));
        assertTrue(cliente.getValidationErrors(a).contains("já está cadastrado"));
        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class, () -> cliente.createUser(a));
        assertTrue(dup.getMessage().contains("já existente"), dup.getMessage());

        assertTrue(cliente.exists(a.getId()));
        assertFalse(cliente.exists(987_654_321L));
        assertThrows(IdentityNaoEncontradoException.class, () -> cliente.findById(987_654_321L));
        assertThrows(IdentityNaoEncontradoException.class, () -> cliente.getByUserId(987_654_321L));
        assertFalse(cliente.walletExists(987_654_321L));
        assertTrue(cliente.walletExists(a.getId()));
        assertEquals(a.getId(), cliente.ensureExists(a.getId()).getUserId());

        cliente.deactivateUser(b.getId());
        cliente.deactivateUser(987_654_321L); // inexistente: no-op
        assertEquals(Map.of(a.getId(), a.getName()), cliente.findNames(List.of(a.getId(), b.getId(), 987_654_321L)),
                "só conta ativa entra no mapa");
        assertEquals(Map.of(), cliente.findNames(List.of()));
    }

    // ===================== violação de unicidade e SQLException checada =====================

    @Test
    void telefoneOuEmailDeOutroUsuarioNoVerifyEnoPutDa400GenericoSemVazarConstraint() throws Exception {
        User dono = criar("dono");

        // o pedido só confere o e-mail: o telefone repetido só estoura no INSERT do verify
        User u = novo("duptel");
        cliente.requestEmailVerification(u.getEmail(), dono.getFone(), u.getName(), SENHA, "1991-02-03");
        String codigo = codigoDoCadastro(u.getEmail());
        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class,
                () -> cliente.verifyEmailAndCreateUser(u.getEmail(), codigo));
        assertTrue(dup.getMessage().contains("Já existe um cadastro"), dup.getMessage());
        for (String vazamento : List.of("Users_", "_key", "constraint", "Key (", dono.getFone())) {
            assertFalse(dup.getMessage().contains(vazamento), "sem detalhe do banco na resposta: " + dup.getMessage());
        }
        assertThrows(IdentityNaoEncontradoException.class, () -> cliente.findByEmail(u.getEmail()), "nada foi criado");

        User outro = criar("putdup");
        outro.setEmail(dono.getEmail());
        IllegalArgumentException putDup = assertThrows(IllegalArgumentException.class, () -> cliente.updateUser(outro));
        assertTrue(putDup.getMessage().contains("Já existe um cadastro"), putDup.getMessage());
        assertFalse(putDup.getMessage().contains(dono.getEmail()), "sem o valor repetido na resposta");
        assertEquals(1, contar("SELECT count(*) FROM identity.users WHERE email = ?", dono.getEmail()));
    }

    @Test
    void sqlExceptionChecadaDoCadastroSai500GenericoEmTextoNaoOJsonDoBoot() throws Exception {
        doThrow(new java.sql.SQLException("detalhe SQL secreto", "08006")).when(pendentes).findByEmail(org.mockito.ArgumentMatchers.anyString());

        HttpResponse<String> r = bruto("POST", "/internal/registrations/resend", "{\"email\":\"a@b.com\"}", tokenInterno);

        assertEquals(500, r.statusCode());
        assertEquals("Erro interno no serviço identity", r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
        assertThrows(IdentityUnavailableException.class, () -> cliente.resendVerificationCode("a@b.com"));
    }

    // ===================== reCAPTCHA =====================

    @Test
    void recaptchaDevolveOEnvelopeComStatusEOCorpoDeSempre() {
        RecaptchaResponse alto = cliente.verify("alto", "login");
        assertEquals(200, alto.status());
        assertEquals(true, alto.body().get("success"));
        assertEquals("login", alto.body().get("action"));
        assertEquals(IdentityTestSession.SECRET_DE_TESTE, FakeRecaptchaServer.get().ultimoSecret(),
                "o identity manda a chave dele (property), não a do ambiente");

        RecaptchaResponse baixo = cliente.verify("baixo", "login");
        assertEquals(200, baixo.status());
        assertEquals(false, baixo.body().get("success"), "score 0.3 < 0.7");
        assertEquals(0.3, ((Number) baixo.body().get("score")).doubleValue());
        assertEquals(false, cliente.verify("alto", "outra-acao").body().get("success"), "ação diferente da pedida");
        assertEquals(false, cliente.verify("acao-errada", "login").body().get("success"));
        assertEquals(false, cliente.verify("falha", "login").body().get("success"));

        RecaptchaResponse google500 = cliente.verify("http500", "login");
        assertEquals(500, google500.status(), "Google com erro = o mesmo 500 de hoje, dentro do envelope");
        assertEquals(false, google500.body().get("success"));
        assertEquals("Erro de conexão com reCAPTCHA", google500.body().get("error"));
        RecaptchaResponse vazio = cliente.verify("vazio", "login");
        assertEquals(500, vazio.status());
        assertEquals("Resposta inválida do reCAPTCHA", vazio.body().get("error"));
    }

    @Test
    void recaptchaComGoogleLentoEstouraOTimeoutDeLeituraSemPrenderAThread() {
        long ini = System.nanoTime();
        RecaptchaResponse lento = cliente.verify("lento", "login");
        long ms = (System.nanoTime() - ini) / 1_000_000;
        assertEquals(500, lento.status());
        assertEquals("Erro de conexão com reCAPTCHA", lento.body().get("error"));
        assertTrue(ms < 6_300, "timeout de leitura de 5 s, não os 6,5 s do Google lento: " + ms + " ms");
    }

    @Test
    void recaptchaSemChaveConfiguradaDaOMesmoErroDeHoje() {
        RecaptchaResponse r = new RecaptchaService("", "http://127.0.0.1:1/nunca-chamado", 0.7).verify("alto", "login");
        assertEquals(500, r.status());
        assertEquals(Map.of("success", false, "error", "reCAPTCHA não configurado"), r.body());
    }

    // ===================== token interno e JWKS =====================

    @Test
    void semTokenOuComTokenErradoTodaRotaInternaDa401SemMarcadorENadaEGravado() throws Exception {
        List<String[]> rotas = new ArrayList<>(List.of(
                new String[] {"GET", "/internal/users/1"}, new String[] {"GET", "/internal/users/by-email?email=a%40b.com"},
                new String[] {"POST", "/internal/users"}, new String[] {"PUT", "/internal/users/1"},
                new String[] {"DELETE", "/internal/users/1"}, new String[] {"POST", "/internal/users/validate"},
                new String[] {"POST", "/internal/users/login"}, new String[] {"PUT", "/internal/users/1/password"},
                new String[] {"POST", "/internal/users/1/password-reset-mfa"},
                new String[] {"POST", "/internal/users/1/password-reset-mfa/verify"},
                new String[] {"PUT", "/internal/users/1/password-with-mfa"}, new String[] {"GET", "/internal/users/1/exists"},
                new String[] {"POST", "/internal/users/names"}, new String[] {"POST", "/internal/registrations"},
                new String[] {"POST", "/internal/registrations/verify"}, new String[] {"POST", "/internal/registrations/resend"},
                new String[] {"GET", "/internal/wallets/1"}, new String[] {"PUT", "/internal/wallets/1"},
                new String[] {"GET", "/internal/wallets/1/exists"}, new String[] {"POST", "/internal/recaptcha/verify"},
                new String[] {"GET", "/internal;x=1/users/1"}, new String[] {"GET", "/%69nternal/users/1"},
                new String[] {"POST", "/.well-known/jwks.json"}, new String[] {"GET", "/.well-known/jwks.json;x"},
                new String[] {"GET", "/.well-known/%6Awks.json"}, new String[] {"GET", "/.well-known/jwks.json/"}));
        User u = novo("semtoken");
        String corpo = json.writeValueAsString(u);
        for (String[] r : rotas) {
            for (String token : new String[] {null, "token-errado", ""}) {
                HttpResponse<String> resp = bruto(r[0], r[1], r[0].equals("GET") || r[0].equals("DELETE") ? null : corpo, token);
                assertEquals(401, resp.statusCode(), r[0] + " " + r[1] + " token=" + token);
                assertTrue(resp.headers().firstValue(IdentityClient.HEADER_CREDENCIAIS_INVALIDAS).isEmpty(),
                        "401 do filtro NÃO leva o marcador de credenciais (senão token errado viraria 'senha errada')");
            }
        }
        assertEquals(0, contar("SELECT count(*) FROM identity.users WHERE email = ?", u.getEmail()), "o POST sem token não gravou");
    }

    @Test
    void jwksEPublicoSoNaUriExataEPublicaApenasAParteQueVerifica() throws Exception {
        HttpResponse<String> r = bruto("GET", "/.well-known/jwks.json", null, null);
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        JsonNode keys = json.readTree(r.body()).get("keys");
        assertEquals(1, keys.size());
        JsonNode k = keys.get(0);
        assertEquals("RSA", k.get("kty").asText());
        assertEquals("RS256", k.get("alg").asText());
        assertEquals("sig", k.get("use").asText());
        assertNotNull(k.get("kid"));
        for (String privado : Set.of("d", "p", "q", "dp", "dq", "qi")) {
            assertFalse(k.has(privado), "o JWKS não pode vazar o componente privado '" + privado + "'");
        }
        assertEquals(200, bruto("GET", "/.well-known/jwks.json", null, tokenInterno).statusCode(), "com token também");
    }

    // ===================== formato dos erros =====================

    @Test
    void pedidoMalformadoDa400EmTextoEFalhaInternaDa500GenericoSemVazarDetalhe() throws Exception {
        HttpResponse<String> json400 = bruto("POST", "/internal/users/login", "{isto não é json", tokenInterno);
        assertEquals(400, json400.statusCode());
        assertTrue(json400.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"),
                "erro de pedido é texto de regra, não o JSON padrão do Spring");
        assertEquals(400, bruto("GET", "/internal/users/abc", null, tokenInterno).statusCode(), "id não numérico");
        assertEquals(400, bruto("GET", "/internal/users/by-email", null, tokenInterno).statusCode(), "parâmetro ausente");
        assertEquals(400, bruto("GET", "/internal/wallets/0", null, tokenInterno).statusCode(), "userId inválido é regra");
        assertEquals(400, bruto("POST", "/internal/registrations", "{}", tokenInterno).statusCode(), "campos obrigatórios");

        HttpResponse<String> nf = bruto("GET", "/internal/users/987654321", null, tokenInterno);
        assertEquals(404, nf.statusCode());
        assertTrue(nf.headers().firstValue(IdentityNaoEncontradoException.HEADER).isPresent(), "404 de recurso leva o marcador");
        assertEquals(404, bruto("GET", "/internal/rota-que-nao-existe", null, tokenInterno).statusCode());
        assertTrue(bruto("GET", "/internal/rota-que-nao-existe", null, tokenInterno).headers()
                .firstValue(IdentityNaoEncontradoException.HEADER).isEmpty(), "404 de rota inexistente NÃO leva o marcador");

        doThrow(new RuntimeException("detalhe SQL secreto")).when(userRepository).findById(anyLong());
        HttpResponse<String> erro = bruto("GET", "/internal/users/5/exists", null, tokenInterno);
        assertEquals(500, erro.statusCode());
        assertFalse(erro.body().contains("secreto"), "o detalhe fica só no log: " + erro.body());
        assertThrows(IdentityUnavailableException.class, () -> cliente.exists(5L), "500 vira indisponível no cliente, nunca false");
    }
}
