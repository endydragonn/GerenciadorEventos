package com.gerenciador.eventos.admission;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Contador de tentativas de reserva por evento (issue #41), em janela deslizante de buckets no Redis, e decisão do modo de alta carga do
 * evento (issue #43). Cada tentativa vira um {@code INCR} no bucket {@code floor(agora / bucketMs)} do evento (chave
 * {@code adm:cnt:<eventId>:<bucket>}, com {@code PEXPIRE} de janela + 1 bucket); o <b>RPM</b> é a soma dos buckets da janela x 60000 / o
 * tempo coberto. Tudo num script Lua ({@code scripts/rpm.lua}): UMA ida ao Redis, atômica (sem contagem perdida nem duplicada sob
 * concorrência).
 *
 * <p><b>Gatilho e histerese (#43).</b> {@code uso = RPM / (X x réplicas)}: em {@code sync}, uso >= {@code queueOn} (0,80) liga o modo fila do
 * evento ({@code adm:mode:<eventId>}, TTL 1 h renovado a cada decisão em fila); em fila, só volta ao síncrono com uso < {@code syncBelow}
 * (0,70) E a espera do evento vazia (ZSET {@code adm:wait:<eventId>}, lido ANTES de contar a tentativa atual). A decisão é do braço
 * {@code cas} ({@link #decidir}); {@link #contar} (braço {@code naive}) só conta e nunca mexe no modo.</p>
 *
 * <p><b>Réplicas ativas dinâmicas (#111).</b> O {@code réplicas} da conta é lido NO MESMO script de {@code adm:replicas}, chave que o scaler da
 * autoescala escreve (com TTL) a cada laço; ausente (scaler parado) ou inválida, vale {@code admission.reservation-replicas}. O mesmo script
 * publica em {@code adm:hot} o maior RPM visto na janela (TTL de uma janela), que o scaler lê para decidir.</p>
 *
 * <p>O RPM é sinal de carga. A janela efetiva é de (n-1) a n buckets, porque o bucket corrente ainda está parcialmente cheio; com os
 * defaults (50 ms / 200 ms) a resolução é de 300 RPM por tentativa. O "agora" é o do relógio do admission (um só processo): informado ao
 * script, que não chama {@code TIME}.</p>
 */
@Component
public class RpmCounter {

    /** Resultado de {@link #decidir}: o RPM do evento (já com a tentativa) e se ele está em modo fila. */
    public record Decisao(long rpm, boolean fila) { }

    static final long TTL_MODO_SEGUNDOS = 3600;
    private static final DefaultRedisScript<List> SCRIPT = novoScript();

    private final StringRedisTemplate redis;
    private final Clock relogio;
    private final long bucketMs;
    private final long janelaMs;
    private final String xRpm;
    private final String replicas;
    private final String queueOn;
    private final String syncBelow;

    public RpmCounter(StringRedisTemplate redis, Clock relogio, @Value("${admission.bucket-ms:50}") long bucketMs,
            @Value("${admission.window-ms:200}") long janelaMs, @Value("${admission.x-rpm:56000}") double xRpm,
            @Value("${admission.reservation-replicas:1}") int replicas, @Value("${admission.queue-on:0.8}") double queueOn,
            @Value("${admission.sync-below:0.7}") double syncBelow) {
        if (bucketMs <= 0 || janelaMs <= 0 || janelaMs % bucketMs != 0) {
            throw new IllegalArgumentException("admission.window-ms (" + janelaMs + ") deve ser positivo e múltiplo de "
                    + "admission.bucket-ms (" + bucketMs + "), que também deve ser positivo");
        }
        if (xRpm <= 0 || replicas < 1) {
            throw new IllegalArgumentException("admission.x-rpm (" + xRpm + ") e admission.reservation-replicas (" + replicas
                    + ") devem ser positivos");
        }
        if (syncBelow <= 0 || queueOn <= syncBelow) {
            // sem a diferença entre os dois o modo oscilaria a cada tentativa
            throw new IllegalArgumentException("admission.queue-on (" + queueOn + ") deve ser maior que admission.sync-below ("
                    + syncBelow + "), que deve ser positivo (histerese)");
        }
        this.redis = redis;
        this.relogio = relogio;
        this.bucketMs = bucketMs;
        this.janelaMs = janelaMs;
        // enviados como texto: o Lua faz rpm / (X x réplicas) e compara com os mesmos doubles que o Java. As réplicas do env só valem
        // sem a chave adm:replicas do scaler (issue #111)
        this.xRpm = String.valueOf(xRpm);
        this.replicas = String.valueOf(replicas);
        this.queueOn = String.valueOf(queueOn);
        this.syncBelow = String.valueOf(syncBelow);
    }

    /** Conta uma tentativa no evento e devolve o RPM atual (já com ela), sem mexer no modo (braço naive). Falha do Redis propaga. */
    public long contar(long eventId) {
        return executar(eventId, true, false).rpm();
    }

    /** Conta uma tentativa (braço cas) E decide o modo do evento (gatilho e histerese). Falha do Redis propaga. */
    public Decisao decidir(long eventId) {
        return executar(eventId, true, true);
    }

    /** RPM atual do evento, sem contar nada nem mexer no modo. */
    public long rpm(long eventId) {
        return executar(eventId, false, false).rpm();
    }

    private Decisao executar(long eventId, boolean contar, boolean decidir) {
        List<?> r = redis.execute(SCRIPT,
                List.of("adm:cnt:" + eventId + ":", AdmissionStore.chaveModo(eventId), AdmissionStore.chaveEspera(eventId),
                        AdmissionStore.CHAVE_REPLICAS, AdmissionStore.CHAVE_QUENTE),
                String.valueOf(relogio.millis()), String.valueOf(bucketMs), String.valueOf(janelaMs), contar ? "1" : "0",
                decidir ? "1" : "0", xRpm, queueOn, syncBelow, String.valueOf(TTL_MODO_SEGUNDOS), replicas);
        return r == null ? new Decisao(0, false) : new Decisao(((Number) r.get(0)).longValue(), ((Number) r.get(1)).longValue() == 1);
    }

    private static DefaultRedisScript<List> novoScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/rpm.lua"));
        script.setResultType(List.class);
        return script;
    }
}
