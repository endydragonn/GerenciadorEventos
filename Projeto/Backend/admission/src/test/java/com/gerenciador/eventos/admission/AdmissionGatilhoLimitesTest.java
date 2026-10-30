package com.gerenciador.eventos.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionBlockedEvent;
import org.springframework.amqp.rabbit.connection.ConnectionUnblockedEvent;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

/**
 * As respostas de negócio e o fail-open do modo fila (issue #43), HTTP real com Redis e RabbitMQ REAIS e SEM worker (as mensagens ficam
 * na fila): com {@code ADMISSION_X_RPM} = 1 todo evento entra em fila na 1ª tentativa. Repetição da chave devolve o estado sem publicar de
 * novo; chave de outro evento = 422; limite de 2 em espera por usuário = 429; fila cheia ({@code x-max-length} 3) = 429; broker bloqueado =
 * {@code sync} (a reserva segue pelo caminho de hoje, sem {@code queued} fantasma); o braço {@code naive} segue {@code sync}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"internal.api.token=token-de-teste",
        "admission.amqp.prefix=${admission.test.prefix}limites-", "admission.x-rpm=1", "admission.max-waiting-per-user=2",
        "admission.queue.max-length=3"})
@DirtiesContext
class AdmissionGatilhoLimitesTest {

    private static final String TOKEN = "token-de-teste";
    private static final String FILA = RabbitDeTeste.prefixo("limites-") + "admission.requests";

    @Autowired private TestRestTemplate http;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RabbitAdmin admin;
    @Autowired private ApplicationContext contexto;
    private final long evento = RedisDeTeste.eventoNovo();
    private final long usuario = RedisDeTeste.usuarioNovo();

    @BeforeEach
    void esvaziarAFila() {
        admin.initialize();
        admin.purgeQueue(FILA, true);
    }

    @AfterEach
    void limpar() {
        for (long e : new long[] {evento, evento + 1}) {
            apagar("adm:cnt:" + e + ":*");
            redis.delete(AdmissionStore.chaveModo(e));
            redis.delete(AdmissionStore.chaveEspera(e));
        }
        for (long desloc : new long[] {0, 1, 100, 101, 102, 200}) {
            apagar("adm:*:" + (usuario + desloc) + "*");
        }
    }

    private void apagar(String padrao) {
        var chaves = redis.keys(padrao);
        if (chaves != null && !chaves.isEmpty()) {
            redis.delete(chaves);
        }
    }

    private ResponseEntity<String> admitir(long ev, long user, String chave, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Internal-Token", TOKEN);
        String corpo = "{\"eventId\":" + ev + ",\"userId\":" + user + ",\"idempotencyKey\":\"" + chave + "\",\"path\":\"" + path + "\"}";
        return http.exchange("/internal/admissions", HttpMethod.POST, new HttpEntity<>(corpo, h), String.class);
    }

    private int naFila() {
        return admin.getQueueInfo(FILA).getMessageCount();
    }

    @Test
    void repeticaoDaChaveDevolveOEstadoAtualSemPublicarDeNovo() {
        ResponseEntity<String> primeira = admitir(evento, usuario, "k1", "cas");
        ResponseEntity<String> repetida = admitir(evento, usuario, "k1", "cas");

        assertEquals(HttpStatus.OK, primeira.getStatusCode());
        assertTrue(primeira.getBody().startsWith("{\"mode\":\"queued\",\"rpm\":"), primeira.getBody());
        assertTrue(primeira.getBody().endsWith(",\"status\":\"queued\",\"idempotencyKey\":\"k1\",\"eventId\":" + evento + "}"),
                primeira.getBody());
        assertEquals(HttpStatus.OK, repetida.getStatusCode());
        assertTrue(repetida.getBody().contains("\"status\":\"queued\""), repetida.getBody());
        assertEquals(1, naFila(), "a repetição não publicou outra mensagem");
    }

    @Test
    void chaveDeOutroEventoDa422ComOTextoDoReservation() {
        admitir(evento, usuario, "k1", "cas");

        ResponseEntity<String> r = admitir(evento + 1, usuario, "k1", "cas");

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, r.getStatusCode());
        assertEquals("Idempotency-Key já usada em outra requisição (evento diferente)", r.getBody());
        assertEquals(1, naFila());
    }

    @Test
    void limiteDeMensagensEmEsperaPorUsuarioDa429() {
        assertEquals(HttpStatus.OK, admitir(evento, usuario, "k1", "cas").getStatusCode());
        assertEquals(HttpStatus.OK, admitir(evento, usuario, "k2", "cas").getStatusCode());

        ResponseEntity<String> r = admitir(evento, usuario, "k3", "cas");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, r.getStatusCode());
        assertEquals("Muitas reservas na fila para este usuário", r.getBody());
        assertEquals(2, naFila(), "o excedente não foi publicado");
    }

    @Test
    void filaCheiaDa429ENaoManda() {
        for (int i = 0; i < 3; i++) {
            assertEquals(HttpStatus.OK, admitir(evento, usuario + 100 + i, "k", "cas").getStatusCode());
        }

        ResponseEntity<String> r = admitir(evento, usuario + 200, "k", "cas");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, r.getStatusCode());
        assertEquals("Fila de admissão cheia, tente mais tarde", r.getBody());
        assertEquals(3, naFila());
    }

    @Test
    void brokerBloqueadoFazOEventoSeguirSincronoSemQueuedFantasma() {
        Connection conexao = Mockito.mock(Connection.class);
        contexto.publishEvent(new ConnectionBlockedEvent(conexao, "memory"));
        try {
            ResponseEntity<String> r = admitir(evento, usuario, "k1", "cas");

            assertEquals(HttpStatus.OK, r.getStatusCode());
            assertTrue(r.getBody().startsWith("{\"mode\":\"sync\",\"rpm\":"), r.getBody());
            assertTrue(!r.getBody().contains("status"), r.getBody());
            assertNull(redis.opsForValue().get(AdmissionStore.chaveResultado(usuario, "k1")), "entrada desfeita");
            assertEquals(0, naFila());
        } finally {
            contexto.publishEvent(new ConnectionUnblockedEvent(conexao));
        }
    }

    @Test
    void obracoNaiveNaoEntraNaFilaMesmoComOEventoEmFila() {
        admitir(evento, usuario, "k1", "cas"); // liga a fila do evento

        ResponseEntity<String> r = admitir(evento, usuario + 1, "k2", "naive");

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertTrue(r.getBody().startsWith("{\"mode\":\"sync\",\"rpm\":"), r.getBody());
        assertEquals(1, naFila());
    }
}
