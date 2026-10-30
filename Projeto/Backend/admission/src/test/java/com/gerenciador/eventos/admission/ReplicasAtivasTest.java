package com.gerenciador.eventos.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.gerenciador.eventos.admission.RpmCounter.Decisao;
import com.gerenciador.eventos.admission.RpmCounterTest.RelogioManual;

/**
 * As réplicas ativas do {@code reservation} lidas do Redis (issue #111, autoescala) contra o Redis REAL: o scaler grava {@code adm:replicas}
 * (com TTL) e o script do contador usa {@code X x esse valor} no gatilho; sem a chave (scaler parado) ou com valor inválido vale
 * {@code admission.reservation-replicas} (o lado seguro: entra em fila mais cedo). E o RPM do evento mais quente da janela
 * ({@code adm:hot}), que o scaler lê para decidir. Mesma convenção do {@link ModoDoEventoTest}: bucket = janela = 1 s, relógio no ms 999, então
 * RPM = 60 x tentativas (X = 1000: 13 tentativas = 0,78 e 14 = 0,84 de X; com 2 réplicas, 26 = 0,78 e 27 = 0,81).
 */
class ReplicasAtivasTest {

    private static final long INICIO = 1_700_000_000_000L - (1_700_000_000_000L % 1000) + 999;

    private StringRedisTemplate redis;
    private RelogioManual relogio;
    private long evento;

    @BeforeEach
    void preparar() {
        redis = RedisDeTeste.template();
        relogio = new RelogioManual(INICIO);
        evento = RedisDeTeste.eventoNovo();
        limparChaves();
    }

    @AfterEach
    void limpar() {
        limparChaves();
        for (long e : new long[] {evento, evento + 1}) {
            var chaves = redis.keys("adm:cnt:" + e + ":*");
            if (chaves != null && !chaves.isEmpty()) {
                redis.delete(chaves);
            }
            redis.delete(AdmissionStore.chaveModo(e));
            redis.delete(AdmissionStore.chaveEspera(e));
        }
    }

    private void limparChaves() {
        redis.delete(AdmissionStore.CHAVE_REPLICAS);
        redis.delete(AdmissionStore.CHAVE_QUENTE);
    }

    private RpmCounter contador(int replicasDoEnv) {
        return new RpmCounter(redis, relogio, 1000, 1000, 1000, replicasDoEnv, 0.8, 0.7);
    }

    private Decisao decidir(RpmCounter c, long ev, int n) {
        Decisao d = null;
        for (int i = 0; i < n; i++) {
            d = c.decidir(ev);
        }
        return d;
    }

    @Test
    void comAChaveEmDoisOGatilhoUsaXVezesDois() {
        redis.opsForValue().set(AdmissionStore.CHAVE_REPLICAS, "2");
        RpmCounter c = contador(1);

        assertFalse(decidir(c, evento, 26).fila(), "26 tentativas = 1560 RPM = 0,78 de 2000: abaixo do gatilho");
        Decisao d = c.decidir(evento);
        assertEquals(1620, d.rpm());
        assertTrue(d.fila(), "27 tentativas = 0,81 de 2000: entra em fila");
    }

    @Test
    void aChaveMudaACapacidadeSemRecriarOContador() {
        RpmCounter c = contador(1);
        assertTrue(decidir(c, evento, 14).fila(), "sem a chave, 840 RPM = 0,84 de 1000: fila");
        redis.delete(AdmissionStore.chaveModo(evento));

        redis.opsForValue().set(AdmissionStore.CHAVE_REPLICAS, "2");
        relogio.avancar(1000);
        assertFalse(decidir(c, evento, 14).fila(), "o scaler promoveu a 2 ativas: 840 RPM = 0,42 de 2000");
    }

    @Test
    void semAChaveVaiAoEnvDasReplicas() {
        assertTrue(decidir(contador(1), evento, 14).fila(), "env = 1: 840 RPM = 0,84 de 1000");
        long outro = evento + 1;
        assertFalse(decidir(contador(2), outro, 14).fila(), "env = 2: 840 RPM = 0,42 de 2000");
    }

    @Test
    void chaveInvalidaVaiAoEnvEnuncaDividePorZero() {
        RpmCounter c = contador(1);
        for (String lixo : new String[] {"abc", "0", "-3", "1.5", "nan", "inf", "", " ", "1e999", "5000"}) {
            redis.opsForValue().set(AdmissionStore.CHAVE_REPLICAS, lixo);
            redis.delete(AdmissionStore.chaveModo(evento));
            relogio.avancar(1000);
            Decisao d = decidir(c, evento, 14);
            assertEquals(840, d.rpm(), "chave '" + lixo + "'");
            assertTrue(d.fila(), "chave '" + lixo + "' usa o env (1): 840 RPM = 0,84 de 1000");
        }
    }

    @Test
    void oRpmDoEventoMaisQuenteFicaEmUmaChaveComTtlDeUmaJanela() {
        RpmCounter c = contador(1);
        assertNull(redis.opsForValue().get(AdmissionStore.CHAVE_QUENTE), "ninguém contou ainda");

        decidir(c, evento, 5); // 300 RPM
        decidir(c, evento + 1, 2); // 120 RPM: não rebaixa
        assertEquals("300", redis.opsForValue().get(AdmissionStore.CHAVE_QUENTE));
        decidir(c, evento + 1, 4); // 360 RPM no outro evento: passa a ser o mais quente
        assertEquals("360", redis.opsForValue().get(AdmissionStore.CHAVE_QUENTE));

        long ttl = redis.getExpire(AdmissionStore.CHAVE_QUENTE, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(ttl > 0 && ttl <= 1000, "a chave vale no máximo uma janela (1 s aqui): " + ttl + " ms");
    }

    @Test
    void oBracoNaiveTambemPublicaELeituraNaoPublica() {
        RpmCounter c = contador(1);
        c.rpm(evento);
        assertNull(redis.opsForValue().get(AdmissionStore.CHAVE_QUENTE), "só leitura não publica");
        c.contar(evento);
        assertEquals("60", redis.opsForValue().get(AdmissionStore.CHAVE_QUENTE), "o braço naive também é carga");
    }

    @Test
    void oValorVelhoSaiDepoisDeUmaJanela() throws Exception {
        RpmCounter c = contador(1);
        decidir(c, evento, 5);
        assertEquals("300", redis.opsForValue().get(AdmissionStore.CHAVE_QUENTE));

        Thread.sleep(1100); // a chave expira (TTL real do Redis); o relógio do contador é o manual
        assertNull(redis.opsForValue().get(AdmissionStore.CHAVE_QUENTE));
        relogio.avancar(1000);
        decidir(c, evento + 1, 1);
        assertEquals("60", redis.opsForValue().get(AdmissionStore.CHAVE_QUENTE), "volta a refletir só a carga atual");
    }
}
