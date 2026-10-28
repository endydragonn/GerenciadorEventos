package com.gerenciador.eventos.admission;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
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

import com.gerenciador.eventos.admissionclient.AdmissionClient;
import com.gerenciador.eventos.admissionclient.api.AdmissionRequest;
import com.gerenciador.eventos.admissionclient.api.AdmissionResult;

/**
 * A API interna do admission (issue #41) por HTTP de verdade, com Redis REAL: o token de serviço em TODA requisição (401 sem/errado,
 * inclusive com truque de path), o contrato de {@code POST /internal/admissions} e de {@code GET .../rpm}, os 400 de validação (a
 * regra da Idempotency-Key é a do reservation) e o {@code AdmissionClient} REAL contra o serviço REAL. Janela de 60 s e bucket de
 * 1 s só para o RPM ser a contagem exata, sem depender do relógio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "internal.api.token=token-de-teste", "admission.bucket-ms=1000", "admission.window-ms=60000"})
class AdmissionApiTest {

    private static final String TOKEN = "token-de-teste";

    @LocalServerPort private int porta;
    @Autowired private TestRestTemplate http;
    @Autowired private StringRedisTemplate redis;
    private long evento = RedisDeTeste.eventoNovo();

    @AfterEach
    void limpar() {
        var chaves = redis.keys("adm:cnt:" + evento + ":*");
        if (chaves != null && !chaves.isEmpty()) {
            redis.delete(chaves);
        }
    }

    private ResponseEntity<String> chamar(HttpMethod metodo, String caminho, String token, String corpo) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.set("X-Internal-Token", token);
        }
        return http.exchange(caminho, metodo, new HttpEntity<>(corpo, h), String.class);
    }

    private String pedido(long eventId, Long userId, String chave, String path) {
        return "{\"eventId\":" + eventId + ",\"userId\":" + userId + ",\"idempotencyKey\":"
                + (chave == null ? "null" : "\"" + chave + "\"") + ",\"path\":" + (path == null ? "null" : "\"" + path + "\"") + "}";
    }

    @Test
    void semTokenOuComTokenErradoEmQualquerRotaDa401() {
        String corpo = pedido(evento, 1L, "k", "cas");
        for (String token : new String[] {null, "", "errado"}) {
            assertEquals(HttpStatus.UNAUTHORIZED, chamar(HttpMethod.POST, "/internal/admissions", token, corpo).getStatusCode());
            assertEquals(HttpStatus.UNAUTHORIZED,
                    chamar(HttpMethod.GET, "/internal/admissions/events/" + evento + "/rpm", token, null).getStatusCode());
        }
        assertEquals(HttpStatus.UNAUTHORIZED, chamar(HttpMethod.POST, "/internal;x=1/admissions", null, corpo).getStatusCode(),
                "o filtro não filtra por caminho: o truque do ';' não passa");
        assertEquals(HttpStatus.UNAUTHORIZED, chamar(HttpMethod.GET, "/qualquer/coisa", null, null).getStatusCode(),
                "o serviço não tem rota pública");
        assertEquals(0, redis.keys("adm:cnt:" + evento + ":*").size(), "o 401 não conta nada");
    }

    @Test
    void postContaEDevolveSyncComORpm() {
        ResponseEntity<String> r1 = chamar(HttpMethod.POST, "/internal/admissions", TOKEN, pedido(evento, 1L, "k1", "cas"));
        ResponseEntity<String> r2 = chamar(HttpMethod.POST, "/internal/admissions", TOKEN, pedido(evento, 2L, "k2", "naive"));

        assertEquals(HttpStatus.OK, r1.getStatusCode());
        assertEquals("{\"mode\":\"sync\",\"rpm\":1}", r1.getBody());
        assertEquals("{\"mode\":\"sync\",\"rpm\":2}", r2.getBody(), "os dois braços contam no mesmo contador do evento");
    }

    @Test
    void getRpmEhSoLeituraEIsolaPorEvento() {
        chamar(HttpMethod.POST, "/internal/admissions", TOKEN, pedido(evento, 1L, "k1", "cas"));
        chamar(HttpMethod.POST, "/internal/admissions", TOKEN, pedido(evento, 1L, "k2", "cas"));

        String lido = chamar(HttpMethod.GET, "/internal/admissions/events/" + evento + "/rpm", TOKEN, null).getBody();
        String denovo = chamar(HttpMethod.GET, "/internal/admissions/events/" + evento + "/rpm", TOKEN, null).getBody();
        String outro = chamar(HttpMethod.GET, "/internal/admissions/events/" + (evento + 7) + "/rpm", TOKEN, null).getBody();

        assertEquals("{\"eventId\":" + evento + ",\"rpm\":2}", lido);
        assertEquals(lido, denovo, "a leitura não conta");
        assertEquals("{\"eventId\":" + (evento + 7) + ",\"rpm\":0}", outro);
    }

    @Test
    void pedidoInvalidoDa400ENaoConta() {
        String chaveLonga = "x".repeat(256);
        Map<String, String> casos = Map.of(
                "chave vazia", pedido(evento, 1L, "  ", "cas"),
                "chave ausente", pedido(evento, 1L, null, "cas"),
                "chave > 255", pedido(evento, 1L, chaveLonga, "cas"),
                "evento zero", pedido(0, 1L, "k", "cas"),
                "usuário ausente", pedido(evento, null, "k", "cas"),
                "path inválido", pedido(evento, 1L, "k", "xyz"),
                "json ilegível", "{nao e json");
        casos.forEach((nome, corpo) -> {
            ResponseEntity<String> r = chamar(HttpMethod.POST, "/internal/admissions", TOKEN, corpo);
            assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode(), nome);
        });
        assertEquals("Idempotency-Key deve ter no máximo 255 caracteres",
                chamar(HttpMethod.POST, "/internal/admissions", TOKEN, pedido(evento, 1L, chaveLonga, "cas")).getBody(),
                "a mensagem é a do reservation (o gateway repassa o 400 igual)");
        assertEquals(HttpStatus.BAD_REQUEST,
                chamar(HttpMethod.GET, "/internal/admissions/events/0/rpm", TOKEN, null).getStatusCode());
        assertEquals(0, redis.keys("adm:cnt:" + evento + ":*").size(), "pedido inválido não conta");
    }

    @Test
    void clienteRealContraServicoReal() throws IOException {
        try (AdmissionClient cliente = new AdmissionClient("http://localhost:" + porta, TOKEN, 5, Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(20), 5, Duration.ofSeconds(5), System::nanoTime)) {
            AdmissionResult r = cliente.admit(new AdmissionRequest(evento, 9L, "chave-real", "cas"));
            assertEquals(AdmissionResult.sync(1), r);
            assertEquals(AdmissionResult.sync(2), cliente.admit(new AdmissionRequest(evento, 10L, "chave-real-2", "naive")));

            assertEquals(AdmissionResult.sync(-1), cliente.admit(new AdmissionRequest(evento, 9L, "x".repeat(300), "cas")),
                    "o 400 do serviço (chave longa) não falha a reserva: sync, sem contar");
        }
        try (AdmissionClient tokenErrado = new AdmissionClient("http://localhost:" + porta, "outro-token", 5, Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(20), 5, Duration.ofSeconds(5), System::nanoTime)) {
            assertEquals(AdmissionResult.sync(-1), tokenErrado.admit(new AdmissionRequest(evento, 9L, "k", "cas")),
                    "token errado: o cliente faz fail-open (e loga ERROR)");
        }
        assertTrue(redis.keys("adm:cnt:" + evento + ":*").size() >= 1);
    }
}
