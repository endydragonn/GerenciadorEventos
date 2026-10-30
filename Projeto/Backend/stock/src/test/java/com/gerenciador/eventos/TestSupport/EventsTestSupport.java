package com.gerenciador.eventos.TestSupport;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Helpers dos testes do serviço stock (cópia enxuta do TestSupport do reservation, issue #47). */
public final class EventsTestSupport {

    private EventsTestSupport() {
    }

    /** Espera a condição ficar verdadeira, com timeout (nunca dorme "no escuro" sem limite). */
    public static void aguardar(Duration timeout, BooleanSupplier condicao, String descricao) throws Exception {
        long fim = System.nanoTime() + timeout.toNanos();
        while (!condicao.getAsBoolean()) {
            if (System.nanoTime() > fim) {
                throw new AssertionError("Timeout (" + timeout + ") esperando: " + descricao);
            }
            Thread.sleep(50);
        }
    }
}
