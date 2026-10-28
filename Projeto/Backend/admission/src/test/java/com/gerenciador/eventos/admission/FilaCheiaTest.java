package com.gerenciador.eventos.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.gerenciador.eventos.admission.FilaDeAdmissao.FilaCheiaException;
import com.gerenciador.eventos.admission.FilaDeAdmissao.NaoPublicadaException;
import com.gerenciador.eventos.admissionclient.api.AdmissionRequest;

/**
 * Os dois fracassos de publicação (issue #42), com RabbitMQ real: fila cheia ({@code x-max-length} 2 + {@code reject-publish} = nack do
 * broker) vira {@link FilaCheiaException} (429), desfaz a entrada e a fila continua com as 2 mensagens; mensagem SEM ROTA (a fila foi
 * apagada, o exchange {@code mandatory} devolve a mensagem) vira {@link NaoPublicadaException} e também desfaz — nunca um {@code queued}.
 * A ordem importa: o teste sem rota apaga a fila e vem por último.
 */
@SpringBootTest(properties = {"internal.api.token=token-de-teste", "admission.amqp.prefix=${admission.test.prefix}cheia-",
        "admission.queue.max-length=2"})
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class FilaCheiaTest {

    private static final String FILA = RabbitDeTeste.prefixo("cheia-") + "admission.requests";

    @Autowired private FilaDeAdmissao fila;
    @Autowired private AdmissionStore store;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private RabbitAdmin admin;
    @Autowired private StringRedisTemplate redis;
    private final long evento = RedisDeTeste.eventoNovo();
    private final long usuario = RedisDeTeste.usuarioNovo();

    @BeforeEach
    void preparar() {
        admin.initialize();
        admin.purgeQueue(FILA, true);
    }

    private AdmissionRequest pedido(long user, String chave) {
        return new AdmissionRequest(evento, user, chave, "cas");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void filaCheiaRecusaComNackDoBrokerDesfazEMantemAsMensagensDaFila() {
        fila.enfileirar(pedido(usuario, "k1"));
        fila.enfileirar(pedido(usuario, "k2"));

        FilaCheiaException ex = assertThrows(FilaCheiaException.class, () -> fila.enfileirar(pedido(usuario, "k3")));

        assertEquals("Fila de admissão cheia, tente mais tarde", ex.getMessage());
        assertNull(store.resultado(usuario, "k3"), "desfeito: nenhum queued fantasma");
        assertEquals(2, redis.opsForZSet().zCard(AdmissionStore.chaveEsperaUsuario(usuario)), "só k1 e k2 esperam");
        assertEquals(2, redis.opsForZSet().zCard(AdmissionStore.chaveEspera(evento)));
        assertNotNull(rabbit.receive(FILA, 2_000));
        assertNotNull(rabbit.receive(FILA, 2_000));
        assertNull(rabbit.receive(FILA, 500), "a fila tinha só as 2 primeiras");
        assertTrue(fila.enfileirar(pedido(usuario + 1, "k3")).publicada(), "com espaço na fila, publica");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void mensagemSemRotaNaoViraQueuedNemAdmitida() {
        admin.deleteQueue(FILA); // o exchange continua e a binding sumiu com a fila: nenhum destino

        assertThrows(NaoPublicadaException.class, () -> fila.enfileirar(pedido(usuario, "k-sem-rota")));

        assertNull(store.resultado(usuario, "k-sem-rota"), "mensagem devolvida (returned) = não publicada: nada de queued");
        assertEquals(0, redis.opsForZSet().zCard(AdmissionStore.chaveEsperaUsuario(usuario)));
        assertEquals(0, redis.opsForZSet().zCard(AdmissionStore.chaveEspera(evento)));
    }
}
