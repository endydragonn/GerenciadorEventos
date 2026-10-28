package com.gerenciador.eventos.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.gerenciador.eventos.admission.RpmCounter.Decisao;
import com.gerenciador.eventos.admission.RpmCounterTest.RelogioManual;

/**
 * O gatilho e a histerese do modo de alta carga (issue #43) contra o Redis REAL, no script Lua do contador: entra em fila com uso
 * (RPM / (X x réplicas)) >= 0,80; com a fila já ligada, só volta ao síncrono com uso < 0,70 E a espera do evento vazia; por evento. Bucket =
 * janela = 1 s e o relógio parado no ms 999 do bucket: o tempo coberto é exatamente 1 s, então RPM = 60 x tentativas e as contas são
 * exatas (X = 1000: 13 tentativas = 780 RPM, 14 = 840; X = 1200: 15 = 900 = 0,75, 16 = 960 = 0,80 exato). Avançar 1 s esvazia a janela.
 */
class ModoDoEventoTest {

    private static final long INICIO = 1_700_000_000_000L - (1_700_000_000_000L % 1000) + 999;

    private StringRedisTemplate redis;
    private RelogioManual relogio;
    private long evento;

    @BeforeEach
    void preparar() {
        redis = RedisDeTeste.template();
        relogio = new RelogioManual(INICIO);
        evento = RedisDeTeste.eventoNovo();
    }

    @AfterEach
    void limpar() {
        for (long e : new long[] {evento, evento + 1}) {
            var chaves = redis.keys("adm:cnt:" + e + ":*");
            if (chaves != null && !chaves.isEmpty()) {
                redis.delete(chaves);
            }
            redis.delete(AdmissionStore.chaveModo(e));
            redis.delete(AdmissionStore.chaveEspera(e));
        }
    }

    private RpmCounter contador(double x, int replicas) {
        return new RpmCounter(redis, relogio, 1000, 1000, x, replicas, 0.8, 0.7);
    }

    /** {@code n} decisões no mesmo instante; devolve a última. */
    private Decisao decidir(RpmCounter c, long ev, int n) {
        Decisao d = null;
        for (int i = 0; i < n; i++) {
            d = c.decidir(ev);
        }
        return d;
    }

    private void esperaUmaMensagem(long ev) {
        redis.opsForZSet().add(AdmissionStore.chaveEspera(ev), "1:k:t", relogio.millis() + 30_000);
    }

    @Test
    void abaixoDeOitentaPorCentoSegueSincronoEEntraEmFilaNoLimiar() {
        RpmCounter c = contador(1000, 1);

        for (int i = 1; i <= 13; i++) {
            Decisao d = c.decidir(evento);
            assertEquals(60L * i, d.rpm());
            assertFalse(d.fila(), i + " tentativas = " + d.rpm() + " RPM = " + (d.rpm() / 1000.0) + " de X: abaixo de 0,80");
        }
        assertFalse(redis.hasKey(AdmissionStore.chaveModo(evento)), "sem fila ligada, nenhuma chave de modo");

        Decisao entrou = c.decidir(evento);
        assertEquals(840, entrou.rpm());
        assertTrue(entrou.fila(), "840 RPM = 0,84 de X: entra em fila");
        assertTrue(redis.hasKey(AdmissionStore.chaveModo(evento)));
        long ttl = redis.getExpire(AdmissionStore.chaveModo(evento));
        assertTrue(ttl > 3500 && ttl <= 3600, "o modo expira em 1 h: " + ttl);
    }

    @Test
    void ousoExatoDeOitentaPorCentoJaEhFila() {
        RpmCounter c = contador(1200, 1);

        assertFalse(decidir(c, evento, 15).fila(), "900 RPM = 0,75 de 1200");
        Decisao d = c.decidir(evento);
        assertEquals(960, d.rpm());
        assertTrue(d.fila(), "960 / 1200 = 0,80 exato: uso >= 0,80 entra em fila");
    }

    @Test
    void asReplicasAumentamACapacidadeDoGatilho() {
        RpmCounter c = contador(600, 2); // X x réplicas = 1200: mesma conta do teste anterior

        assertFalse(decidir(c, evento, 15).fila());
        assertTrue(c.decidir(evento).fila());
    }

    @Test
    void comAFilaLigadaSoVoltaAoSincronoComUsoAbaixoDeSetentaPorCentoEEsperaVazia() {
        RpmCounter c = contador(1000, 1);
        assertTrue(decidir(c, evento, 14).fila());

        relogio.avancar(1000); // a janela esvazia: o uso cai a 0,06
        esperaUmaMensagem(evento);
        Decisao comEspera = c.decidir(evento);
        assertEquals(60, comEspera.rpm());
        assertTrue(comEspera.fila(), "uso baixo, mas a fila do evento ainda tem mensagem esperando: segue em fila");

        // sobe a 11 tentativas (a espera ainda segura a fila); tira a espera e a 12ª dá 0,72, entre 0,70 e 0,80, com a espera vazia
        assertTrue(decidir(c, evento, 10).fila(), "11 tentativas = 0,66 de X, mas a espera ainda tem mensagem: segue em fila");
        redis.delete(AdmissionStore.chaveEspera(evento));
        Decisao entre = c.decidir(evento);
        assertEquals(720, entre.rpm());
        assertTrue(entre.fila(), "0,72 de X, entre 0,70 e 0,80, espera vazia: não volta ao síncrono");

        relogio.avancar(1000);
        Decisao voltou = c.decidir(evento);
        assertEquals(60, voltou.rpm());
        assertFalse(voltou.fila(), "uso < 0,70 e espera vazia: volta ao síncrono");
        assertFalse(redis.hasKey(AdmissionStore.chaveModo(evento)), "e a chave do modo some");
        assertFalse(c.decidir(evento).fila(), "segue síncrono");
    }

    @Test
    void esperaVencidaPeloDeadlineNaoSegura() {
        RpmCounter c = contador(1000, 1);
        assertTrue(decidir(c, evento, 14).fila());
        relogio.avancar(1000);
        redis.opsForZSet().add(AdmissionStore.chaveEspera(evento), "1:k:t", relogio.millis() - 1); // mensagem perdida: deadline passou

        assertFalse(c.decidir(evento).fila(), "o que passou do deadline sai da espera e não prende o evento em fila");
    }

    @Test
    void modoEPorEvento() {
        RpmCounter c = contador(1000, 1);

        assertTrue(decidir(c, evento, 14).fila());

        assertFalse(c.decidir(evento + 1).fila(), "o outro evento não está em fila");
        assertFalse(redis.hasKey(AdmissionStore.chaveModo(evento + 1)));
        assertTrue(c.decidir(evento).fila());
    }

    @Test
    void contarSemDecidirNaoMudaOModoNemLeAEspera() {
        RpmCounter c = contador(1000, 1);

        for (int i = 0; i < 20; i++) {
            c.contar(evento); // braço naive e leituras: só contam
        }
        assertFalse(redis.hasKey(AdmissionStore.chaveModo(evento)), "contar não liga a fila");
        assertEquals(1200, c.rpm(evento));

        assertTrue(c.decidir(evento).fila(), "a decisão enxerga a carga contada por todos os braços (21 = 1260 RPM)");
    }

    @Test
    void configuracaoInvalidaERecusada() {
        assertThrows(IllegalArgumentException.class, () -> new RpmCounter(redis, relogio, 1000, 1000, 0, 1, 0.8, 0.7), "X zero");
        assertThrows(IllegalArgumentException.class, () -> new RpmCounter(redis, relogio, 1000, 1000, 1000, 0, 0.8, 0.7), "sem réplica");
        assertThrows(IllegalArgumentException.class, () -> new RpmCounter(redis, relogio, 1000, 1000, 1000, 1, 0.7, 0.8),
                "entrar abaixo de sair: sem histerese");
        assertThrows(IllegalArgumentException.class, () -> new RpmCounter(redis, relogio, 1000, 1000, 1000, 1, 0.8, 0), "sair zero");
    }
}
