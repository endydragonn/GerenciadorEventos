package com.gerenciador.eventos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;

/**
 * Probes do Kubernetes (issues #197 e #207): {@code /actuator/health/liveness} e {@code /readiness} respondem 200 {@code UP}
 * sem token e sem detalhes. No Spring Boot 3.x os grupos só existem com {@code management.endpoint.health.probes.enabled}
 * (o manifest do cluster liga por env); por isso ficam numa classe própria: ligados, a raiz {@code /actuator/health} passa a
 * listar os {@code groups}, o que o {@code HttpContractTest} (health público sem detalhes) não espera.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GlobalDbTruncator.class)
@TestPropertySource(properties = "management.endpoint.health.probes.enabled=true")
public class ActuatorProbesTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;

    @Test
    void probesDoKubernetes_semToken_returns200UpSemDetalhes() {
        for (String path : new String[] {"/actuator/health/liveness", "/actuator/health/readiness"}) {
            ResponseEntity<Map> resp = restTemplate.getForEntity("http://localhost:" + port + path, Map.class);

            assertEquals(HttpStatus.OK, resp.getStatusCode(), path);
            assertEquals("UP", resp.getBody().get("status"), path);
            assertEquals(1, resp.getBody().size(), path + " não deve expor detalhes");
        }
    }
}
