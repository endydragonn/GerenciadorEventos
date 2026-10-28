package com.gerenciador.eventos.admission;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * O contador por evento (issue #41) contra o Redis REAL: buckets de 50 ms somados numa janela de 200 ms, conversão em RPM, janela
 * que desliza (o que sai da janela deixa de contar), eventos independentes, TTL em TODA chave, atomicidade sob concorrência e
 * configuração inválida. O relógio é controlado (os bucket indexam o instante informado ao script, não o do Redis).
 */
class RpmCounterTest {

    private StringRedisTemplate redis;
    private RelogioManual relogio;
    private long evento;

    @BeforeEach
    void preparar() {
        redis = RedisDeTeste.template();
        relogio = new RelogioManual(1_700_000_000_000L);
        evento = RedisDeTeste.eventoNovo();
    }

    @AfterEach
    void limpar() {
        Set<String> chaves = redis.keys("adm:cnt:" + evento + ":*");
        if (chaves != null && !chaves.isEmpty()) {
            redis.delete(chaves);
        }
    }

    private RpmCounter contador(long bucketMs, long janelaMs) {
        return new RpmCounter(redis, relogio, bucketMs, janelaMs, 56000, 1, 0.8, 0.7);
    }

    /** RPM esperado: soma x 60000 / (janela - bucket + resto do bucket corrente + 1 ms), o bucket corrente é parcial. */
    private static long esperado(long soma, long restoMs, long bucketMs, long janelaMs) {
        return soma * 60_000 / (janelaMs - bucketMs + restoMs + 1);
    }

    @Test
    void contaNaJanelaEConverteParaRpm() {
        RpmCounter c = contador(50, 200);

        // o relógio começa no início de um bucket (resto 0): 150 ms de buckets completos + 1 ms do corrente = 151 ms cobertos
        assertEquals(esperado(1, 0, 50, 200), c.contar(evento));
        assertEquals(397, esperado(1, 0, 50, 200), "1 tentativa em 151 ms = 397 RPM");
        assertEquals(esperado(2, 0, 50, 200), c.contar(evento));
        relogio.avancar(60); // outro bucket (resto 10), mesma janela
        assertEquals(esperado(3, 10, 50, 200), c.contar(evento));
        assertEquals(esperado(3, 10, 50, 200), c.rpm(evento), "a leitura devolve o mesmo valor");
    }

    @Test
    void leituraNaoContaNemCriaChave() {
        RpmCounter c = contador(50, 200);

        assertEquals(0, c.rpm(evento));
        assertEquals(0, c.rpm(evento));
        assertTrue(redis.keys("adm:cnt:" + evento + ":*").isEmpty(), "só contar cria chave");
        assertEquals(esperado(1, 0, 50, 200), c.contar(evento), "e as leituras não contaram");
    }

    @Test
    void aJanelaDesliza() {
        RpmCounter c = contador(50, 200);
        c.contar(evento);
        c.contar(evento);
        c.contar(evento);
        assertEquals(esperado(3, 0, 50, 200), c.rpm(evento));

        relogio.avancar(150); // 3 buckets depois: o bucket das 3 tentativas ainda está na janela (4 buckets)
        assertEquals(esperado(3, 0, 50, 200), c.rpm(evento));

        relogio.avancar(50); // 4 buckets depois: saiu da janela
        assertEquals(0, c.rpm(evento));
        assertEquals(esperado(1, 0, 50, 200), c.contar(evento), "só a tentativa nova");
    }

    @Test
    void eventosSaoIndependentes() {
        RpmCounter c = contador(50, 200);
        long outro = evento + 1;
        try {
            c.contar(evento);
            c.contar(evento);
            assertEquals(esperado(1, 0, 50, 200), c.contar(outro));
            assertEquals(esperado(2, 0, 50, 200), c.rpm(evento));
        } finally {
            redis.delete(redis.keys("adm:cnt:" + outro + ":*"));
        }
    }

    @Test
    void todaChaveTemTtlDeJanelaMaisUmBucket() {
        RpmCounter c = contador(50, 200);
        c.contar(evento);
        relogio.avancar(50);
        c.contar(evento);

        Set<String> chaves = redis.keys("adm:cnt:" + evento + ":*");
        assertEquals(2, chaves.size());
        for (String chave : chaves) {
            Long ttl = redis.getExpire(chave, TimeUnit.MILLISECONDS);
            assertTrue(ttl != null && ttl > 0 && ttl <= 250, "TTL de " + chave + " = " + ttl + " ms (esperado 1..250)");
        }
    }

    @Test
    void bucketEJanelaSaoConfiguraveis() {
        RpmCounter c = contador(1000, 60_000); // RPM direto: 60 buckets de 1 s
        for (int i = 0; i < 5; i++) {
            c.contar(evento);
        }
        relogio.avancar(30_000);
        assertEquals(6, c.contar(evento), "5 + 1 na janela de 60 s = 6 RPM");
        relogio.avancar(31_000);
        assertEquals(2, c.contar(evento), "as 5 primeiras saíram da janela; ficam a 6ª (30 s) e a nova");
    }

    @Test
    void contagemConcorrenteNaoPerdeNemDuplica() throws Exception {
        RpmCounter c = contador(50, 200);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Long>> futuros = new java.util.ArrayList<>();
            for (int i = 0; i < 400; i++) {
                futuros.add(pool.submit(() -> c.contar(evento)));
            }
            for (Future<Long> f : futuros) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(esperado(400, 0, 50, 200), c.rpm(evento), "400 tentativas no mesmo instante: nenhuma perdida, nenhuma contada duas vezes");
    }

    /**
     * Taxa conhecida: 1 tentativa a cada 5 ms (12.000 RPM) durante 1 s, lendo o RPM a cada tentativa. O bucket corrente está parcial, então
     * dividir pela janela cheia (200 ms) leria 31 x 300 = 9.300 no início de cada bucket (-22%); dividindo pelo tempo coberto o erro fica em
     * poucos por cento, em qualquer ponto do bucket.
     */
    @Test
    void lidoNumaTaxaConstanteDaOValorCertoEmQualquerPontoDoBucket() {
        RpmCounter c = contador(50, 200);
        for (int t = 0; t < 1000; t += 5) {
            long lido = c.contar(evento);
            if (t >= 150) { // janela cheia
                assertTrue(Math.abs(lido - 12_000) <= 420, "t=" + t + " ms: lido " + lido + " RPM, esperado ~12000 (+-3,5%)");
            }
            relogio.avancar(5);
        }
    }

    @Test
    void janelaQueNaoEMultiploDoBucketEhRecusada() {
        assertThrows(IllegalArgumentException.class, () -> contador(50, 220));
        assertThrows(IllegalArgumentException.class, () -> contador(0, 200));
        assertThrows(IllegalArgumentException.class, () -> contador(50, 0));
        assertFalse(contador(50, 200) == null);
    }

    /** Relógio mutável: o teste decide o "agora" que o contador informa ao script. */
    static final class RelogioManual extends Clock {
        private final AtomicLong agoraMs;

        RelogioManual(long inicioMs) {
            this.agoraMs = new AtomicLong(inicioMs);
        }

        void avancar(long ms) {
            agoraMs.addAndGet(ms);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(agoraMs.get());
        }

        @Override
        public long millis() {
            return agoraMs.get();
        }
    }
}
