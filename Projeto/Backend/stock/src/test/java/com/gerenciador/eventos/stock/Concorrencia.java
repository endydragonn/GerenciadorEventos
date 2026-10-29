package com.gerenciador.eventos.stock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Simula "N réplicas rodando o mesmo job ao mesmo tempo" (issue #111, copiada do reservation): cada tarefa roda numa thread própria (logo, na sua
 * própria conexão do pool) e todas largam juntas numa {@link CyclicBarrier}. Falha em qualquer tarefa propaga.
 */
final class Concorrencia {

    private Concorrencia() {
    }

    /** A mesma tarefa em {@code n} threads. */
    static <T> List<T> emParalelo(int n, Callable<T> tarefa) throws Exception {
        return emParalelo(Collections.nCopies(n, tarefa));
    }

    /** Uma tarefa por thread (ex.: uma instância de relay cada), todas largando juntas. */
    static <T> List<T> emParalelo(List<Callable<T>> tarefas) throws Exception {
        CyclicBarrier largada = new CyclicBarrier(tarefas.size());
        ExecutorService pool = Executors.newFixedThreadPool(tarefas.size());
        try {
            List<Future<T>> futuros = new ArrayList<>();
            for (Callable<T> t : tarefas) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return t.call();
                }));
            }
            List<T> resultados = new ArrayList<>();
            for (Future<T> f : futuros) {
                resultados.add(f.get(120, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }
}
