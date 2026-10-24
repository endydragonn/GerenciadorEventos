package com.gerenciador.eventos.gateway;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;

import com.gerenciador.eventos.catalog.api.Event;
import com.gerenciador.eventos.identity.api.UserApi;

/** O TTL do cache de {@code creator_name} (issue #35) é 45 s ±20%, sorteado por entrada (sem Spring, sem banco). */
class CreatorNamesTest {

    @Test
    void ttlFicaEntre36e54sEVariaPorEntrada() {
        Set<Duration> vistos = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            Duration ttl = CreatorNames.ttlComJitter();
            assertTrue(ttl.compareTo(Duration.ofSeconds(36)) >= 0 && ttl.compareTo(Duration.ofSeconds(54)) <= 0,
                    "fora de 45 s ±20%: " + ttl);
            vistos.add(ttl);
        }
        assertTrue(vistos.size() > 1, "o jitter sorteia TTLs diferentes");
    }

    /**
     * Falha do identity ao buscar os nomes (banco fora, timeout) não derruba a resposta de evento: o nome fica nulo (como o de
     * conta desativada) e nada é cacheado, então a próxima resposta tenta de novo.
     */
    @Test
    void falhaAoBuscarNomesDeixaNomeNuloSemCachearETentaDeNovo() {
        UserApi userApi = mock(UserApi.class);
        when(userApi.findNames(anyCollection())).thenThrow(new RuntimeException("banco fora")).thenReturn(Map.of(7L, "Ana"));
        CreatorNames nomes = new CreatorNames(userApi);
        Event e = new Event();
        e.setCreator_id(7L);

        assertSame(e, nomes.preencher(e), "não lança");
        assertNull(e.getCreator_name());
        assertEquals(List.of(e), nomes.preencher(List.of(e)));
        assertEquals("Ana", e.getCreator_name(), "a falha não foi cacheada: a 2ª chamada consultou de novo");
        verify(userApi, times(2)).findNames(anyCollection());
    }
}
