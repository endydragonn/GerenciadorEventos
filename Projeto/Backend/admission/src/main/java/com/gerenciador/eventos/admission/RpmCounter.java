package com.gerenciador.eventos.admission;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Contador de tentativas de reserva por evento (issue #41), em janela deslizante de buckets no Redis. Cada tentativa vira um
 * {@code INCR} no bucket {@code floor(agora / bucketMs)} do evento (chave {@code adm:cnt:<eventId>:<bucket>}, com {@code PEXPIRE}
 * de janela + 1 bucket); o <b>RPM</b> é a soma dos buckets da janela x 60000 / janela. Tudo num script Lua
 * ({@code scripts/rpm.lua}): UMA ida ao Redis, atômica (sem contagem perdida nem duplicada sob concorrência).
 *
 * <p>O RPM é só <b>sinal de carga</b>: nada de capacidade é decidido por ele. A janela efetiva é de (n-1) a n buckets, porque o
 * bucket corrente ainda está parcialmente cheio; com os defaults (50 ms / 200 ms) a resolução é de 300 RPM por tentativa. O "agora"
 * é o do relógio do admission (um só processo): informado ao script, que não chama {@code TIME}.</p>
 */
@Component
public class RpmCounter {

    private static final DefaultRedisScript<Long> SCRIPT = novoScript();

    private final StringRedisTemplate redis;
    private final Clock relogio;
    private final long bucketMs;
    private final long janelaMs;

    public RpmCounter(StringRedisTemplate redis, Clock relogio, @Value("${admission.bucket-ms:50}") long bucketMs,
            @Value("${admission.window-ms:200}") long janelaMs) {
        if (bucketMs <= 0 || janelaMs <= 0 || janelaMs % bucketMs != 0) {
            throw new IllegalArgumentException("admission.window-ms (" + janelaMs + ") deve ser positivo e múltiplo de "
                    + "admission.bucket-ms (" + bucketMs + "), que também deve ser positivo");
        }
        this.redis = redis;
        this.relogio = relogio;
        this.bucketMs = bucketMs;
        this.janelaMs = janelaMs;
    }

    /** Conta uma tentativa no evento e devolve o RPM atual (já com ela). Falha do Redis propaga (o chamador decide o fail-open). */
    public long contar(long eventId) {
        return executar(eventId, true);
    }

    /** RPM atual do evento, sem contar nada. */
    public long rpm(long eventId) {
        return executar(eventId, false);
    }

    private long executar(long eventId, boolean contar) {
        Long rpm = redis.execute(SCRIPT, List.of("adm:cnt:" + eventId + ":"), String.valueOf(relogio.millis()),
                String.valueOf(bucketMs), String.valueOf(janelaMs), contar ? "1" : "0");
        return rpm == null ? 0 : rpm;
    }

    private static DefaultRedisScript<Long> novoScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/rpm.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
