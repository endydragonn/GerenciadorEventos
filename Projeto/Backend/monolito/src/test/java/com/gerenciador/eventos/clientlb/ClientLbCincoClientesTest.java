package com.gerenciador.eventos.clientlb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.gerenciador.eventos.admissionclient.AdmissionClient;
import com.gerenciador.eventos.catalogclient.CatalogClient;
import com.gerenciador.eventos.identityclient.IdentityClient;
import com.gerenciador.eventos.reservationclient.ReservationClient;
import com.gerenciador.eventos.stockclient.StockClient;
import com.sun.net.httpserver.HttpServer;

/**
 * Balanceamento no cliente (trilha C da #197) pelos 5 {@code *Client} REAIS, sem Docker, sem Spring e sem banco: "pods" são
 * servidores HTTP do JDK em 127.0.0.1/2/3 na MESMA porta (o resolver devolve só o IP; a porta é a da URL, como num Service headless), e
 * o DNS é falso e mutável (1 → 3 → 2 endereços). O teste mora no monólito porque é o único módulo que enxerga os 5 clientes sem ciclo
 * de módulos Maven. Roda sozinho com
 * {@code mvn -pl monolito -am test -Dtest=ClientLbCincoClientesTest -Dsurefire.failIfNoSpecifiedTests=false}.
 */
class ClientLbCincoClientesTest {

    private static final String HOST = "pods.teste";
    private static final long TTL_MS = 500;
    private static final long FOLGA_TTL_MS = 800;

    /** Um cliente real e a chamada GET mais simples dele. */
    private record Alvo(String nome, Runnable chamada, AutoCloseable cliente) { }

    @FunctionalInterface
    private interface Fabrica {
        Alvo criar(String url, ClientLb lb, Duration resposta);
    }

    private static Stream<Fabrica> clientes() {
        Duration conectar = Duration.ofSeconds(1);
        Duration poolMax = Duration.ofSeconds(5);
        Duration evict = Duration.ofSeconds(20);
        Fabrica reservation = (url, lb, resposta) -> {
            ReservationClient c = new ReservationClient(url, "t", 20, conectar, poolMax, resposta, evict, lb);
            return new Alvo("reservation-client", () -> c.disponibilidade(1L), c::close);
        };
        Fabrica stock = (url, lb, resposta) -> {
            StockClient c = new StockClient(url, "t", 20, conectar, poolMax, resposta, evict, lb);
            return new Alvo("stock-client", () -> c.findByEventId(1L), c::close);
        };
        Fabrica catalog = (url, lb, resposta) -> {
            CatalogClient c = new CatalogClient(url, "t", 20, conectar, poolMax, resposta, evict, lb);
            return new Alvo("catalog-client", () -> c.exists(1L), c::close);
        };
        Fabrica identity = (url, lb, resposta) -> {
            IdentityClient c = new IdentityClient(url, "t", 20, conectar, poolMax, resposta, evict, lb);
            return new Alvo("identity-client", () -> c.exists(1L), c::close);
        };
        Fabrica admission = (url, lb, resposta) -> {
            AdmissionClient c = new AdmissionClient(url, "t", 20, conectar, poolMax, resposta, evict, 5, Duration.ofSeconds(5),
                    System::nanoTime, lb);
            return new Alvo("admission-client", () -> c.result(1L, "k"), c::close);
        };
        return Stream.of(reservation, stock, catalog, identity, admission);
    }

    /** Servidores em 127.0.0.1..n, mesma porta, contando as requisições de cada um. */
    private static final class Pods implements AutoCloseable {
        final List<HttpServer> servidores = new ArrayList<>();
        final List<AtomicInteger> contagem = new ArrayList<>();
        final List<InetAddress> ips = new ArrayList<>();
        final int porta;

        Pods(int n, long atrasoMs) throws IOException {
            int p = 0;
            for (int i = 0; i < n; i++) {
                InetAddress ip = InetAddress.getByAddress(new byte[] {127, 0, 0, (byte) (i + 1)});
                HttpServer s = HttpServer.create(new InetSocketAddress(ip, p), 0);
                p = s.getAddress().getPort();
                AtomicInteger recebidas = new AtomicInteger();
                s.createContext("/", ex -> {
                    recebidas.incrementAndGet();
                    try {
                        Thread.sleep(atrasoMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] corpo = "true".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, corpo.length);
                    ex.getResponseBody().write(corpo);
                    ex.close();
                });
                s.setExecutor(Executors.newCachedThreadPool());
                s.start();
                servidores.add(s);
                contagem.add(recebidas);
                ips.add(ip);
            }
            porta = p;
        }

        int recebidas(int i) {
            return contagem.get(i).get();
        }

        int total() {
            return contagem.stream().mapToInt(AtomicInteger::get).sum();
        }

        String url() {
            return "http://" + HOST + ":" + porta;
        }

        @Override
        public void close() {
            servidores.forEach(s -> s.stop(0));
        }
    }

    /** DNS falso e mutável: o que o Service headless devolveria a cada momento. */
    private static final class DnsFalso implements DnsResolver {
        private final AtomicReference<InetAddress[]> atual = new AtomicReference<>(new InetAddress[0]);

        void enderecos(InetAddress... novos) {
            atual.set(novos);
        }

        @Override
        public InetAddress[] resolve(String host) {
            return atual.get().clone();
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }

    private static void emParalelo(int n, Runnable chamada) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<?>> feitas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            feitas.add(pool.submit(() -> {
                largada.await();
                try {
                    chamada.run();
                } catch (RuntimeException ignorada) {
                    // o corpo do servidor falso pode não ser o do contrato do cliente: o que se mede é a requisição que chegou
                }
                return null;
            }));
        }
        largada.countDown();
        for (Future<?> f : feitas) {
            f.get();
        }
        pool.shutdown();
    }

    @ParameterizedTest
    @MethodSource("clientes")
    void umTresDoisEnderecosTtlERecusa(Fabrica fabrica) throws Exception {
        try (Pods pods = new Pods(3, 300)) {
            DnsFalso dns = new DnsFalso();
            Alvo alvo = fabrica.criar(pods.url(), new ClientLb(true, TTL_MS, dns), Duration.ofSeconds(5));
            try (AutoCloseable fecha = alvo.cliente()) {
                // 1 endereço: tudo no primeiro
                dns.enderecos(pods.ips.get(0));
                emParalelo(4, alvo.chamada());
                assertEquals(4, pods.recebidas(0), alvo.nome() + ": com 1 endereço tudo vai nele");

                // 3 endereços: depois do TTL as conexões novas se espalham pelos três
                dns.enderecos(pods.ips.get(0), pods.ips.get(1), pods.ips.get(2));
                Thread.sleep(FOLGA_TTL_MS);
                int base0 = pods.recebidas(0);
                emParalelo(9, alvo.chamada());
                assertEquals(13, pods.total(), alvo.nome() + ": as 9 requisições chegaram");
                assertTrue(pods.recebidas(0) - base0 >= 2 && pods.recebidas(1) >= 2 && pods.recebidas(2) >= 2,
                        alvo.nome() + ": as conexões novas se espalham pelos 3 endereços (esperado ~3 cada)");

                // 2 endereços (o do meio saiu): depois do TTL o pool não tem mais conexão para ele
                dns.enderecos(pods.ips.get(0), pods.ips.get(2));
                Thread.sleep(FOLGA_TTL_MS);
                int meio = pods.recebidas(1);
                int primeiro = pods.recebidas(0);
                int terceiro = pods.recebidas(2);
                emParalelo(8, alvo.chamada());
                emParalelo(4, alvo.chamada());
                assertEquals(meio, pods.recebidas(1), alvo.nome() + ": o endereço que saiu não recebe mais nada depois do TTL");
                assertTrue(pods.recebidas(0) > primeiro && pods.recebidas(2) > terceiro, alvo.nome() + ": os dois que ficaram recebem");
                assertEquals(12, pods.recebidas(0) - primeiro + pods.recebidas(2) - terceiro);

                // um endereço que recusa a conexão é pulado: 127.0.0.9 não tem servidor (RST) e a requisição vai ao próximo
                InetAddress morto = InetAddress.getByAddress(new byte[] {127, 0, 0, 9});
                dns.enderecos(morto, pods.ips.get(0));
                Thread.sleep(FOLGA_TTL_MS);
                int antesDoPulo = pods.recebidas(0);
                emParalelo(6, alvo.chamada());
                assertEquals(6, pods.recebidas(0) - antesDoPulo, alvo.nome() + ": as 6 requisições passam pelo endereço vivo");
            }
        }
    }

    @ParameterizedTest
    @MethodSource("clientes")
    void timeoutDeRespostaDeHojeContinuaComOTtlSomado(Fabrica fabrica) throws Exception {
        try (Pods pods = new Pods(1, 2500)) {
            DnsFalso dns = new DnsFalso();
            dns.enderecos(pods.ips.get(0));
            Alvo alvo = fabrica.criar(pods.url(), new ClientLb(true, 15_000, dns), Duration.ofMillis(400));
            try (AutoCloseable fecha = alvo.cliente()) {
                long t0 = System.nanoTime();
                try {
                    alvo.chamada().run();
                } catch (RuntimeException esperada) {
                    // estourou a resposta
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertTrue(ms >= 300 && ms < 2000, alvo.nome() + ": estourou em " + ms + " ms (timeout de resposta de 400 ms)");
            }
        }
    }

    @ParameterizedTest
    @MethodSource("clientes")
    void desligadoMontaComoHojeEUsaOResolverDoSistema(Fabrica fabrica) throws Exception {
        try (Pods pods = new Pods(1, 0)) {
            Alvo alvo = fabrica.criar(pods.url(), ClientLb.desligado(), Duration.ofSeconds(2));
            try (AutoCloseable fecha = alvo.cliente()) {
                try {
                    alvo.chamada().run();
                } catch (RuntimeException esperada) {
                    // o nome falso não existe no DNS do sistema
                }
                assertEquals(0, pods.recebidas(0), alvo.nome() + ": desligado não usa o resolver da trilha (o nome falso não resolve)");
            }
        }
    }
}
