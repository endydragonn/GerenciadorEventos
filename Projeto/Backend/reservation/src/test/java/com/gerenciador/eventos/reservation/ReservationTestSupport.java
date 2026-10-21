package com.gerenciador.eventos.reservation;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;

import com.gerenciador.eventos.reservationclient.api.EventCap;

/**
 * Base dos testes do serviço reservation. Como o serviço não enxerga {@code users}/{@code event} (outro schema, sem FK),
 * "usuário" e "evento" são só ids: o evento ganha o {@code eventcap} (que é o "evento existe" dentro do serviço) e o
 * usuário não precisa de nada. Os ids vêm de um contador por JVM, então testes de classes diferentes nunca colidem.
 */
abstract class ReservationTestSupport {

    private static final AtomicLong IDS = new AtomicLong(1);

    @Autowired
    protected EventCapRepository eventCapRepository;

    /** Usuário de teste: só um id (a existência do usuário e da carteira é conferida pelo gateway, não por este serviço). */
    record Usuario(Long id) {
        public Long getId() {
            return id;
        }
    }

    /** Evento de teste: só um id, com o {@code eventcap} criado. */
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
        Evento e = new Evento(IDS.getAndIncrement());
        eventCapRepository.create(new EventCap(e.id(), capacidade, capacidade, null));
        return e;
    }
}
