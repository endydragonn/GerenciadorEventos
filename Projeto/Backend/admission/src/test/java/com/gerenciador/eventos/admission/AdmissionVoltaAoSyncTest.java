package com.gerenciador.eventos.admission;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * O ciclo COMPLETO da histerese (issue #43), com Redis, RabbitMQ e worker REAIS e o reservation falso, na janela padrão (50 ms / 200 ms) e
 * {@code ADMISSION_X_RPM} = 600 (80% = 480 RPM; uma tentativa isolada lê no máximo ~397 RPM): uma rajada liga o modo fila do evento, o worker
 * drena (a espera esvazia) e, passada a janela, a PRÓXIMA requisição lê uso < 70% e volta ao {@code sync}, com o modo desligado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"internal.api.token=token-de-teste",
        "admission.amqp.prefix=${admission.test.prefix}volta-", "admission.x-rpm=600", "admission.worker.enabled=true",
        "admission.worker.concurrency=4", "admission.retry-backoff-ms=100"})
@DirtiesContext
class AdmissionVoltaAoSyncTest {

    private static final String TOKEN = "token-de-teste";

    @Autowired private TestRestTemplate http;
    @Autowired private StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final FakeReservationServer reservation = FakeReservationServer.get();
    private final long evento = RedisDeTeste.eventoNovo();
    private long proximoUsuario = RedisDeTeste.usuarioNovo();

    @BeforeEach
    void preparar() {
        reservation.reset();
        reservation.capacidade(evento, 100);
    }

    @AfterEach
    void limpar() {
        var chaves = redis.keys("adm:cnt:" + evento + ":*");
        if (chaves != null && !chaves.isEmpty()) {
            redis.delete(chaves);
        }
        redis.delete(AdmissionStore.chaveModo(evento));
        redis.delete(AdmissionStore.chaveEspera(evento));
    }

    private JsonNode admitir(long usuario, String chave) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Internal-Token", TOKEN);
        String corpo = "{\"eventId\":" + evento + ",\"userId\":" + usuario + ",\"idempotencyKey\":\"" + chave + "\",\"path\":\"cas\"}";
        ResponseEntity<String> r = http.exchange("/internal/admissions", HttpMethod.POST, new HttpEntity<>(corpo, h), String.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return json.readTree(r.getBody());
    }

    private boolean pendente(long usuario, String chave) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", TOKEN);
        ResponseEntity<String> r = http.exchange("/internal/admissions/result?userId=" + usuario + "&idempotencyKey=" + chave,
                HttpMethod.GET, new HttpEntity<>(h), String.class);
        return r.getStatusCode() == HttpStatus.OK && "pending".equals(json.readTree(r.getBody()).path("status").asText());
    }

    @Test
    void rajadaLigaAFilaOWorkerDrenaEAProximaRequisicaoVoltaAoSync() throws Exception {
        List<long[]> naFila = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            long usuario = proximoUsuario++;
            JsonNode r = admitir(usuario, "k" + i);
            if ("queued".equals(r.path("mode").asText())) {
                naFila.add(new long[] {usuario, i});
            }
        }
        assertFalse(naFila.isEmpty(), "6 tentativas em sequência passam de 80% de X (480 RPM) na janela de 200 ms: o evento entrou em fila");
        assertTrue(redis.hasKey(AdmissionStore.chaveModo(evento)), "modo fila ligado");

        long fim = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        for (long[] t : naFila) {
            while (!pendente(t[0], "k" + t[1])) {
                assertTrue(System.nanoTime() < fim, "o worker não levou a tentativa " + t[1] + " ao pending em 20 s");
                Thread.sleep(25);
            }
        }
        assertEquals(naFila.size(), reservation.reservasAtivas(evento), "o worker fez as reservas da fila");
        assertEquals(0, redis.opsForZSet().zCard(AdmissionStore.chaveEspera(evento)), "a fila drenou: a espera do evento está vazia");

        Thread.sleep(500); // a janela de 200 ms (e o bucket corrente) passa: a carga do evento cai a zero

        JsonNode volta = admitir(proximoUsuario++, "k-depois");
        assertEquals("sync", volta.path("mode").asText(), "uso < 70% e espera vazia: a próxima requisição volta ao síncrono");
        assertTrue(volta.path("rpm").asLong() < 420, "uma tentativa isolada lê < 70% de X (420 RPM): " + volta);
        assertFalse(redis.hasKey(AdmissionStore.chaveModo(evento)), "e o modo fila do evento foi desligado");
        Thread.sleep(500); // a tentativa isolada sai da janela: duas seguidas (~600+ RPM) reacenderiam a fila
        assertEquals("sync", admitir(proximoUsuario++, "k-depois-2").path("mode").asText(), "segue síncrono");
    }
}
