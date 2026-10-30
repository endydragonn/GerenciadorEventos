package com.gerenciador.eventos.TestSupport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gerenciador.eventos.identityclient.IdentityClient;
import com.gerenciador.eventos.identityclient.api.IdentityNaoEncontradoException;
import com.gerenciador.eventos.identityclient.api.LoginResult;
import com.gerenciador.eventos.identityclient.api.MyWallet;
import com.gerenciador.eventos.identityclient.api.RecaptchaResponse;
import com.gerenciador.eventos.identityclient.api.User;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Servidor HTTP de verdade (JDK {@code HttpServer}, porta sorteada) no papel do serviço identity nos testes do MONÓLITO (issue
 * #36). O monólito não tem o código do serviço no classpath (fronteira física): o gateway fala HTTP com este servidor, em memória,
 * pelo {@code IdentityClient} real, e valida os tokens dele pelo JWKS que ele serve. Implementa o MESMO contrato do serviço
 * ({@code /internal/users|registrations|wallets|recaptcha}, 400 texto de regra, 401 de credenciais COM o marcador
 * {@code X-Identity-Credenciais-Invalidas}, 404 COM {@code X-Identity-Nao-Encontrado}, 401 sem o token interno, login e verify-email
 * devolvem {@code {user, token}}) e as regras mínimas que os testes do gateway enxergam. Assina com uma chave RSA de teste
 * (RS256, claims {@code sub}/{@code uid}/{@code isHost}/{@code iss}, header {@code kid}) e serve só a parte pública em
 * {@code GET /.well-known/jwks.json}, que conta as requisições ({@link #jwksHits()}).
 *
 * <p>É um fake: Postgres, BCrypt, eventos e reCAPTCHA real são do módulo {@code identity}, e o contrato cliente REAL x serviço REAL é
 * o {@code IdentityApiE2ETest} de lá. Uma instância por JVM, iniciada pelo {@link IdentityFakeSession} (URL na propriedade
 * {@code identity.service.url}); {@link #foraDoAr(boolean)} simula o container parado (500 em toda rota, JWKS inclusive).
 * {@link #reset()} esvazia usuários e cadastros mas MANTÉM a chave e a sequência de ids (um {@code uid} nunca se repete na JVM,
 * então um token de um teste anterior não vira o usuário de outro).</p>
 */
public final class FakeIdentityServer {

    public static final String ISSUER = "gerenciador-identity";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static FakeIdentityServer instancia;

    public static synchronized FakeIdentityServer iniciar() throws IOException {
        if (instancia == null) {
            instancia = new FakeIdentityServer();
        }
        return instancia;
    }

    public static FakeIdentityServer get() {
        return instancia;
    }

    /** Estado de um usuário (a senha fica em claro: é um fake). */
    private static final class Conta {
        final User user;
        String senha;
        String mfa;

        Conta(User user, String senha) {
            this.user = user;
            this.senha = senha;
        }
    }

    private final HttpServer server;
    private final RSAKey chave;
    private final Map<Long, Conta> contas = new ConcurrentHashMap<>();
    private final Map<String, String[]> pendentes = new ConcurrentHashMap<>(); // email -> {fone, nome, senha, nascimento, codigo}
    private final AtomicLong sequencia = new AtomicLong();
    private final AtomicInteger jwksHits = new AtomicInteger();
    private final List<String> chamadas = new CopyOnWriteArrayList<>();
    private volatile boolean foraDoAr;
    private volatile RecaptchaResponse recaptcha = new RecaptchaResponse(500,
            Map.of("success", false, "error", "reCAPTCHA não configurado"));

    private FakeIdentityServer() throws IOException {
        try {
            chave = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyIDFromThumbprint(true).generate(); // como o JwtSigningKey do serviço
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal", this::tratar);
        server.createContext("/.well-known/jwks.json", this::jwks);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** {@code kid} da chave de assinatura (o do header dos tokens válidos). */
    public String kid() {
        return chave.getKeyID();
    }

    /** Esvazia usuários e cadastros (o {@code GlobalDbTruncator} chama junto com o TRUNCATE). Chave e sequência de ids NÃO voltam. */
    public synchronized void reset() {
        contas.clear();
        pendentes.clear();
        chamadas.clear();
        jwksHits.set(0);
        foraDoAr = false;
        recaptcha = new RecaptchaResponse(500, Map.of("success", false, "error", "reCAPTCHA não configurado"));
    }

    /** O "container parado": toda rota (e o JWKS) responde 500. */
    public void foraDoAr(boolean fora) {
        this.foraDoAr = fora;
    }

    /** Quantas vezes o {@code /.well-known/jwks.json} foi pedido (inclusive durante {@link #foraDoAr}). */
    public int jwksHits() {
        return jwksHits.get();
    }

    /** "METODO /caminho" de cada chamada recebida em {@code /internal/**}, na ordem. */
    public List<String> chamadas() {
        return Collections.unmodifiableList(new ArrayList<>(chamadas));
    }

    /** Resposta do {@code /internal/recaptcha/verify} (default: 500 "não configurado", como com a chave vazia). */
    public void recaptcha(RecaptchaResponse resposta) {
        this.recaptcha = resposta;
    }

    // ===================== ganchos dos testes (o que antes era SQL direto ou o service local) =====================

    /** Cria o usuário direto no estado (como o {@code UserService.createUser} local fazia); a senha em claro fica para o login. */
    public synchronized User criarUsuario(User u) {
        validarObrigatorios(u);
        validarDuplicados(u);
        User salvo = copiar(u);
        salvo.setId(sequencia.incrementAndGet());
        salvo.setIsActive(true);
        salvo.setIsHost(false);
        if (salvo.getIsAdmin() == null) {
            salvo.setIsAdmin(false);
        }
        LocalDateTime agora = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        salvo.setCreatedAt(agora);
        salvo.setUpdatedAt(agora);
        salvo.setPassword(null);
        contas.put(salvo.getId(), new Conta(salvo, u.getPassword()));
        return copiar(salvo);
    }

    /** O {@code UPDATE users SET host = true} dos testes (nenhuma rota promove host). */
    public void promoverHost(long id) {
        conta(id).user.setIsHost(true);
    }

    /** O {@code UPDATE users SET isactive = true}. */
    public void reativar(long id) {
        conta(id).user.setIsActive(true);
    }

    public String codigoPendente(String email) {
        String[] p = pendentes.get(email);
        return p == null ? null : p[4];
    }

    /** Gera um código MFA novo (o {@code UserService.generatePasswordResetMfa} local). */
    public String gerarMfa(long id) {
        Conta c = conta(id);
        c.mfa = codigo(8);
        return c.mfa;
    }

    /** Token válido (RS256, chave do fake) do usuário, com o {@code isHost} de AGORA. */
    public String tokenDe(long id) {
        User u = conta(id).user;
        return assinar(chave, u.getEmail(), id, Boolean.TRUE.equals(u.getIsHost()), ISSUER, Instant.now().plusSeconds(3600), true);
    }

    /** Token com claims livres, assinado pela chave do fake (casos de {@code iss}/{@code exp}/{@code uid}). */
    public String tokenCustom(String sub, Long uid, boolean host, String issuer, Instant exp) {
        return assinar(chave, sub, uid, host, issuer, exp, true);
    }

    /** Token bem formado e RS256, mas assinado por OUTRA chave (mesmo {@code kid} do fake: só a assinatura difere). */
    public String tokenDeOutraChave(long id) {
        try {
            RSAKey outra = new RSAKeyGenerator(2048).keyID(chave.getKeyID()).generate();
            return assinar(outra, "outra@example.com", id, false, ISSUER, Instant.now().plusSeconds(3600), true);
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Token HS256 (o formato antigo, assinado com um segredo qualquer). */
    public static String tokenHs256(String sub, long uid) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(sub).issuer(ISSUER).claim("uid", uid)
                    .claim("isHost", true).expirationTime(Date.from(Instant.now().plusSeconds(3600))).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
            byte[] segredo = new byte[32];
            RANDOM.nextBytes(segredo);
            jwt.sign(new MACSigner(segredo));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Token RS256 da chave do fake, mas com um {@code kid} que o JWKS não tem (para o teste do limite de refetch). */
    public String tokenComKidDesconhecido() {
        try {
            RSAKey aleatoria = new RSAKeyGenerator(2048).keyID("kid-" + Long.toHexString(RANDOM.nextLong())).generate();
            return assinar(aleatoria, "x@example.com", 1L, false, ISSUER, Instant.now().plusSeconds(3600), true);
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String assinar(RSAKey com, String sub, Long uid, boolean host, String issuer, Instant exp, boolean comKid) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(sub).issuer(issuer).claim("isHost", host)
                    .issueTime(new Date()).expirationTime(Date.from(exp));
            if (uid != null) {
                claims.claim("uid", uid);
            }
            JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT);
            if (comKid) {
                header.keyID(com.getKeyID());
            }
            SignedJWT jwt = new SignedJWT(header.build(), claims.build());
            jwt.sign(new RSASSASigner(com));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ===================== roteamento (espelha UserController/RegistrationController/WalletController) =====================

    private void jwks(HttpExchange ex) throws IOException {
        jwksHits.incrementAndGet();
        if (foraDoAr) {
            responder(ex, 500, "text/plain", "Erro interno no serviço identity");
            return;
        }
        responder(ex, 200, "application/json", new JWKSet(chave.toPublicJWK()).toString());
    }

    private void tratar(HttpExchange ex) throws IOException {
        String metodo = ex.getRequestMethod();
        String caminho = ex.getRequestURI().getPath();
        String esperado = System.getenv("INTERNAL_API_TOKEN");
        if (esperado != null && !esperado.equals(ex.getRequestHeaders().getFirst("X-Internal-Token"))) {
            chamadas.add("401 " + metodo + " " + caminho);
            responder(ex, 401, "text/plain", "Token interno ausente ou inválido");
            return;
        }
        chamadas.add(metodo + " " + caminho);
        if (foraDoAr) {
            responder(ex, 500, "text/plain", "Erro interno no serviço identity");
            return;
        }
        try {
            String[] p = caminho.substring("/internal/".length()).split("/");
            switch (p[0]) {
                case "users" -> usuarios(ex, metodo, java.util.Arrays.copyOfRange(p, 1, p.length));
                case "registrations" -> cadastro(ex, p.length > 1 ? p[1] : "");
                case "wallets" -> carteiras(ex, metodo, java.util.Arrays.copyOfRange(p, 1, p.length));
                case "recaptcha" -> responderJson(ex, 200, recaptcha);
                default -> responder(ex, 404, "text/plain", "rota inexistente");
            }
        } catch (IllegalArgumentException e) {
            responder(ex, 400, "text/plain", e.getMessage());
        } catch (NaoEncontrado e) {
            ex.getResponseHeaders().add(IdentityNaoEncontradoException.HEADER, "true");
            responder(ex, 404, "text/plain", e.getMessage());
        } catch (CredenciaisInvalidas e) {
            ex.getResponseHeaders().add(IdentityClient.HEADER_CREDENCIAIS_INVALIDAS, "true");
            responder(ex, 401, "text/plain", e.getMessage());
        } catch (RuntimeException e) {
            responder(ex, 500, "text/plain", "Erro interno no serviço identity");
        }
    }

    private void usuarios(HttpExchange ex, String metodo, String[] p) throws IOException {
        if (p.length == 0) {
            responderJson(ex, 201, criarUsuario(lerUser(ex)));
            return;
        }
        switch (p[0]) {
            case "validate" -> responder(ex, 200, "text/plain", erros(lerUser(ex)));
            case "login" -> {
                Map<String, String> req = lerMapa(ex);
                responderJson(ex, 200, login(req.get("email"), req.get("password")));
            }
            case "by-email" -> responderJson(ex, 200, copiar(porEmail(query(ex).get("email")).user));
            case "names" -> responderJson(ex, 200, nomes(JSON.readValue(ex.getRequestBody(), new TypeReference<List<Long>>() { })));
            default -> porId(ex, metodo, Long.parseLong(p[0]), p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : "");
        }
    }

    private void porId(HttpExchange ex, String metodo, long id, String sub, String sub2) throws IOException {
        switch (metodo + ":" + sub + ":" + sub2) {
            case "GET::" -> responderJson(ex, 200, copiar(conta(id).user));
            case "PUT::" -> responderJson(ex, 200, atualizar(id, lerUser(ex)));
            case "DELETE::" -> {
                Conta c = contas.get(id);
                if (c != null) {
                    c.user.setIsActive(false);
                }
                responder(ex, 204, null, null);
            }
            case "GET:exists:" -> responderJson(ex, 200, contas.containsKey(id));
            case "PUT:password:" -> {
                Map<String, String> req = lerMapa(ex);
                responderJson(ex, 200, trocarSenha(id, req.get("oldPassword"), req.get("newPassword")));
            }
            case "POST:password-reset-mfa:" -> {
                Conta c = conta(id);
                c.mfa = codigo(8);
                responderJson(ex, 200, copiar(c.user));
            }
            case "POST:password-reset-mfa:verify" -> {
                if (!mfaConfere(id, lerMapa(ex).get("mfaCode"))) {
                    throw new CredenciaisInvalidas("Código MFA inválido ou expirado");
                }
                responder(ex, 200, null, null);
            }
            case "PUT:password-with-mfa:" -> {
                Map<String, String> req = lerMapa(ex);
                trocarSenhaComMfa(id, req.get("newPassword"), req.get("mfaCode"));
                responder(ex, 204, null, null);
            }
            default -> responder(ex, 405, "text/plain", "método não suportado");
        }
    }

    private void cadastro(HttpExchange ex, String acao) throws IOException {
        Map<String, String> req = lerMapa(ex);
        switch (acao) {
            case "" -> {
                String email = req.get("email");
                if (porEmailOuNull(email) != null) {
                    throw new IllegalArgumentException("Email já existente: " + email);
                }
                pendentes.put(email, new String[] {req.get("fone"), req.get("userName"), req.get("password"),
                    req.get("birthDate"), codigo(6)});
                responder(ex, 201, null, null);
            }
            case "resend" -> {
                String[] p = pendentes.get(req.get("email"));
                if (p == null) {
                    throw new IllegalArgumentException("Nenhuma verificação pendente para este email");
                }
                p[4] = codigo(6);
                responder(ex, 200, null, null);
            }
            case "verify" -> {
                String[] p = pendentes.get(req.get("email"));
                if (p == null || !p[4].equals(req.get("verificationCode"))) {
                    throw new IllegalArgumentException("Código de verificação inválido");
                }
                User u = new User(p[1], req.get("email"), p[0], p[2], p[3]);
                User criado = criarUsuario(u);
                pendentes.remove(req.get("email"));
                responderJson(ex, 201, new LoginResult(criado, tokenDe(criado.getId())));
            }
            default -> responder(ex, 404, "text/plain", "rota inexistente");
        }
    }

    private void carteiras(HttpExchange ex, String metodo, String[] p) throws IOException {
        long id = Long.parseLong(p[0]);
        if (p.length > 1 && "exists".equals(p[1])) {
            responderJson(ex, 200, contas.containsKey(id));
            return;
        }
        if (!contas.containsKey(id)) {
            throw new NaoEncontrado("Carteira não encontrada para user_id=" + id);
        }
        MyWallet w = new MyWallet(id);
        w.setCreatedAt(contas.get(id).user.getCreatedAt());
        w.setUpdatedAt(contas.get(id).user.getCreatedAt());
        responderJson(ex, 200, w);
    }

    // ===================== regras =====================

    private synchronized LoginResult login(String email, String senha) {
        Conta c = email == null || senha == null ? null : porEmailOuNull(email);
        if (c == null || !senha.equals(c.senha)) {
            throw new CredenciaisInvalidas("Credenciais inválidas");
        }
        return new LoginResult(copiar(c.user), tokenDe(c.user.getId()));
    }

    private synchronized User atualizar(long id, User novo) {
        Conta c = conta(id);
        validarObrigatorios(novo);
        Conta dono = porEmailOuNull(novo.getEmail());
        if (dono != null && dono != c) { // como o serviço (#166): e-mail de outra conta, sem diferenciar caixa
            throw new IllegalArgumentException("Já existe um cadastro com estes dados (e-mail, telefone ou nome)");
        }
        c.user.setName(novo.getName());
        c.user.setEmail(novo.getEmail());
        c.user.setFone(novo.getFone());
        if (novo.getIsAdmin() != null) {
            c.user.setIsAdmin(novo.getIsAdmin()); // como o serviço: admin é gravável, host NUNCA
        }
        // como o serviço (#171): o PUT IGNORA o password (só /password e /password-with-mfa trocam a senha) e a resposta não leva senha
        c.user.setUpdatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        User resposta = copiar(c.user);
        resposta.setPassword(null);
        return resposta;
    }

    private synchronized boolean trocarSenha(long id, String atual, String nova) {
        Conta c = contas.get(id);
        if (c == null) {
            throw new IllegalArgumentException("Usuário não encontrado");
        }
        if (atual == null || !atual.equals(c.senha)) {
            throw new IllegalArgumentException("Senha atual incorreta");
        }
        c.senha = senhaNova(nova);
        return true;
    }

    private synchronized boolean mfaConfere(long id, String codigo) {
        Conta c = contas.get(id);
        return c != null && codigo != null && codigo.equals(c.mfa);
    }

    private synchronized void trocarSenhaComMfa(long id, String nova, String codigo) {
        if (!mfaConfere(id, codigo)) {
            throw new IllegalArgumentException("Código MFA inválido ou expirado");
        }
        Conta c = contas.get(id);
        c.senha = senhaNova(nova);
        c.mfa = null;
    }

    private static String senhaNova(String nova) {
        if (nova == null || nova.trim().length() < 6) {
            throw new IllegalArgumentException("A nova senha deve ter no mínimo 6 caracteres");
        }
        return nova;
    }

    private Map<Long, String> nomes(List<Long> ids) {
        Map<Long, String> m = new LinkedHashMap<>();
        for (Long id : ids) {
            Conta c = contas.get(id);
            if (c != null && Boolean.TRUE.equals(c.user.getIsActive())) {
                m.put(id, c.user.getName());
            }
        }
        return m;
    }

    private String erros(User u) {
        StringBuilder sb = new StringBuilder();
        if (u.getName() == null || u.getName().trim().isEmpty()) sb.append("Nome não pode estar vazio. ");
        if (u.getEmail() == null || u.getEmail().trim().isEmpty()) sb.append("Email não pode estar vazio. ");
        if (u.getEmail() != null && !u.getEmail().isBlank() && porEmailOuNull(u.getEmail()) != null) {
            sb.append("Email '").append(u.getEmail()).append("' já está cadastrado. ");
        }
        if (u.getName() != null && !u.getName().isBlank()
                && contas.values().stream().anyMatch(c -> u.getName().equals(c.user.getName()))) {
            sb.append("Nome '").append(u.getName()).append("' já está cadastrado. ");
        }
        if (u.getFone() != null && !u.getFone().isBlank()
                && contas.values().stream().anyMatch(c -> u.getFone().equals(c.user.getFone()))) {
            sb.append("Telefone '").append(u.getFone()).append("' já está cadastrado. ");
        }
        return sb.toString().trim();
    }

    private static void validarObrigatorios(User u) {
        if (u.getName() == null || u.getName().trim().isEmpty()) throw new IllegalArgumentException("Nome não pode estar vazio");
        if (u.getEmail() == null || u.getEmail().trim().isEmpty()) throw new IllegalArgumentException("Email não pode estar vazio");
        if (u.getFone() == null || u.getFone().trim().isEmpty()) throw new IllegalArgumentException("Telefone não pode estar vazio");
    }

    private void validarDuplicados(User u) {
        if (contas.values().stream().anyMatch(c -> u.getName().equals(c.user.getName()))) {
            throw new IllegalArgumentException("Nome já existente: " + u.getName());
        }
        if (porEmailOuNull(u.getEmail()) != null) throw new IllegalArgumentException("Email já existente: " + u.getEmail());
        if (contas.values().stream().anyMatch(c -> u.getFone().equals(c.user.getFone()))) {
            throw new IllegalArgumentException("Telefone já existente: " + u.getFone());
        }
    }

    private Conta conta(long id) {
        Conta c = contas.get(id);
        if (c == null) {
            throw new NaoEncontrado("Usuário não encontrado com ID: " + id);
        }
        return c;
    }

    private Conta porEmail(String email) {
        Conta c = porEmailOuNull(email);
        if (c == null) {
            throw new NaoEncontrado("Usuário não encontrado com email: " + email);
        }
        return c;
    }

    private Conta porEmailOuNull(String email) {
        return contas.values().stream().filter(c -> c.user.getEmail().equalsIgnoreCase(email)).findFirst().orElse(null);
    }

    private static User copiar(User u) {
        User c = new User(u.getName(), u.getEmail(), u.getFone(), u.getPassword(), u.getBirthDate());
        c.setId(u.getId());
        c.setIsAdmin(u.getIsAdmin());
        c.setIsHost(u.getIsHost());
        c.setIsActive(u.getIsActive());
        c.setCreatedAt(u.getCreatedAt());
        c.setUpdatedAt(u.getUpdatedAt());
        return c;
    }

    private static String codigo(int tamanho) {
        String alfabeto = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tamanho; i++) {
            sb.append(tamanho == 6 ? (char) ('0' + RANDOM.nextInt(10)) : alfabeto.charAt(RANDOM.nextInt(alfabeto.length())));
        }
        return sb.toString();
    }

    // ===================== http =====================

    private static User lerUser(HttpExchange ex) throws IOException {
        return JSON.readValue(ex.getRequestBody(), User.class);
    }

    private static Map<String, String> lerMapa(HttpExchange ex) throws IOException {
        byte[] corpo = ex.getRequestBody().readAllBytes();
        return corpo.length == 0 ? new HashMap<>() : JSON.readValue(corpo, new TypeReference<Map<String, String>>() { });
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String par : raw.split("&")) {
            String[] kv = par.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    private static void responderJson(HttpExchange ex, int status, Object corpo) throws IOException {
        responder(ex, status, "application/json", JSON.writeValueAsString(corpo));
    }

    private static void responder(HttpExchange ex, int status, String tipo, String corpo) throws IOException {
        byte[] bytes = corpo == null ? new byte[0] : corpo.getBytes(StandardCharsets.UTF_8);
        if (tipo != null) ex.getResponseHeaders().add("Content-Type", tipo);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    private static final class NaoEncontrado extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NaoEncontrado(String m) {
            super(m);
        }
    }

    private static final class CredenciaisInvalidas extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CredenciaisInvalidas(String m) {
            super(m);
        }
    }
}
