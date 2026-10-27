package com.gerenciador.eventos;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.ResponseEntity;

import com.gerenciador.eventos.TestSupport.FakeCatalogServer;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.identity.UserService;
import com.gerenciador.eventos.identityclient.api.User;

/**
 * Caso 4 do spec (issue #35, aceite 7): sem {@code SEED_ENABLED} ({@code seed.enabled}, default {@code false}) o
 * {@code POST /bff/seed} responde 404 e NADA é criado — nem usuário, nem evento no catalog. Ligado, ele semeia como antes
 * ({@code HttpContractTest}, que roda com a flag ligada, e o kit de carga pelo {@code carga/docker-compose.carga.yml}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GlobalDbTruncator.class)
@TestPropertySource(properties = "seed.enabled=false") // fixo: não depende do default nem de um SEED_ENABLED no ambiente/.env
public class SeedDesligadoTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserService userService;

    @Test
    void seedSemAFlagDa404ENaoCriaNada() {
        User criador = new User();
        criador.setName("Seed desligado criador");
        criador.setEmail("seed-desligado-criador@example.com");
        criador.setFone("11955550001");
        criador = userService.createUser(criador);

        Map<String, Object> usuario = new HashMap<>();
        usuario.put("name", "Seed desligado novo");
        usuario.put("email", "seed-desligado-novo@example.com");
        usuario.put("password", "senha123");
        usuario.put("fone", "11955550002");
        usuario.put("isAdmin", true);
        Map<String, Object> evento = new HashMap<>();
        evento.put("creator_id", criador.getId());
        evento.put("event_name", "Seed desligado " + System.nanoTime());
        evento.put("is_EAD", true);
        evento.put("event_date", LocalDateTime.now().plusDays(5).toString());
        evento.put("lot_quantity", 5);
        evento.put("description", "desc");
        FakeCatalogServer catalog = FakeCatalogServer.get();
        int eventosAntes = catalog.quantidadeDeEventos();
        long postsAntes = catalog.chamadas().stream().filter(c -> c.startsWith("POST")).count();

        ResponseEntity<String> resp = restTemplate.postForEntity("http://localhost:" + port + "/bff/seed",
                Map.of("users", List.of(usuario), "events", List.of(evento)), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals(eventosAntes, catalog.quantidadeDeEventos(), "nenhum evento criado no catalog");
        assertEquals(postsAntes, catalog.chamadas().stream().filter(c -> c.startsWith("POST")).count(),
                "nada foi enviado ao catalog");
        assertThrows(RuntimeException.class, () -> userService.findByEmail("seed-desligado-novo@example.com"),
                "nenhum usuário criado");
    }
}
