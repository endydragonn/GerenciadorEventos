package com.gerenciador.eventos.reservation;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.gerenciador.eventos.TestSupport.StockEmbutido;
import com.gerenciador.eventos.stockclient.StockClient;
import com.gerenciador.eventos.stockclient.api.EventCap;

/**
 * Base dos testes do serviço reservation. Como o serviço não enxerga {@code users}/{@code event} (outro schema, sem FK),
 * "usuário" e "evento" são só ids: o evento ganha o {@code eventcap} no serviço stock (que é o "evento existe" dentro da saga) e o
 * usuário não precisa de nada. Os ids vêm de um contador por JVM, então testes de classes diferentes nunca colidem.
 *
 * <p>Desde a #47 a capacidade é do stock, que sobe REAL dentro da JVM de teste ({@link StockEmbutido}, atrás de um proxy de falhas); o
 * contexto do reservation aponta o {@code StockClient} para o proxy. {@link #estoque} fala DIRETO com o stock (sem as falhas injetadas)
 * para criar e ler a capacidade; a devolução de vaga por cancelamento/expiração é assíncrona (consumer do stock), então os testes chamam
 * {@link #drenarDevolucoes()} antes de olhar o {@code quant}.</p>
 */
abstract class ReservationTestSupport {

    private static final AtomicLong IDS = new AtomicLong(1);

    @DynamicPropertySource
    static void stockEmbutido(DynamicPropertyRegistry registry) {
        registry.add("stock.service.url", StockEmbutido::urlDoProxy);
    }

    /** Cliente DIRETO ao stock real (sem proxy): cria e lê a capacidade. */
    protected final StockClient estoque = StockEmbutido.get().direto();

    /** Usuário de teste: só um id (a existência do usuário e da carteira é conferida pelo gateway, não por este serviço). */
    record Usuario(Long id) {
        public Long getId() {
            return id;
        }
    }

    /** Evento de teste: só um id, com o {@code eventcap} criado no stock. */
    record Evento(Long id) {
        public Long getEvent_id() {
            return id;
        }
    }

    protected Usuario novoUsuario(String ignorado) {
        return new Usuario(IDS.getAndIncrement());
    }

    protected Usuario novoUsuario(String ignorado, String tambemIgnorado) {
        return novoUsuario(ignorado);
    }

    /** Evento com capacidade {@code capacidade} (-1 = ilimitado), sem janela de compra. */
    protected Evento novoEvento(Long ignorado, String nome, int capacidade) {
        return novoEvento(ignorado, nome, capacidade, null);
    }

    /** Evento com capacidade {@code capacidade} e janela de compra até {@code buyTimeLimit} (null = sem janela). */
    protected Evento novoEvento(Long ignorado, String nome, int capacidade, LocalDateTime buyTimeLimit) {
        Evento e = new Evento(IDS.getAndIncrement());
        estoque.create(new EventCap(e.id(), capacidade, capacidade, buyTimeLimit));
        return e;
    }

    /**
     * Capacidade lida do stock DEPOIS de o consumer de devolução processar o que já foi appendado: o estado a que a saga converge
     * (cancelar/expirar devolvem a vaga de forma assíncrona). Para olhar o "antes" da devolução, use {@link #estoque} direto.
     */
    protected EventCap capacidade(Long eventId) {
        try {
            drenarDevolucoes();
        } catch (Exception ex) {
            throw new IllegalStateException("consumer de devolução do stock falhou: " + ex, ex);
        }
        return estoque.findByEventId(eventId);
    }

    /** Roda o caminho do consumer do stock sobre os cancelamentos/expirações já appendados: a vaga volta ({@code quant}). */
    protected int drenarDevolucoes() throws Exception {
        return StockEmbutido.get().liberarDevolucoes();
    }
}
