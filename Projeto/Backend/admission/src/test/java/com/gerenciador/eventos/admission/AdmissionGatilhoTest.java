package com.gerenciador.eventos.admission;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Os dois regimes do {@code POST /internal/admissions} (issue #43) por HTTP de verdade, com Redis, RabbitMQ e worker REAIS e um
 * reservation falso: abaixo do gatilho a resposta é a de sempre ({@code {"mode":"sync","rpm":n}}, byte a byte, como antes); ao cruzar 80% de
 * X ({@code ADMISSION_X_RPM} = 100 aqui, janela de 60 s para o RPM ser a contagem) o MESMO endpoint passa a devolver {@code queued} e o
 * worker leva cada tentativa ao {@code pending}; o braço {@code naive} nunca enfileira. A histerese exata é do {@link ModoDoEventoTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"internal.api.token=token-de-teste",
        "admission.amqp.prefix=${admission.test.prefix}gatilho-", "admission.bucket-ms=1000", "admission.window-ms=60000",
        "admission.x-rpm=100", "admission.worker.enabled=true", "admission.worker.concurrency=4", "admission.retry-backoff-ms=100"})
@DirtiesContext
class AdmissionGatilhoTest {

    private static final String TOKEN = "token-de-teste";

    @LocalServerPort private int porta;
    @Autowired private TestRestTemplate http;
    @Autowired private StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final FakeReservationServer reservation = FakeReservationServer.get();
    private final long evento = RedisDeTeste.eventoNovo();
    private long proximoUsuario = RedisDeTeste.usuarioNovo();

    @BeforeEach
    void preparar() {
        reservation.reset();
        reservation.capacidade(evento, 1000);
    }

    @AfterEach
    void limpar() {
        var chaves = redis.keys("adm:cnt:" + evento + ":*");
        if (chaves != null && !chaves.isEmpty()) {
            redis.delete(chaves);
        }
        redis.delete(AdmissionStore.chaveModo(evento));
    }

    private ResponseEntity<String> admitir(long usuario, String chave, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Internal-Token", TOKEN);
        String corpo = "{\"eventId\":" + evento + ",\"userId\":" + usuario + ",\"idempotencyKey\":\"" + chave + "\",\"path\":\"" + path + "\"}";
        return http.exchange("/internal/admissions", HttpMethod.POST, new HttpEntity<>(corpo, h), String.class);
    }

    private JsonNode resultado(long usuario, String chave) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", TOKEN);
        ResponseEntity<String> r = http.exchange("/internal/admissions/result?userId=" + usuario + "&idempotencyKey=" + chave,
                HttpMethod.GET, new HttpEntity<>(h), String.class);
        return r.getStatusCode() == HttpStatus.OK ? json.readTree(r.getBody()) : null;
    }

    private JsonNode aguardarFinal(long usuario, String chave) throws Exception {
        long fim = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonNode r;
        while ((r = resultado(usuario, chave)) == null || "queued".equals(r.path("status").asText())) {
            if (System.nanoTime() > fim) {
                throw new AssertionError("sem estado final em 20 s: " + r);
            }
            Thread.sleep(25);
        }
        return r;
    }

    @Test
    void abaixoDoGatilhoESyncComOContratoDeSempreEAcimaVaiParaAFilaEOWorkerLevaAoPending() throws Exception {
        int primeiraEmFila = -1;
        java.util.List<long[]> naFila = new java.util.ArrayList<>(); // {usuário, índice da chave}
        for (int i = 1; i <= 90; i++) {
            long usuario = proximoUsuario++;
            ResponseEntity<String> r = admitir(usuario, "k" + i, "cas");
            assertEquals(HttpStatus.OK, r.getStatusCode());
            JsonNode corpo = json.readTree(r.getBody());
            if (primeiraEmFila < 0 && "sync".equals(corpo.path("mode").asText())) {
                assertEquals("{\"mode\":\"sync\",\"rpm\":" + corpo.path("rpm").asLong() + "}", r.getBody(),
                        "abaixo do gatilho: o corpo é o de antes, só mode e rpm");
                assertTrue(corpo.path("rpm").asLong() < 80, "rpm abaixo de 80% de X (100): " + corpo);
                continue;
            }
            if (primeiraEmFila < 0) {
                primeiraEmFila = i;
            }
            assertEquals("queued", corpo.path("mode").asText(), "ligada a fila, o evento segue em fila: tentativa " + i);
            assertEquals("queued", corpo.path("status").asText());
            assertEquals("k" + i, corpo.path("idempotencyKey").asText());
            assertEquals(evento, corpo.path("eventId").asLong());
            assertTrue(corpo.path("rpm").asLong() >= 80, "rpm: " + corpo);
            naFila.add(new long[] {usuario, i});
        }
        assertTrue(primeiraEmFila == 79 || primeiraEmFila == 80,
                "RPM = tentativas na janela de 60 s: 80% de X = 80 RPM; virou fila na tentativa " + primeiraEmFila);

        for (long[] t : naFila) {
            JsonNode pronto = aguardarFinal(t[0], "k" + t[1]);
            assertEquals("pending", pronto.path("status").asText(), "o worker levou a tentativa " + t[1] + " ao pending");
            assertNotNull(pronto.path("expiresAt").asText(null), "o expiresAt do walletevent");
        }
        assertEquals(naFila.size(), reservation.reservasAtivas(evento), "o reservation só recebeu as tentativas da fila (as síncronas são do gateway)");
        assertEquals(naFila.size(), reservation.chamadasDeReserva());
    }

    @Test
    void obracoNaiveNuncaEnfileiraNemComAFilaLigada() throws Exception {
        long usuario = proximoUsuario++;
        for (int i = 1; i <= 90; i++) {
            assertEquals("sync", json.readTree(admitir(usuario, "n" + i, "naive").getBody()).path("mode").asText(),
                    "naive só conta, nunca vai para a fila (tentativa " + i + ")");
        }
        assertNull(resultado(usuario, "n90"), "nenhum resultado de fila para o naive");
        assertTrue(!redis.hasKey(AdmissionStore.chaveModo(evento)), "e o naive não liga o modo fila");
        assertEquals(0, reservation.chamadasDeReserva());
    }
}
