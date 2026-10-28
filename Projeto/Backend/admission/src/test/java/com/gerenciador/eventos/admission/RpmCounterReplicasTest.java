package com.gerenciador.eventos.admission;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.gerenciador.eventos.admission.RpmCounter.Decisao;
import com.gerenciador.eventos.admission.RpmCounterTest.RelogioManual;

/**
 * N réplicas do {@code admission} (autoescala, issue #112) contando o MESMO evento: cada réplica é uma instância própria de
 * {@link RpmCounter} com a sua conexão ao Redis REAL, e o RPM e o modo do evento são a SOMA delas, porque o único estado é o Redis (buckets
 * {@code adm:cnt:*}, modo {@code adm:mode:*}). Não há guarda de produção para tirar e ver o teste cair: o que o teste prova é justamente
 * que NÃO existe contador em memória (uma réplica que guardasse a contagem localmente enxergaria só a metade e não ligaria a fila).
 *
 * <p>O relógio é um só e manual ({@link RelogioManual}); em produção as réplicas são containers do mesmo host e dividem o relógio do
 * kernel, então os buckets {@code floor(agora / bucketMs)} de todas coincidem. Mesma convenção do {@link ReplicasAtivasTest}: bucket =
 * janela = 1 s, relógio no ms 999, então RPM = 60 x tentativas; com X = 1000 e 1 réplica ativa, 14 tentativas = 0,84 de X.</p>
 */
class RpmCounterReplicasTest {

    private static final long INICIO = 1_700_000_000_000L - (1_700_000_000_000L % 1000) + 999;

    private RelogioManual relogio;
    private long evento;

    @BeforeEach
    void preparar() {
        relogio = new RelogioManual(INICIO);
        evento = RedisDeTeste.eventoNovo();
        limpar();
    }

    @AfterEach
    void limpar() {
        StringRedisTemplate redis = RedisDeTeste.template();
        var chaves = redis.keys("adm:cnt:" + evento + ":*");
        if (chaves != null && !chaves.isEmpty()) {
            redis.delete(chaves);
        }
        redis.delete(AdmissionStore.chaveModo(evento));
        redis.delete(AdmissionStore.chaveEspera(evento));
        redis.delete(AdmissionStore.CHAVE_REPLICAS);
        redis.delete(AdmissionStore.CHAVE_QUENTE);
    }

    /** Uma "réplica": instância e conexão próprias, como um processo separado. */
    private RpmCounter replica() {
        StringRedisTemplate redis = RedisDeTeste.template();
        return new RpmCounter(redis, relogio, 1000, 1000, 1000, 1, 0.8, 0.7);
    }

    @Test
    void duasReplicasContandoOMesmoEventoDaoORpmSomado() throws Exception {
        RpmCounter a = replica();
        RpmCounter b = replica();
        int threadsPorReplica = 4;
        int tentativas = 30; // por thread
        CyclicBarrier largada = new CyclicBarrier(2 * threadsPorReplica);
        ExecutorService pool = Executors.newFixedThreadPool(2 * threadsPorReplica);
        try {
            List<Future<Long>> fs = new ArrayList<>();
            for (RpmCounter r : List.of(a, b)) {
                for (int t = 0; t < threadsPorReplica; t++) {
                    Callable<Long> tarefa = () -> {
                        largada.await();
                        for (int i = 0; i < tentativas; i++) {
                            r.contar(evento);
                        }
                        return 0L;
                    };
                    fs.add(pool.submit(tarefa));
                }
            }
            for (Future<Long> f : fs) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        long total = 2L * threadsPorReplica * tentativas; // 240 tentativas, nenhuma perdida nem contada em dobro
        assertEquals(60 * total, a.rpm(evento), "a réplica A enxerga a contagem das duas");
        assertEquals(60 * total, b.rpm(evento), "a réplica B enxerga a mesma soma");
    }

    @Test
    void metadeDaCargaEmCadaReplicaJaLigaAFilaPorqueOModoEDoEventoNoRedis() {
        RpmCounter a = replica();
        RpmCounter b = replica();

        Decisao deA = null;
        for (int i = 0; i < 7; i++) { // 7 tentativas = 420 RPM = 0,42 de X: sozinha, A ficaria no síncrono
            deA = a.decidir(evento);
        }
        assertFalse(deA.fila(), "só a metade da carga: ainda síncrono");

        Decisao deB = null;
        for (int i = 0; i < 7; i++) { // com as 7 de B a soma é 14 = 840 RPM = 0,84 de X
            deB = b.decidir(evento);
        }
        assertEquals(840, deB.rpm(), "B soma as tentativas de A com as suas");
        assertTrue(deB.fila(), "a soma passou do gatilho de 0,80: B liga a fila do evento");
        assertTrue(a.decidir(evento).fila(), "e A vê o modo que B ligou (o modo mora no Redis, não na réplica)");
    }
}
