package com.gerenciador.eventos.identityclient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.gerenciador.eventos.identityclient.api.IdentityNaoEncontradoException;
import com.gerenciador.eventos.identityclient.api.IdentityUnavailableException;
import com.gerenciador.eventos.identityclient.api.LoginResult;
import com.gerenciador.eventos.identityclient.api.RecaptchaResponse;
import com.gerenciador.eventos.identityclient.api.User;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * O {@link IdentityClient} diante de um identity fora do ar e dos marcadores do contrato (issue #36, spec "Cliente"): porta sem
 * ninguém, resposta 500 e resposta mais lenta que o timeout viram {@link IdentityUnavailableException} — nunca "não encontrado",
 * "senha errada" nem {@code exists == false} — e o cliente não repete a chamada (sem retry). 404 só é "não existe" COM o cabeçalho
 * {@link IdentityNaoEncontradoException#HEADER}; 401 só é "credenciais inválidas" COM
 * {@link IdentityClient#HEADER_CREDENCIAIS_INVALIDAS}. Sem Spring nem banco: servidores HTTP do JDK na porta sorteada.
 */
public class IdentityClientIndisponivelTest {

    private static final Duration TIMEOUT_CURTO = Duration.ofMillis(300);

    private static IdentityClient cliente(String url) {
        return new IdentityClient(url, "token-de-teste", 4, TIMEOUT_CURTO, TIMEOUT_CURTO, TIMEOUT_CURTO, Duration.ofSeconds(20));
    }

    /** Toda chamada que o gateway faz, inclusive as de credencial e os {@code exists}, falha como indisponível. */
    private static void todasFalhamComoIndisponivel(IdentityClient c) {
        assertThrows(IdentityUnavailableException.class, () -> c.exists(1L), "exists nunca devolve false por falha");
        assertThrows(IdentityUnavailableException.class, () -> c.walletExists(1L), "walletExists nunca devolve false por falha");
        assertThrows(IdentityUnavailableException.class, () -> c.findById(1L), "findById não vira 'não encontrado'");
        assertThrows(IdentityUnavailableException.class, () -> c.findByEmail("a@x.com"));
        assertThrows(IdentityUnavailableException.class, () -> c.login("a@x.com", "senha"), "login não vira 'senha errada' (null)");
        assertThrows(IdentityUnavailableException.class, () -> c.verifyPasswordResetMfaCode(1L, "x"), "MFA não vira false");
        assertThrows(IdentityUnavailableException.class, () -> c.createUser(new User()));
        assertThrows(IdentityUnavailableException.class, () -> c.findNames(List.of(1L, 2L)), "nomes: o gateway decide tolerar");
        assertThrows(IdentityUnavailableException.class, () -> c.verifyEmailAndCreateUser("a@x.com", "123456"));
        assertThrows(IdentityUnavailableException.class, () -> c.getByUserId(1L));
        assertThrows(IdentityUnavailableException.class, () -> c.verify("t", "login"));
    }

    @Test
    void portaSemServicoViraIndisponivel() throws Exception {
        int porta;
        try (ServerSocket s = new ServerSocket(0)) {
            porta = s.getLocalPort(); // fechada ao sair do try: conexão recusada
        }
        IdentityClient c = cliente("http://127.0.0.1:" + porta);
        try {
            todasFalhamComoIndisponivel(c);
        } finally {
            c.close();
        }
    }

    @Test
    void resposta500ViraIndisponivelSemRetry() throws Exception {
        AtomicInteger chamadas = new AtomicInteger();
        HttpServer server = servidor(ex -> responder(ex, 500, chamadas));
        IdentityClient c = cliente("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            todasFalhamComoIndisponivel(c);
            chamadas.set(0);
            assertThrows(IdentityUnavailableException.class, () -> c.exists(1L));
            assertEquals(1, chamadas.get(), "uma falha = uma chamada (sem retry automático)");
            chamadas.set(0);
            assertThrows(IdentityUnavailableException.class, () -> c.createUser(new User()));
            assertEquals(1, chamadas.get(), "o POST de cadastro também não é repetido");
        } finally {
            c.close();
            server.stop(0);
        }
    }

    @Test
    void respostaMaisLentaQueOTimeoutViraIndisponivelSemRetry() throws Exception {
        AtomicInteger chamadas = new AtomicInteger();
        HttpServer server = servidor(ex -> {
            try {
                Thread.sleep(TIMEOUT_CURTO.toMillis() * 4);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            responder(ex, 200, chamadas);
        });
        IdentityClient c = cliente("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            assertThrows(IdentityUnavailableException.class, () -> c.exists(1L));
            assertThrows(IdentityUnavailableException.class, () -> c.login("a@x.com", "senha"));
            Thread.sleep(TIMEOUT_CURTO.toMillis() * 5); // deixa o servidor terminar as respostas atrasadas
            assertEquals(2, chamadas.get(), "cada chamada chegou uma vez só (sem retry)");
        } finally {
            c.close();
            server.stop(0);
        }
    }

    /**
     * Um 404 que NÃO veio do identity (rota inexistente, URL de outro serviço, proxy) não pode virar "não existe": só o 404 com o
     * cabeçalho do contrato é; o resto é indisponibilidade.
     */
    @Test
    void resposta404SemOMarcadorViraIndisponivel() throws Exception {
        HttpServer server = servidor(ex -> responder(ex, 404, new AtomicInteger()));
        IdentityClient c = cliente("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            assertThrows(IdentityUnavailableException.class, () -> c.findById(1L));
            assertThrows(IdentityUnavailableException.class, () -> c.findByEmail("a@x.com"));
            assertThrows(IdentityUnavailableException.class, () -> c.getByUserId(1L));
            assertThrows(IdentityUnavailableException.class, () -> c.exists(1L));
            assertThrows(IdentityUnavailableException.class, () -> c.walletExists(1L));
        } finally {
            c.close();
            server.stop(0);
        }
    }

    @Test
    void resposta404ComOMarcadorEhNaoEncontrado() throws Exception {
        HttpServer server = servidor(ex -> {
            ex.getResponseHeaders().add(IdentityNaoEncontradoException.HEADER, "true");
            responder(ex, 404, new AtomicInteger());
        });
        IdentityClient c = cliente("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            assertThrows(IdentityNaoEncontradoException.class, () -> c.findById(1L));
            assertThrows(IdentityNaoEncontradoException.class, () -> c.findByEmail("a@x.com"));
            assertThrows(IdentityNaoEncontradoException.class, () -> c.getByUserId(1L));
            assertThrows(IdentityNaoEncontradoException.class, () -> c.requestPasswordResetMfa(1L));
            // as rotas /exists respondem 200 true/false: um 404 ali, mesmo marcado, é falha e não "não existe"
            assertThrows(IdentityUnavailableException.class, () -> c.exists(1L));
            assertThrows(IdentityUnavailableException.class, () -> c.walletExists(1L));
        } finally {
            c.close();
            server.stop(0);
        }
    }

    /** 401 SEM o marcador é o filtro do token interno (token errado): falha de configuração, não "senha errada" de todo mundo. */
    @Test
    void resposta401SemOMarcadorViraIndisponivel() throws Exception {
        HttpServer server = servidor(ex -> responder(ex, 401, new AtomicInteger()));
        IdentityClient c = cliente("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            assertThrows(IdentityUnavailableException.class, () -> c.login("a@x.com", "senha"));
            assertThrows(IdentityUnavailableException.class, () -> c.verifyPasswordResetMfaCode(1L, "x"));
            assertThrows(IdentityUnavailableException.class, () -> c.findById(1L));
        } finally {
            c.close();
            server.stop(0);
        }
    }

    @Test
    void resposta401ComOMarcadorEhCredencialInvalidaSoNasRotasDeCredencial() throws Exception {
        HttpServer server = servidor(ex -> {
            ex.getResponseHeaders().add(IdentityClient.HEADER_CREDENCIAIS_INVALIDAS, "true");
            responder(ex, 401, new AtomicInteger());
        });
        IdentityClient c = cliente("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            assertNull(c.login("a@x.com", "errada"), "login com credencial inválida devolve null, como o UserService");
            assertFalse(c.verifyPasswordResetMfaCode(1L, "errado"));
            // numa rota que não tem credencial o marcador é anomalia
            assertThrows(IdentityUnavailableException.class, () -> c.changePassword(1L, "a", "b"));
            assertThrows(IdentityUnavailableException.class, () -> c.verifyEmailAndCreateUser("a@x.com", "123456"));
        } finally {
            c.close();
            server.stop(0);
        }
    }

    @Test
    void resposta400ViraIllegalArgumentComOTextoDoServico() throws Exception {
        HttpServer server = servidor(ex -> responder(ex, 400, new AtomicInteger()));
        IdentityClient c = cliente("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> c.createUser(new User()));
            assertEquals("erro", e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> c.verifyEmailAndCreateUser("a@x.com", "000000"));
            assertThrows(IllegalArgumentException.class, () -> c.changePasswordWithMfa(1L, "novaSenha", "x"));
        } finally {
            c.close();
            server.stop(0);
        }
    }

    /** O caminho feliz também fixa o contrato: token interno em toda chamada, e-mail codificado como valor, JSON do login. */
    @Test
    void contratoFeliz() throws Exception {
        List<String> vistas = new CopyOnWriteArrayList<>();
        HttpServer server = servidor(ex -> {
            vistas.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery())
                    + " token=" + ex.getRequestHeaders().getFirst(IdentityClient.HEADER_TOKEN_INTERNO));
            String path = ex.getRequestURI().getRawPath();
            String json = switch (path) {
                case "/internal/users/login" -> "{\"user\":{\"user_id\":7,\"email\":\"a+b@x.com\"},\"token\":\"jwt\"}";
                case "/internal/users/names" -> "{\"1\":\"Ana\",\"2\":\"Beto\"}";
                case "/internal/recaptcha/verify" -> "{\"status\":500,\"body\":{\"success\":false,\"error\":\"x\"}}";
                case "/internal/users/7/exists" -> "true";
                default -> "{\"user_id\":7,\"email\":\"a+b@x.com\"}";
            };
            responderJson(ex, json);
        });
        IdentityClient c = cliente("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            LoginResult login = c.login("a+b@x.com", "senha");
            assertEquals("jwt", login.token());
            assertEquals(7L, login.user().getId());
            assertTrue(c.exists(7L));
            assertEquals(Map.of(1L, "Ana", 2L, "Beto"), c.findNames(List.of(1L, 2L)));
            assertNotNull(c.findByEmail("a+b@x.com"));
            RecaptchaResponse r = c.verify("t", "login");
            assertEquals(500, r.status(), "o 500 do reCAPTCHA vem no envelope, não como erro HTTP");
            assertEquals(Boolean.FALSE, r.body().get("success"));
            assertEquals(Map.of(), c.findNames(List.of()), "lista vazia nem chama o serviço");

            assertTrue(vistas.contains("POST /internal/users/login token=token-de-teste"), vistas.toString());
            assertTrue(vistas.contains("GET /internal/users/7/exists token=token-de-teste"), vistas.toString());
            assertTrue(vistas.contains("GET /internal/users/by-email?email=a%2Bb%40x.com token=token-de-teste"), vistas.toString());
            assertEquals(5, vistas.size());
        } finally {
            c.close();
            server.stop(0);
        }
    }

    @Test
    void recaptcha500GenericoDoServicoEhIndisponivel() throws Exception {
        HttpServer server = servidor(ex -> responder(ex, 500, new AtomicInteger()));
        IdentityClient c = cliente("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            assertThrows(IdentityUnavailableException.class, () -> c.verify("t", "login"));
        } finally {
            c.close();
            server.stop(0);
        }
    }

    @Test
    void construcaoSemTokenFalha() {
        for (String token : new String[] {null, "", "   "}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new IdentityClient("http://127.0.0.1:1", token));
            assertTrue(e.getMessage().contains("INTERNAL_API_TOKEN"));
        }
    }

    private static HttpServer servidor(Consumer<HttpExchange> tratar) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", tratar::accept);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        return server;
    }

    private static void responder(HttpExchange ex, int status, AtomicInteger chamadas) {
        chamadas.incrementAndGet();
        try {
            byte[] corpo = (status == 200 ? "true" : "erro").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "text/plain");
            ex.sendResponseHeaders(status, corpo.length);
            ex.getResponseBody().write(corpo);
        } catch (IOException e) {
            // o cliente já desistiu (timeout): a conexão fechou do outro lado
        } finally {
            ex.close();
        }
    }

    private static void responderJson(HttpExchange ex, String json) {
        try {
            byte[] corpo = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, corpo.length);
            ex.getResponseBody().write(corpo);
        } catch (IOException e) {
            // cliente desistiu
        } finally {
            ex.close();
        }
    }
}
