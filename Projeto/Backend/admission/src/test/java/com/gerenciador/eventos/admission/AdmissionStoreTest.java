package com.gerenciador.eventos.admission;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.admission.AdmissionStore.Tipo;

/**
 * O estado da fila de admissão no Redis REAL (issue #42), sem Spring nem RabbitMQ: resultado com {@code attemptId} e TTL em toda escrita,
 * a espera por evento e por usuário (score = deadline), a repetição da chave (422 / estado atual / refila), o limite por usuário e o
 * compare-and-set do estado final (tentativa antiga não mexe na nova).
 */
class AdmissionStoreTest {

    private static final long TTL_RESULTADO = 600;
    private static final long TTL_FILA_MS = 30_000;

    private final StringRedisTemplate redis = RedisDeTeste.template();
    private final RelogioMutavel relogio = new RelogioMutavel(1_800_000_000_000L);
    private final AdmissionStore store = new AdmissionStore(redis, new ObjectMapper(), relogio, TTL_RESULTADO, TTL_FILA_MS, 3);
    private final long evento = RedisDeTeste.eventoNovo();
    private final long usuario = RedisDeTeste.usuarioNovo();

    @BeforeEach
    @AfterEach
    void limpar() {
        var chaves = redis.keys("adm:*:" + usuario + "*");
        if (chaves != null && !chaves.isEmpty()) {
            redis.delete(chaves);
        }
        redis.delete(AdmissionStore.chaveEspera(evento));
    }

    private AdmissionMessage msg(String chave, String tentativa, long deadline) {
        return new AdmissionMessage(usuario, evento, chave, tentativa, deadline);
    }

    private AdmissionMessage msg(String chave, String tentativa) {
        return msg(chave, tentativa, relogio.millis() + TTL_FILA_MS);
    }

    @Test
    void recusaNoBootUmTtlDeResultadoMenorQueOTtlDaFilaMaisACarencia() {
        // 89 s < 30 s de fila + 60 s de carência: o queued expiraria antes do worker e a mensagem seria descartada sem reservar
        assertThrows(IllegalArgumentException.class, () -> new AdmissionStore(redis, new ObjectMapper(), relogio, 89, TTL_FILA_MS, 3));
        new AdmissionStore(redis, new ObjectMapper(), relogio, 90, TTL_FILA_MS, 3); // exatamente o mínimo serve
    }

    @Test
    void entrarGravaQueuedComTtlEAEsperaDoEventoEDoUsuario() {
        AdmissionMessage m = msg("k1", "t1");

        assertEquals(Tipo.ENFILEIRADA, store.entrar(m).tipo());

        ResultadoDaFila r = store.resultado(usuario, "k1");
        assertEquals(new ResultadoDaFila("queued", evento, "t1", m.deadline(), null, null), r);
        Long ttl = redis.getExpire(AdmissionStore.chaveResultado(usuario, "k1"));
        assertTrue(ttl != null && ttl > 0 && ttl <= TTL_RESULTADO, "o resultado tem TTL (nenhuma escrita sem TTL): " + ttl);
        assertEquals((double) m.deadline(), redis.opsForZSet().score(AdmissionStore.chaveEspera(evento), m.membro()));
        assertEquals((double) m.deadline(), redis.opsForZSet().score(AdmissionStore.chaveEsperaUsuario(usuario), m.membro()));
        Long ttlEspera = redis.getExpire(AdmissionStore.chaveEspera(evento));
        assertTrue(ttlEspera != null && ttlEspera > 0 && ttlEspera <= 90, "a espera tem TTL = TTL da fila + 60 s: " + ttlEspera);
        assertTrue(redis.getExpire(AdmissionStore.chaveEsperaUsuario(usuario)) > 0);
    }

    @Test
    void repeticaoDeQueuedOuPendingDevolveOEstadoAtualSemEnfileirarDeNovo() {
        AdmissionMessage m = msg("k1", "t1");
        store.entrar(m);

        AdmissionStore.Entrada repetida = store.entrar(msg("k1", "t2"));
        assertEquals(Tipo.EXISTENTE, repetida.tipo());
        assertEquals("t1", repetida.atual().attemptId(), "a tentativa original continua valendo");
        assertEquals(1, redis.opsForZSet().zCard(AdmissionStore.chaveEsperaUsuario(usuario)), "não entrou na espera de novo");

        assertTrue(store.concluir(m, store.resultado(usuario, "k1").pending("2026-10-28T12:00:00")));
        AdmissionStore.Entrada depoisDePending = store.entrar(msg("k1", "t3"));
        assertEquals(Tipo.EXISTENTE, depoisDePending.tipo());
        assertEquals("pending", depoisDePending.atual().status());
    }

    @Test
    void rejectedETimedOutDoMesmoEventoSaoSobrescritosPorUmaTentativaNova() {
        AdmissionMessage m = msg("k1", "t1");
        store.entrar(m);
        store.concluir(m, store.resultado(usuario, "k1").rejected("Evento lotado"));

        assertEquals(Tipo.ENFILEIRADA, store.entrar(msg("k1", "t2")).tipo(), "uma falha não consome a chave");
        assertEquals("t2", store.resultado(usuario, "k1").attemptId());
        assertEquals("queued", store.resultado(usuario, "k1").status());

        AdmissionMessage m2 = msg("k1", "t2");
        store.concluir(m2, store.resultado(usuario, "k1").timedOut());
        assertEquals(Tipo.ENFILEIRADA, store.entrar(msg("k1", "t3")).tipo());
        assertEquals("t3", store.resultado(usuario, "k1").attemptId());
    }

    @Test
    void queuedVencidoValeComoTimedOutNaRepeticaoENaLeitura() {
        AdmissionMessage m = msg("k1", "t1");
        store.entrar(m);
        ResultadoDaFila bruto = store.resultado(usuario, "k1");

        relogio.avancar(TTL_FILA_MS + ResultadoDaFila.CARENCIA_MS - 1_000);
        assertEquals("queued", bruto.efetivo(relogio.millis()).status(), "dentro da carência ainda é queued");
        assertEquals(Tipo.EXISTENTE, store.entrar(msg("k1", "t2")).tipo());

        relogio.avancar(2_000);
        assertEquals("timed_out", bruto.efetivo(relogio.millis()).status(), "deadline + 60 s passou: a mensagem se perdeu");
        assertEquals(Tipo.ENFILEIRADA, store.entrar(msg("k1", "t3")).tipo(), "o queued vencido é sobrescrito, como o timed_out");
        assertEquals("t3", store.resultado(usuario, "k1").attemptId());
    }

    @Test
    void chaveDeOutroEventoDa422MesmoComResultadoFinal() {
        AdmissionMessage m = msg("k1", "t1");
        store.entrar(m);

        assertEquals(Tipo.OUTRO_EVENTO, store.entrar(new AdmissionMessage(usuario, evento + 1, "k1", "tx", relogio.millis() + 1)).tipo());

        store.concluir(m, store.resultado(usuario, "k1").rejected("Evento lotado"));
        assertEquals(Tipo.OUTRO_EVENTO, store.entrar(new AdmissionMessage(usuario, evento + 1, "k1", "ty", relogio.millis() + 1)).tipo());
        assertEquals("t1", store.resultado(usuario, "k1").attemptId(), "o 422 não toca o resultado");
    }

    @Test
    void limitePorUsuarioBarraAAlemDoMaximoEARepeticaoVemAntesDoLimite() {
        for (int i = 1; i <= 3; i++) {
            assertEquals(Tipo.ENFILEIRADA, store.entrar(msg("k" + i, "t" + i)).tipo());
        }

        assertEquals(Tipo.LIMITE_USUARIO, store.entrar(msg("k4", "t4")).tipo());
        assertNull(store.resultado(usuario, "k4"), "o 429 não grava resultado nem espera");
        assertEquals(3, redis.opsForZSet().zCard(AdmissionStore.chaveEsperaUsuario(usuario)));
        assertEquals(Tipo.EXISTENTE, store.entrar(msg("k1", "t9")).tipo(), "a repetição é avaliada ANTES do limite");

        store.sairDaEspera(msg("k1", "t1"));
        assertEquals(Tipo.ENFILEIRADA, store.entrar(msg("k4", "t4")).tipo(), "uma vaga na espera libera o usuário");
    }

    @Test
    void esperaVencidaPeloDeadlineSaiSozinhaEDeixaOUsuarioEnfileirar() {
        for (int i = 1; i <= 3; i++) {
            store.entrar(msg("k" + i, "t" + i, relogio.millis() + 1_000));
        }
        assertEquals(Tipo.LIMITE_USUARIO, store.entrar(msg("k4", "t4")).tipo());

        relogio.avancar(2_000); // as três mensagens se perderam (deadline passou): saem da espera
        assertEquals(Tipo.ENFILEIRADA, store.entrar(msg("k4", "t4")).tipo());
        assertEquals(1, redis.opsForZSet().zCard(AdmissionStore.chaveEsperaUsuario(usuario)));
        assertEquals(1, redis.opsForZSet().zCard(AdmissionStore.chaveEspera(evento)));
    }

    @Test
    void estadoFinalSoSobrescreveOQueuedDaMesmaTentativa() {
        AdmissionMessage antiga = msg("k1", "t-antiga");
        store.entrar(antiga);
        ResultadoDaFila baseAntiga = store.resultado(usuario, "k1");
        store.concluir(antiga, baseAntiga.rejected("Evento lotado"));
        AdmissionMessage nova = msg("k1", "t-nova");
        store.entrar(nova); // refila: queued da tentativa nova

        assertFalse(store.concluir(antiga, baseAntiga.pending("2026-10-28T12:00:00")), "a tentativa antiga não mexe na nova");
        assertEquals("queued", store.resultado(usuario, "k1").status());
        assertEquals("t-nova", store.resultado(usuario, "k1").attemptId());

        ResultadoDaFila baseNova = store.resultado(usuario, "k1");
        assertTrue(store.concluir(nova, baseNova.pending("2026-10-28T12:00:00")));
        assertFalse(store.concluir(nova, baseNova.timedOut()), "estado final não é sobrescrito");
        assertEquals("pending", store.resultado(usuario, "k1").status());
        Long ttl = redis.getExpire(AdmissionStore.chaveResultado(usuario, "k1"));
        assertTrue(ttl != null && ttl > 0 && ttl <= TTL_RESULTADO, "o estado final também tem TTL: " + ttl);
        assertFalse(store.concluir(msg("sem-resultado", "t"), baseNova.timedOut()), "sem resultado não grava nada");
        assertNull(store.resultado(usuario, "sem-resultado"));
    }

    @Test
    void desfazerApagaOQueuedDestaTentativaENaoODeUmaNova() {
        AdmissionMessage m = msg("k1", "t1");
        store.entrar(m);

        store.desfazer(m);
        assertNull(store.resultado(usuario, "k1"));
        assertEquals(0, redis.opsForZSet().zCard(AdmissionStore.chaveEspera(evento)));
        assertEquals(0, redis.opsForZSet().zCard(AdmissionStore.chaveEsperaUsuario(usuario)));
        store.desfazer(m); // idempotente

        AdmissionMessage nova = msg("k1", "t2");
        store.entrar(nova);
        store.desfazer(m); // desfazer a tentativa antiga não apaga a nova
        assertNotNull(store.resultado(usuario, "k1"));
        assertEquals("t2", store.resultado(usuario, "k1").attemptId());
        assertEquals(1, redis.opsForZSet().zCard(AdmissionStore.chaveEsperaUsuario(usuario)));
    }

    @Test
    void sairEVoltarParaEsperaSaoIdempotentes() {
        AdmissionMessage m = msg("k1", "t1");
        store.entrar(m);

        store.sairDaEspera(m);
        store.sairDaEspera(m); // reentrega não decrementa duas vezes
        assertEquals(0, redis.opsForZSet().zCard(AdmissionStore.chaveEspera(evento)));

        store.voltarParaEspera(m);
        store.voltarParaEspera(m);
        assertEquals(1, redis.opsForZSet().zCard(AdmissionStore.chaveEspera(evento)));
        assertEquals((double) m.deadline(), redis.opsForZSet().score(AdmissionStore.chaveEspera(evento), m.membro()));
        assertTrue(redis.getExpire(AdmissionStore.chaveEspera(evento)) > 0, "a espera que voltou tem TTL");
        assertTrue(redis.getExpire(AdmissionStore.chaveEsperaUsuario(usuario)) > 0);
    }

    @Test
    void naoHaDadoPessoalNoResultadoNemNaMensagem() {
        AdmissionMessage m = msg("k1", "t1");
        store.entrar(m);

        String bruto = redis.opsForValue().get(AdmissionStore.chaveResultado(usuario, "k1"));
        assertFalse(bruto.contains("@"), bruto);
        assertEquals(java.util.Set.of("status", "eventId", "attemptId", "deadline"), campos(bruto));
        assertNotEquals("", m.membro());
    }

    private static java.util.Set<String> campos(String json) {
        try {
            var out = new java.util.HashSet<String>();
            new ObjectMapper().readTree(json).fieldNames().forEachRemaining(out::add);
            return out;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Relógio que os testes avançam na mão. */
    static final class RelogioMutavel extends Clock {
        private volatile long ms;

        RelogioMutavel(long ms) {
            this.ms = ms;
        }

        void avancar(long dMs) {
            ms += dMs;
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
            return Instant.ofEpochMilli(ms);
        }

        @Override
        public long millis() {
            return ms;
        }
    }
}
