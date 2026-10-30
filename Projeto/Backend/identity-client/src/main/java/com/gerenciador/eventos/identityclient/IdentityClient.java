package com.gerenciador.eventos.identityclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import static com.gerenciador.eventos.identityclient.IdentityErros.chamar;
import static com.gerenciador.eventos.identityclient.IdentityErros.corpo;
import static com.gerenciador.eventos.identityclient.IdentityErros.erro;
import static com.gerenciador.eventos.identityclient.IdentityErros.mapa;
import static com.gerenciador.eventos.identityclient.IdentityErros.tentar;
import static com.gerenciador.eventos.identityclient.IdentityErros.validarUserId;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gerenciador.eventos.clientlb.ClientLb;
import com.gerenciador.eventos.identityclient.api.EmailVerificationApi;
import com.gerenciador.eventos.identityclient.api.IdentityNaoEncontradoException;
import com.gerenciador.eventos.identityclient.api.IdentityUnavailableException;
import com.gerenciador.eventos.identityclient.api.LoginResult;
import com.gerenciador.eventos.identityclient.api.MyWallet;
import com.gerenciador.eventos.identityclient.api.MyWalletApi;
import com.gerenciador.eventos.identityclient.api.RecaptchaApi;
import com.gerenciador.eventos.identityclient.api.RecaptchaResponse;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.identityclient.api.UserApi;

/**
 * Cliente HTTP da API interna do serviço identity (issue #36): o salto {@code gateway -> identity}. Cada método das
 * interfaces é UMA chamada síncrona a {@code /internal/**}; nada é cacheado nem repetido. O contrato completo (verbos, paths,
 * corpos, status) está tabelado em {@code identity-client/README.md}; este é o resumo.
 *
 * <pre>
 * UserApi          POST /internal/users | GET /internal/users/{id} | GET /internal/users/by-email?email= |
 *                  PUT /internal/users/{id} | DELETE /internal/users/{id} | POST /internal/users/validate |
 *                  POST /internal/users/login | PUT /internal/users/{id}/password |
 *                  POST /internal/users/{id}/password-reset-mfa | POST /internal/users/{id}/password-reset-mfa/verify |
 *                  PUT /internal/users/{id}/password-with-mfa | GET /internal/users/{id}/exists | POST /internal/users/names
 * EmailVerificationApi  POST /internal/registrations | POST /internal/registrations/verify | POST /internal/registrations/resend
 * MyWalletApi      GET /internal/wallets/{userId} | PUT /internal/wallets/{userId} | GET /internal/wallets/{userId}/exists
 * RecaptchaApi     POST /internal/recaptcha/verify
 * </pre>
 *
 * <p><b>Pool e timeouts</b> como o {@code CatalogClient} (Apache HttpClient 5, keep-alive, {@code maxConnections} no total e por
 * rota, >= às threads do Tomcat do gateway): padrão 400 conexões, conectar 2 s, esperar conexão do pool 5 s, resposta 5 s,
 * {@code evictIdle} 20 s. <b>Sem retry automático</b>: um POST repetido às cegas poderia cadastrar duas vezes ou mandar dois
 * e-mails.</p>
 *
 * <p><b>Erros</b> (o corpo da resposta de erro é a mensagem, texto puro): 400 => {@link IllegalArgumentException}; 404 COM o
 * cabeçalho {@link IdentityNaoEncontradoException#HEADER} => {@link IdentityNaoEncontradoException}; 401 COM o cabeçalho
 * {@link #HEADER_CREDENCIAIS_INVALIDAS} (só login e verificação MFA) => {@code null}/{@code false}; rede, timeout, 5xx, 404 sem
 * o cabeçalho, 401 sem ele (token interno errado) e qualquer outro status => {@link IdentityUnavailableException}.
 * {@link #exists} e {@link #walletExists} nunca devolvem {@code false} por falha.</p>
 */
public class IdentityClient implements UserApi, EmailVerificationApi, MyWalletApi, RecaptchaApi {

    /** Header do token de serviço compartilhado que o identity exige em toda requisição (menos o JWKS). */
    public static final String HEADER_TOKEN_INTERNO = "X-Internal-Token";

    /**
     * Marca o 401 "credenciais/código inválidos" do identity (login e verificação MFA). Um 401 SEM ele é o filtro do token interno
     * (token errado: falha de configuração) e NÃO pode virar "senha errada" para todos os usuários.
     */
    public static final String HEADER_CREDENCIAIS_INVALIDAS = "X-Identity-Credenciais-Invalidas";

    private static final String USERS = "/internal/users";
    private static final String REGISTRATIONS = "/internal/registrations";
    private static final String WALLETS = "/internal/wallets";
    private static final String ID = "/{id}";

    private final RestClient http;
    private final CloseableHttpClient httpClient;

    /** Com os padrões da spec: 400 conexões, conectar 2 s, pool 5 s, resposta 5 s, evict 20 s. */
    public IdentityClient(String baseUrl, String internalToken) {
        this(baseUrl, internalToken, 400, Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(20));
    }

    /** @param internalToken {@code INTERNAL_API_TOKEN}, enviado em TODA chamada; obrigatório (o identity responde 401 sem ele) */
    public IdentityClient(String baseUrl, String internalToken, int maxConnections, Duration connectTimeout,
            Duration connectionRequestTimeout, Duration responseTimeout, Duration evictIdle) {
        this(baseUrl, internalToken, maxConnections, connectTimeout, connectionRequestTimeout, responseTimeout, evictIdle,
                ClientLb.desligado());
    }

    /** @param lb balanceamento no cliente (trilha C da #197): soma resolver round-robin, TTL e reuso FIFO ao pool; desligado não muda nada */
    public IdentityClient(String baseUrl, String internalToken, int maxConnections, Duration connectTimeout,
            Duration connectionRequestTimeout, Duration responseTimeout, Duration evictIdle,
            ClientLb lb) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_TOKEN não configurado: o cliente do identity precisa do token de "
                    + "serviço (o mesmo valor do serviço identity). Gere um com `openssl rand -hex 32` e ponha no Projeto/.env.");
        }
        ConnectionConfig.Builder conexao = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()));
        PoolingHttpClientConnectionManagerBuilder construtor = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnections);
        lb.aplicar(construtor, conexao);
        PoolingHttpClientConnectionManager pool = construtor.setDefaultConnectionConfig(conexao.build()).build();
        this.httpClient = HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectionRequestTimeout.toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()))
                        .build())
                .evictIdleConnections(TimeValue.ofMilliseconds(evictIdle.toMillis()))
                .evictExpiredConnections()
                .disableAutomaticRetries()
                .build();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HEADER_TOKEN_INTERNO, internalToken)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .messageConverters(c -> {
                    c.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    c.add(0, new MappingJackson2HttpMessageConverter(mapper));
                })
                .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> {
                    throw erro(resp.getStatusCode().value(),
                            resp.getHeaders().containsKey(IdentityNaoEncontradoException.HEADER),
                            resp.getHeaders().containsKey(HEADER_CREDENCIAIS_INVALIDAS),
                            new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8));
                })
                .build();
    }

    /** Fecha o pool (destroyMethod do bean; os testes também). */
    public void close() throws IOException {
        httpClient.close();
    }

    // ===================== UserApi =====================

    @Override
    public User createUser(User user) {
        return corpo(chamar(() -> http.post().uri(USERS).contentType(MediaType.APPLICATION_JSON).body(user)
                .retrieve().body(User.class)));
    }

    @Override
    public User findById(Long id) {
        return corpo(chamar(() -> http.get().uri(USERS + ID, id).retrieve().body(User.class)));
    }

    @Override
    public User findByEmail(String email) {
        // o e-mail entra como VALOR de variável ({email}), não no template: "+" vira %2B, "@" %40, "&" não abre outro parâmetro
        return corpo(chamar(() -> http.get().uri(b -> b.path(USERS + "/by-email").queryParam("email", "{email}").build(email))
                .retrieve().body(User.class)));
    }

    @Override
    public User updateUser(User user) {
        if (user.getId() == null) {
            throw new IllegalArgumentException("ID do usuário não pode ser nulo");
        }
        return corpo(chamar(() -> http.put().uri(USERS + ID, user.getId()).contentType(MediaType.APPLICATION_JSON)
                .body(user).retrieve().body(User.class)));
    }

    @Override
    public void deactivateUser(Long id) {
        chamar(() -> http.delete().uri(USERS + ID, id).retrieve().toBodilessEntity());
    }

    /** {@code ""} = válido (200 com corpo vazio); texto = os erros. */
    @Override
    public String getValidationErrors(User user) {
        String erros = chamar(() -> http.post().uri(USERS + "/validate").contentType(MediaType.APPLICATION_JSON).body(user)
                .retrieve().body(String.class));
        return erros == null ? "" : erros;
    }

    /** 401 com o marcador de credenciais => {@code null} (como o {@code UserService} sempre fez). */
    @Override
    public LoginResult login(String email, String password) {
        Map<String, String> corpo = mapa("email", email, "password", password);
        try {
            return corpo(tentar(() -> http.post().uri(USERS + "/login").contentType(MediaType.APPLICATION_JSON).body(corpo)
                    .retrieve().body(LoginResult.class)));
        } catch (IdentityErros.CredenciaisInvalidas ex) {
            return null;
        }
    }

    @Override
    public boolean changePassword(Long userId, String oldPassword, String newPassword) {
        Map<String, String> corpo = mapa("oldPassword", oldPassword, "newPassword", newPassword);
        return corpo(chamar(() -> http.put().uri(USERS + "/{id}/password", userId).contentType(MediaType.APPLICATION_JSON)
                .body(corpo).retrieve().body(Boolean.class)));
    }

    @Override
    public User requestPasswordResetMfa(Long userId) {
        return corpo(chamar(() -> http.post().uri(USERS + "/{id}/password-reset-mfa", userId).retrieve().body(User.class)));
    }

    /** 401 com o marcador de credenciais => {@code false} (código inválido ou expirado). */
    @Override
    public boolean verifyPasswordResetMfaCode(Long userId, String mfaCode) {
        Map<String, String> corpo = mapa("mfaCode", mfaCode);
        try {
            tentar(() -> http.post().uri(USERS + "/{id}/password-reset-mfa/verify", userId)
                    .contentType(MediaType.APPLICATION_JSON).body(corpo).retrieve().toBodilessEntity());
            return true;
        } catch (IdentityErros.CredenciaisInvalidas ex) {
            return false;
        }
    }

    @Override
    public void changePasswordWithMfa(Long userId, String newPassword, String mfaCode) {
        Map<String, String> corpo = mapa("newPassword", newPassword, "mfaCode", mfaCode);
        chamar(() -> http.put().uri(USERS + "/{id}/password-with-mfa", userId).contentType(MediaType.APPLICATION_JSON)
                .body(corpo).retrieve().toBodilessEntity());
    }

    /** {@code GET /{id}/exists}. Falha => {@link IdentityUnavailableException}, NUNCA {@code false}. */
    @Override
    public boolean exists(Long userId) {
        return existe(USERS + "/{id}/exists", userId);
    }

    @Override
    public Map<Long, String> findNames(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nomes = chamar(() -> http.post().uri(USERS + "/names").contentType(MediaType.APPLICATION_JSON)
                .body(new ArrayList<>(ids)).retrieve().body(new ParameterizedTypeReference<Map<Long, String>>() { }));
        return nomes == null ? Map.of() : nomes;
    }

    // ===================== EmailVerificationApi =====================

    @Override
    public void requestEmailVerification(String email, String fone, String userName, String password, String birthDate) {
        Map<String, String> corpo = mapa("email", email, "fone", fone, "userName", userName, "password", password,
                "birthDate", birthDate);
        chamar(() -> http.post().uri(REGISTRATIONS).contentType(MediaType.APPLICATION_JSON).body(corpo)
                .retrieve().toBodilessEntity());
    }

    @Override
    public LoginResult verifyEmailAndCreateUser(String email, String verificationCode) {
        Map<String, String> corpo = mapa("email", email, "verificationCode", verificationCode);
        return corpo(chamar(() -> http.post().uri(REGISTRATIONS + "/verify").contentType(MediaType.APPLICATION_JSON)
                .body(corpo).retrieve().body(LoginResult.class)));
    }

    @Override
    public void resendVerificationCode(String email) {
        Map<String, String> corpo = mapa("email", email);
        chamar(() -> http.post().uri(REGISTRATIONS + "/resend").contentType(MediaType.APPLICATION_JSON).body(corpo)
                .retrieve().toBodilessEntity());
    }

    // ===================== MyWalletApi =====================

    @Override
    public MyWallet getByUserId(Long userId) {
        validarUserId(userId);
        return corpo(chamar(() -> http.get().uri(WALLETS + "/{userId}", userId).retrieve().body(MyWallet.class)));
    }

    @Override
    public MyWallet ensureExists(Long userId) {
        validarUserId(userId);
        return corpo(chamar(() -> http.put().uri(WALLETS + "/{userId}", userId).retrieve().body(MyWallet.class)));
    }

    /** Falha => {@link IdentityUnavailableException}, NUNCA {@code false}. */
    @Override
    public boolean walletExists(Long userId) {
        validarUserId(userId);
        return existe(WALLETS + "/{id}/exists", userId);
    }

    // ===================== RecaptchaApi =====================

    /** O serviço responde 200 com o envelope {@code {status, body}}; só falha de transporte/5xx genérico é indisponibilidade. */
    @Override
    public RecaptchaResponse verify(String token, String action) {
        Map<String, String> corpo = mapa("token", token, "action", action);
        RecaptchaResponse r = corpo(chamar(() -> http.post().uri("/internal/recaptcha/verify")
                .contentType(MediaType.APPLICATION_JSON).body(corpo).retrieve().body(RecaptchaResponse.class)));
        if (r.body() == null) {
            throw new IdentityUnavailableException("Serviço identity respondeu o reCAPTCHA sem corpo", null);
        }
        return r;
    }

    // ===================== utilitários =====================

    /** As rotas {@code /exists} respondem 200 true/false: um 404 (mesmo marcado) ali é falha, não "não existe". */
    private boolean existe(String template, Long id) {
        try {
            return corpo(chamar(() -> http.get().uri(template, id).retrieve().body(Boolean.class)));
        } catch (IdentityNaoEncontradoException ex) {
            throw new IdentityUnavailableException("Serviço identity respondeu 404 em exists: " + ex.getMessage(), ex);
        }
    }
}
