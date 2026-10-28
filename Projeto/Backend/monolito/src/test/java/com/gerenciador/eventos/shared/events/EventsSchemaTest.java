package com.gerenciador.eventos.shared.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Event store por serviço (issue #33): a infra de {@code shared.events} aponta para o schema do serviço
 * ({@code events.schema}), então cada serviço tem o seu {@code event_store}/{@code event_outbox}/checkpoint e nenhum lê o do
 * outro. Unitário (sem banco): o comportamento contra o Postgres é coberto pelos testes do monólito (schema {@code public}, o
 * default) e do módulo {@code reservation} (schema {@code reservation}).
 */
class EventsSchemaTest {

    @Test
    void defaultDoMonolitoEPublicEQualificaAsTabelas() {
        EventStore store = new EventStore(new ObjectMapper(), "public");
        assertEquals("public", store.schema());
        assertEquals("public.event_outbox", store.tabela("event_outbox"));
    }

    @Test
    void servicoUsaOProprioSchema() {
        EventStore store = new EventStore(new ObjectMapper(), "reservation");
        assertEquals("reservation.projection_checkpoint", store.tabela("projection_checkpoint"));
    }

    @Test
    void schemaInvalidoEhRecusado() {
        assertThrows(IllegalArgumentException.class, () -> new EventStore(new ObjectMapper(), "public; drop table x"));
        assertThrows(IllegalArgumentException.class, () -> new EventStore(new ObjectMapper(), "Reservation"));
    }

    /** O advisory lock é do BANCO: relays de serviços diferentes não podem disputar a mesma chave. */
    @Test
    void relaysDeSchemasDiferentesUsamChavesDeLockDiferentes() {
        assertEquals(OutboxRelay.LOCK_KEY, OutboxRelay.lockKey("public"), "public mantém a chave de sempre");
        assertNotEquals(OutboxRelay.LOCK_KEY, OutboxRelay.lockKey("reservation"));
        assertNotEquals(OutboxRelay.lockKey("reservation"), OutboxRelay.lockKey("notification"));
    }
}
