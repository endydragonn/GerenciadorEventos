package com.gerenciador.eventos.admission;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Redis TRAVADO com a conexão JÁ ESTABELECIDA (issue #41): o admission fala com o Redis REAL por um proxy TCP do teste; depois de uma
 * contagem normal o proxy "congela" (segue com os sockets abertos e para de repassar), como um Redis em pausa ou uma partição de rede.
 * Cada comando passa a esperar o timeout de 200 ms e o serviço responde 503 (a falha que o disjuntor do gateway conta), sem esperar o
 * Redis. É o cenário que importa em produção: a conexão do Lettuce é compartilhada e permanente. (Um Redis que nem completa o handshake
 * de uma conexão NOVA custa mais que isso ao 1º comando, mas depois de estabelecida a conexão não passa mais por ali.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "internal.api.token=token-de-teste", "spring.data.redis.host=127.0.0.1"})
class AdmissionRedisTravadoTest {

    private static final ProxyCongelavel PROXY = new ProxyCongelavel(System.getenv().getOrDefault("REDIS_HOST", "localhost"),
            Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));

    @LocalServerPort private int porta;

    @DynamicPropertySource
    static void porta(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", PROXY::porta);
    }

    @AfterAll
    static void fechar() {
        PROXY.fechar();
    }

    @Test
    void redisCongeladoComAConexaoAbertaDa503PeloTimeoutDe200ms() {
        assertEquals(200, RedisForaSupport.post(porta, RedisDeTeste.eventoNovo()).status(), "com o Redis vivo (pelo proxy) conta normalmente");

        PROXY.congelar();

        RedisForaSupport.Medida post = RedisForaSupport.post(porta, RedisDeTeste.eventoNovo());
        assertEquals(503, post.status(), "Redis travado: 503 (o disjuntor do gateway precisa contar a falha)");
        assertTrue(post.ms() >= 150 && post.ms() < 1_000,
                "falha pelo timeout de comando de 200 ms (nem imediata, nem esperando o Redis): " + post.ms() + " ms");
        RedisForaSupport.Medida get = RedisForaSupport.getRpm(porta);
        assertEquals(503, get.status());
        assertTrue(get.ms() < 1_000, "a leitura também falha pelo timeout: " + get.ms() + " ms");
    }

    /** Proxy TCP para o Redis real que pode parar de repassar (descarta o que chega) sem fechar os sockets. */
    static final class ProxyCongelavel {
        private final String hostReal;
        private final int portaReal;
        private final ServerSocket servidor;
        private final List<Socket> sockets = new ArrayList<>();
        private volatile boolean congelado;

        ProxyCongelavel(String hostReal, int portaReal) {
            this.hostReal = hostReal;
            this.portaReal = portaReal;
            try {
                this.servidor = new ServerSocket(0);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
            Thread t = new Thread(this::aceitar, "proxy-redis-congelavel");
            t.setDaemon(true);
            t.start();
        }

        int porta() {
            return servidor.getLocalPort();
        }

        void congelar() {
            congelado = true;
        }

        private void aceitar() {
            try {
                while (!servidor.isClosed()) {
                    Socket cliente = servidor.accept();
                    Socket real = new Socket(hostReal, portaReal);
                    synchronized (sockets) {
                        sockets.add(cliente);
                        sockets.add(real);
                    }
                    bombear(cliente, real);
                    bombear(real, cliente);
                }
            } catch (IOException ex) {
                // servidor fechado no @AfterAll
            }
        }

        private void bombear(Socket de, Socket para) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                try (InputStream in = de.getInputStream(); OutputStream out = para.getOutputStream()) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (!congelado) {
                            out.write(buf, 0, n);
                            out.flush();
                        }
                    }
                } catch (IOException ex) {
                    // conexão fechada
                }
            }, "proxy-redis-bomba");
            t.setDaemon(true);
            t.start();
        }

        void fechar() {
            try {
                servidor.close();
            } catch (IOException ex) {
                // ignora
            }
            synchronized (sockets) {
                for (Socket s : sockets) {
                    try {
                        s.close();
                    } catch (IOException ex) {
                        // ignora
                    }
                }
            }
        }
    }
}
