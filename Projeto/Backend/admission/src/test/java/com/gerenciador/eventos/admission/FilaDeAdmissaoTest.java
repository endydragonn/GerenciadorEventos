package com.gerenciador.eventos.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionBlockedEvent;
import org.springframework.amqp.rabbit.connection.ConnectionUnblockedEvent;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.admission.FilaDeAdmissao.ChaveReusadaException;
import com.gerenciador.eventos.admission.FilaDeAdmissao.LimiteUsuarioException;
import com.gerenciador.eventos.admission.FilaDeAdmissao.NaoPublicadaException;
import com.gerenciador.eventos.admission.FilaDeAdmissao.ResultadoFila;
import com.gerenciador.eventos.admissionclient.api.AdmissionRequest;
import com.gerenciador.eventos.admissionclient.api.AdmissionStatus;

/**
 * O caminho de enfileirar (issue #42) com RabbitMQ e Redis REAIS (sem worker: a mensagem fica na fila e o teste a lê): mensagem persistente,
 * sem dado pessoal, na routing key do evento; resultado {@code queued} e espera gravados ANTES de publicar; repetição da chave (estado
 * atual, 422, refila depois de falha), limite por usuário (429) e broker bloqueado (não publica e desfaz).
 */
@SpringBootTest(properties = {"internal.api.token=token-de-teste", "admission.amqp.prefix=${admission.test.prefix}fila-"})
@DirtiesContext
class FilaDeAdmissaoTest {

    private static final String FILA = RabbitDeTeste.prefixo("fila-") + "admission.requests";

    @Autowired private FilaDeAdmissao fila;
    @Autowired private AdmissionStore store;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private RabbitAdmin admin;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ApplicationContext contexto;
    private final ObjectMapper json = new ObjectMapper();
    private final long evento = RedisDeTeste.eventoNovo();
    private final long usuario = RedisDeTeste.usuarioNovo();

    @BeforeEach
    void esvaziarAFila() {
        admin.initialize();
        admin.purgeQueue(FILA, true);
    }

    private AdmissionRequest pedido(long user, long event, String chave) {
        return new AdmissionRequest(event, user, chave, "cas");
    }

    private Message proxima() {
        return rabbit.receive(FILA, 2_000);
    }

    @Test
    void publicaMensagemPersistenteSemDadoPessoalNaRotaDoEventoEGravaQueuedEEspera() throws Exception {
        long antes = System.currentTimeMillis();
        ResultadoFila r = fila.enfileirar(pedido(usuario, evento, "chave-1"));

        assertTrue(r.publicada());
        assertEquals(new AdmissionStatus("queued", evento, null, null), r.estado());
        Message m = proxima();
        assertNotNull(m, "a mensagem está na fila admission.requests");
        assertEquals("event." + evento, m.getMessageProperties().getReceivedRoutingKey());
        assertEquals(MessageDeliveryMode.PERSISTENT, m.getMessageProperties().getReceivedDeliveryMode());
        JsonNode corpo = json.readTree(m.getBody());
        assertEquals(java.util.Set.of("userId", "eventId", "idempotencyKey", "attemptId", "deadline"), campos(corpo),
                "só ids, a chave, a tentativa e o deadline: sem dado pessoal");
        assertEquals(usuario, corpo.get("userId").asLong());
        assertEquals(evento, corpo.get("eventId").asLong());
        assertEquals("chave-1", corpo.get("idempotencyKey").asText());
        long deadline = corpo.get("deadline").asLong();
        assertTrue(deadline >= antes + 30_000 && deadline <= System.currentTimeMillis() + 30_000, "deadline = decisão + TTL da fila");

        ResultadoDaFila resultado = store.resultado(usuario, "chave-1");
        assertEquals("queued", resultado.status());
        assertEquals(corpo.get("attemptId").asText(), resultado.attemptId(), "mensagem, espera e resultado amarrados pela tentativa");
        assertEquals(deadline, resultado.deadline());
        assertEquals(1, redis.opsForZSet().zCard(AdmissionStore.chaveEspera(evento)));
        assertEquals(1, redis.opsForZSet().zCard(AdmissionStore.chaveEsperaUsuario(usuario)));
        assertEquals(new AdmissionStatus("queued", evento, null, null), fila.consultar(usuario, "chave-1").orElseThrow());
    }

    @Test
    void repeticaoDaChaveDevolveOEstadoAtualSemPublicarDeNovo() {
        fila.enfileirar(pedido(usuario, evento, "chave-1"));
        assertNotNull(proxima());

        ResultadoFila repetida = fila.enfileirar(pedido(usuario, evento, "chave-1"));

        assertFalse(repetida.publicada());
        assertEquals("queued", repetida.estado().status());
        assertNull(proxima(), "nenhuma segunda mensagem");
    }

    @Test
    void chaveDeOutroEventoLancaChaveReusadaESemPublicar() {
        fila.enfileirar(pedido(usuario, evento, "chave-1"));
        assertNotNull(proxima());

        ChaveReusadaException ex = assertThrows(ChaveReusadaException.class, () -> fila.enfileirar(pedido(usuario, evento + 1, "chave-1")));

        assertEquals("Idempotency-Key já usada em outra requisição (evento diferente)", ex.getMessage());
        assertNull(proxima());
    }

    @Test
    void rejeitadaOuVencidaRefilaComTentativaNova() throws Exception {
        fila.enfileirar(pedido(usuario, evento, "chave-1"));
        JsonNode primeira = json.readTree(proxima().getBody());
        AdmissionMessage m = new AdmissionMessage(usuario, evento, "chave-1", primeira.get("attemptId").asText(), primeira.get("deadline").asLong());
        store.concluir(m, store.resultado(usuario, "chave-1").rejected("Evento lotado"));

        ResultadoFila refila = fila.enfileirar(pedido(usuario, evento, "chave-1"));

        assertTrue(refila.publicada(), "como no síncrono, uma falha não consome a chave");
        JsonNode segunda = json.readTree(proxima().getBody());
        assertTrue(!segunda.get("attemptId").asText().equals(primeira.get("attemptId").asText()), "tentativa nova");
        assertEquals(segunda.get("attemptId").asText(), store.resultado(usuario, "chave-1").attemptId());
    }

    @Test
    void usuarioNoLimiteDeMensagensEmEsperaRecebeLimiteSemPublicar() {
        for (int i = 1; i <= 5; i++) {
            fila.enfileirar(pedido(usuario, evento, "chave-" + i));
        }

        assertThrows(LimiteUsuarioException.class, () -> fila.enfileirar(pedido(usuario, evento, "chave-6")));

        int mensagens = 0;
        while (proxima() != null) {
            mensagens++;
        }
        assertEquals(5, mensagens, "a 6ª não foi publicada");
        assertNull(store.resultado(usuario, "chave-6"));
        assertEquals("queued", fila.enfileirar(pedido(usuario, evento, "chave-1")).estado().status(), "a repetição passa antes do limite");
    }

    @Test
    void filaSoAceitaOBracoCasEONaiveFalhaComMensagemClaraSemTocarNoRedis() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fila.enfileirar(new AdmissionRequest(evento, usuario, "chave-naive", "naive")));

        assertEquals("a fila de admissão só aceita path=cas", ex.getMessage());
        assertNull(proxima(), "nada foi publicado: o braço naive nunca reserva pelo CAS do worker");
        assertTrue(fila.consultar(usuario, "chave-naive").isEmpty());
    }

    @Test
    void pedidoInvalidoLancaIllegalArgumentESemTocarNoRedis() {
        assertThrows(IllegalArgumentException.class, () -> fila.enfileirar(pedido(usuario, evento, " ")));
        assertThrows(IllegalArgumentException.class, () -> fila.enfileirar(pedido(usuario, evento, "x".repeat(256))));
        assertThrows(IllegalArgumentException.class, () -> fila.enfileirar(new AdmissionRequest(evento, usuario, "k", "xyz")));
        assertTrue(fila.consultar(usuario, "k").isEmpty());
    }

    @Test
    void brokerBloqueadoNaoPublicaEDesfazAEntrada() {
        Connection conexao = Mockito.mock(Connection.class);
        contexto.publishEvent(new ConnectionBlockedEvent(conexao, "memory"));

        assertThrows(NaoPublicadaException.class, () -> fila.enfileirar(pedido(usuario, evento, "chave-1")));

        assertNull(proxima(), "nada foi publicado");
        assertTrue(fila.consultar(usuario, "chave-1").isEmpty(), "a entrada foi desfeita: o chamador segue síncrono");
        assertEquals(0, redis.opsForZSet().zCard(AdmissionStore.chaveEsperaUsuario(usuario)));

        contexto.publishEvent(new ConnectionUnblockedEvent(conexao));
        assertTrue(fila.enfileirar(pedido(usuario, evento, "chave-1")).publicada(), "desbloqueado, publica de novo");
    }

    private static java.util.Set<String> campos(JsonNode no) {
        var out = new java.util.HashSet<String>();
        no.fieldNames().forEachRemaining(out::add);
        return out;
    }
}
