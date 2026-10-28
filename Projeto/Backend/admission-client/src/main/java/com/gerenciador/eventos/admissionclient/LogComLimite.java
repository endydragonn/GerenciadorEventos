package com.gerenciador.eventos.admissionclient;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.slf4j.Logger;

/**
 * Log com limite de taxa para falhas repetidas (fail-open do admission, issue #41): com o serviço fora, cada requisição de reserva
 * falharia e geraria uma linha. Escreve no máximo uma linha por {@code intervaloNanos}, com a contagem acumulada desde a última.
 */
public final class LogComLimite {

    private final Consumer<String> saida;
    private final long intervaloNanos;
    private final LongSupplier relogio;
    private long ultimo;
    private boolean jaLogou;
    private long acumulado;

    private LogComLimite(Consumer<String> saida, long intervaloNanos, LongSupplier relogio) {
        this.saida = saida;
        this.intervaloNanos = intervaloNanos;
        this.relogio = relogio;
    }

    public static LogComLimite warn(Logger log, long intervaloNanos, LongSupplier relogio) {
        return new LogComLimite(log::warn, intervaloNanos, relogio);
    }

    public static LogComLimite error(Logger log, long intervaloNanos, LongSupplier relogio) {
        return new LogComLimite(log::error, intervaloNanos, relogio);
    }

    /** Registra uma ocorrência; escreve a linha só se passou o intervalo desde a última (a primeira sai na hora). */
    public synchronized void registrar(String mensagem) {
        acumulado++;
        long agora = relogio.getAsLong();
        if (jaLogou && agora - ultimo < intervaloNanos) {
            return;
        }
        saida.accept(mensagem + " [ocorrências desde o último log: " + acumulado + "]");
        acumulado = 0;
        ultimo = agora;
        jaLogou = true;
    }
}
