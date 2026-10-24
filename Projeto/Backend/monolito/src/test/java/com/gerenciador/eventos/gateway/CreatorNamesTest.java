package com.gerenciador.eventos.gateway;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

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
}
