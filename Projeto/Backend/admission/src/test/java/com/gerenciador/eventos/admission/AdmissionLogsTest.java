package com.gerenciador.eventos.admission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;

import com.gerenciador.eventos.admissionclient.api.AdmissionRequest;

/**
 * Nenhum log do admission pode conter a credencial do RabbitMQ (issue #42, regra "Segredos" do CLAUDE.md): o {@code CachingConnectionFactory}
 * do Spring AMQP registra, em INFO, {@code Created new connection: ... [delegate=amqp://<usuario>@<host>:5672/, ...]}. O serviço silencia esse
 * logger (WARN). O teste abre a conexão do publicador de verdade (publica uma mensagem) e confere a saída capturada.
 */
@SpringBootTest(properties = {"internal.api.token=token-de-teste", "admission.amqp.prefix=${admission.test.prefix}log-"})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
class AdmissionLogsTest {

    @Autowired private FilaDeAdmissao fila;
    @Autowired private RabbitAdmin admin;

    @Test
    void aConexaoAoBrokerNaoLogaUsuarioNemSenha(CapturedOutput saida) {
        long evento = RedisDeTeste.eventoNovo();
        admin.initialize(); // abre a conexão do admin (e declara a topologia)

        assertTrue(fila.enfileirar(new AdmissionRequest(evento, RedisDeTeste.usuarioNovo(), "chave-log", "cas")).publicada(),
                "a conexão com o broker foi aberta e usada");

        String log = saida.getAll();
        assertFalse(log.contains("amqp://"), "o toString da conexão traz amqp://<usuário>@<host>: não pode ir para o log");
        String usuario = System.getenv("RABBITMQ_USER");
        if (usuario != null && !usuario.isBlank()) {
            assertFalse(log.contains(usuario + "@"), "usuário do broker no log");
        }
        String senha = System.getenv("RABBITMQ_PASSWORD");
        if (senha != null && !senha.isBlank()) {
            assertFalse(log.contains(senha), "senha do broker no log");
        }
    }
}
