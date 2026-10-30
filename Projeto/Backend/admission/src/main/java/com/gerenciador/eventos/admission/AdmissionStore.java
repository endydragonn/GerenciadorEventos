package com.gerenciador.eventos.admission;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Estado da fila de admissão no Redis (issue #42): o resultado de cada mensagem ({@code adm:res:<userId>:<chave>}, JSON, TODA escrita com
 * TTL) e a espera, o que foi publicado e ainda não foi pego por um worker (ZSET {@code adm:wait:<eventId>} e {@code adm:uwait:<userId>},
 * score = deadline). As operações que mudam o resultado são scripts Lua (atômicas; o estado final é compare-and-set pela tentativa).
 * Falha do Redis PROPAGA ({@code DataAccessException}): quem chama decide (503 na API, nack no worker).
 */
@Component
public class AdmissionStore {

    /** Resultado de {@link #entrar}. */
    public enum Tipo { ENFILEIRADA, EXISTENTE, OUTRO_EVENTO, LIMITE_USUARIO }

    /** {@code atual} só vem preenchido em {@link Tipo#EXISTENTE}. */
    public record Entrada(Tipo tipo, ResultadoDaFila atual) { }

    private static final DefaultRedisScript<List> ENFILEIRAR = script("enfileirar.lua", List.class);
    private static final DefaultRedisScript<Long> CONCLUIR = script("concluir.lua", Long.class);
    private static final DefaultRedisScript<Long> DESFAZER = script("desfazer.lua", Long.class);
    private static final DefaultRedisScript<Long> VOLTAR = script("voltar.lua", Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock relogio;
    private final long ttlResultadoSegundos;
    private final Duration ttlEspera;
    private final int maxEmEsperaPorUsuario;

    public AdmissionStore(StringRedisTemplate redis, ObjectMapper json, Clock relogio,
            @Value("${admission.result-ttl-seconds:600}") long ttlResultadoSegundos,
            @Value("${admission.queue.ttl-ms:30000}") long ttlFilaMs,
            @Value("${admission.max-waiting-per-user:5}") int maxEmEsperaPorUsuario) {
        if (ttlResultadoSegundos * 1000 < ttlFilaMs + ResultadoDaFila.CARENCIA_MS) {
            // o queued expiraria antes do worker pegar a mensagem: ela seria descartada sem reservar e o cliente veria 404
            throw new IllegalArgumentException("admission.result-ttl-seconds (" + ttlResultadoSegundos + ") deve cobrir admission.queue.ttl-ms ("
                    + ttlFilaMs + ") mais a carência de " + ResultadoDaFila.CARENCIA_MS + " ms");
        }
        this.redis = redis;
        this.json = json;
        this.relogio = relogio;
        this.ttlResultadoSegundos = ttlResultadoSegundos;
        // TTL das chaves de espera = TTL da fila + 60 s (o que sobra sai pelo score, o deadline)
        this.ttlEspera = Duration.ofSeconds((ttlFilaMs + ResultadoDaFila.CARENCIA_MS + 999) / 1000);
        this.maxEmEsperaPorUsuario = maxEmEsperaPorUsuario;
    }

    /**
     * Registra a tentativa (resultado {@code queued} + espera do evento e do usuário), depois de tratar a repetição da chave e o limite
     * por usuário; tudo num script. {@code ENFILEIRADA} = o chamador deve publicar a mensagem.
     */
    public Entrada entrar(AdmissionMessage m) {
        List<?> r = redis.execute(ENFILEIRAR, List.of(chaveResultado(m.userId(), m.idempotencyKey()), chaveEspera(m.eventId()),
                chaveEsperaUsuario(m.userId()), chaveModo(m.eventId())), String.valueOf(relogio.millis()), String.valueOf(m.eventId()),
                escrever(ResultadoDaFila.queued(m)), String.valueOf(ttlResultadoSegundos), String.valueOf(maxEmEsperaPorUsuario),
                m.membro(), String.valueOf(m.deadline()), String.valueOf(ttlEspera.toSeconds()), String.valueOf(ResultadoDaFila.CARENCIA_MS),
                String.valueOf(RpmCounter.TTL_MODO_SEGUNDOS));
        return switch (((Number) r.get(0)).intValue()) {
            case 0 -> new Entrada(Tipo.ENFILEIRADA, null);
            case 1 -> new Entrada(Tipo.EXISTENTE, ler((String) r.get(1)));
            case 2 -> new Entrada(Tipo.OUTRO_EVENTO, null);
            default -> new Entrada(Tipo.LIMITE_USUARIO, null);
        };
    }

    /** O resultado como está no Redis (sem a regra do {@code queued} vencido), ou {@code null}. */
    public ResultadoDaFila resultado(long userId, String chave) {
        String bruto = redis.opsForValue().get(chaveResultado(userId, chave));
        return bruto == null ? null : ler(bruto);
    }

    /** Estado final da tentativa ({@code pending}, {@code rejected} ou {@code timed_out}); só grava sobre o {@code queued} dela. */
    public boolean concluir(AdmissionMessage m, ResultadoDaFila finalizado) {
        Long gravou = redis.execute(CONCLUIR, List.of(chaveResultado(m.userId(), m.idempotencyKey())), m.attemptId(),
                escrever(finalizado), String.valueOf(ttlResultadoSegundos));
        return Long.valueOf(1).equals(gravou);
    }

    /** Desfaz a entrada de uma mensagem que não foi publicada. */
    public void desfazer(AdmissionMessage m) {
        redis.execute(DESFAZER, List.of(chaveResultado(m.userId(), m.idempotencyKey()), chaveEspera(m.eventId()),
                chaveEsperaUsuario(m.userId())), m.attemptId(), m.membro());
    }

    /** O worker pegou a mensagem (ou ela foi para a dead-letter): sai das duas esperas. {@code ZREM} é idempotente (reentrega não decrementa 2x). */
    public void sairDaEspera(AdmissionMessage m) {
        redis.opsForZSet().remove(chaveEspera(m.eventId()), m.membro());
        redis.opsForZSet().remove(chaveEsperaUsuario(m.userId()), m.membro());
    }

    /** O worker devolveu a mensagem à fila ({@code nack} com requeue): volta às esperas, com TTL, num script (o backlog segue contando). */
    public void voltarParaEspera(AdmissionMessage m) {
        redis.execute(VOLTAR, List.of(chaveEspera(m.eventId()), chaveEsperaUsuario(m.userId()), chaveModo(m.eventId())), m.membro(),
                String.valueOf(m.deadline()), String.valueOf(ttlEspera.toSeconds()), String.valueOf(RpmCounter.TTL_MODO_SEGUNDOS));
    }

    /** Réplicas ATIVAS do reservation, escritas pelo scaler da autoescala (#111) com TTL; ausente ou inválida => vale o env (ver rpm.lua). */
    static final String CHAVE_REPLICAS = "adm:replicas";

    /** RPM do evento mais quente da janela atual (maior valor visto, TTL de uma janela), escrito pelo rpm.lua e lido pelo scaler (#111). */
    static final String CHAVE_QUENTE = "adm:hot";

    static String chaveResultado(long userId, String chave) {
        return "adm:res:" + userId + ":" + chave;
    }

    /** Presente = o evento está em modo fila (issue #43); escrita só pelo script do contador. */
    static String chaveModo(long eventId) {
        return "adm:mode:" + eventId;
    }

    static String chaveEspera(long eventId) {
        return "adm:wait:" + eventId;
    }

    static String chaveEsperaUsuario(long userId) {
        return "adm:uwait:" + userId;
    }

    private String escrever(ResultadoDaFila r) {
        try {
            return json.writeValueAsString(r);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ResultadoDaFila ler(String bruto) {
        try {
            return json.readValue(bruto, ResultadoDaFila.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("resultado ilegível no Redis", ex);
        }
    }

    private static <T> DefaultRedisScript<T> script(String nome, Class<T> tipo) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/" + nome));
        script.setResultType(tipo);
        return script;
    }
}
