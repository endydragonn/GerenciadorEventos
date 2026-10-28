package com.gerenciador.eventos.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventStore;
import com.gerenciador.eventos.shared.events.NotificationEvents;
import com.gerenciador.eventos.shared.events.NovoEvento;

/**
 * Retenção do dado pessoal do stream {@code identity.notification} (issue #32; decisão do usuário: validade do código +
 * 30 s). O job é chamado na mão ({@code run-tests.sh} estica o timer). Os eventos são appendados direto, com o
 * {@code expiresAt} que cada caso precisa; só some o que venceu há mais de 30 s E já saiu do outbox.
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class NotificationEventRetentionTest {

    @Autowired private NotificationEventRetentionService retencao;
    @Autowired private EventStore eventStore;
    @Autowired private DatabaseConnection db;
    @Autowired private ObjectMapper json;

    @BeforeEach
    void limpar() throws Exception {
        EventsTestSupport.limpar(db);
    }

    /** Appenda um evento (já "publicado": sai do outbox, salvo se {@code noOutbox}) e devolve o streamId. */
    private String append(String streamType, Instant expiresAt, boolean noOutbox) throws Exception {
        String streamId = UUID.randomUUID().toString();
        var payload = json.createObjectNode().put(NotificationEvents.EMAIL, "ret-" + streamId + "@example.com")
                .put(NotificationEvents.USER_NAME, "Ret").put(NotificationEvents.CODE, "123456");
        if (expiresAt != null) {
            payload.put(NotificationEvents.EXPIRES_AT, expiresAt.toString());
        }
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            eventStore.append(c, NovoEvento.of(streamType, streamId, 1, "VerificationCodeRequested", payload, streamId))
                    .orElseThrow();
            if (!noOutbox) {
                try (var st = c.createStatement()) {
                    st.execute("DELETE FROM identity.event_outbox WHERE global_position = "
                            + "(SELECT global_position FROM identity.event_store WHERE stream_id = '" + streamId + "')");
                }
            }
            c.commit();
        }
        return streamId;
    }

    private boolean existe(String streamId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM identity.event_store WHERE stream_id = ?")) {
            ps.setString(1, streamId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Test
    void apagaOVencidoHaMaisDe30sQueJaSaiuDoOutbox() throws Exception {
        String vencido = append(NotificationEvents.STREAM_TYPE, Instant.now().minusSeconds(31), false);

        assertEquals(1, retencao.purgeExpired());

        assertFalse(existe(vencido), "código vencido há 31 s (> 30 s de carência) e já publicado: some");
    }

    @Test
    void naoApagaOQueAindaEstaNaCarenciaNemOQueAindaVale() throws Exception {
        String naCarencia = append(NotificationEvents.STREAM_TYPE, Instant.now().minusSeconds(10), false);
        String valido = append(NotificationEvents.STREAM_TYPE, Instant.now().plusSeconds(120), false);

        assertEquals(0, retencao.purgeExpired());

        assertTrue(existe(naCarencia), "venceu há 10 s: ainda dentro dos 30 s de carência");
        assertTrue(existe(valido), "o código ainda vale");
    }

    @Test
    void naoApagaVencidoQueAindaEstaNoOutboxNemOutroStream() throws Exception {
        String naoPublicado = append(NotificationEvents.STREAM_TYPE, Instant.now().minusSeconds(600), true);
        String outroStream = append("catalog.event", Instant.now().minusSeconds(600), false);

        assertEquals(0, retencao.purgeExpired());

        assertTrue(existe(naoPublicado), "ainda no event_outbox: o relay precisa dele");
        assertTrue(existe(outroStream), "só o stream identity.notification é apagado");
    }

    @Test
    void expiresAtMalformadoNaoTravaOJobEContaPeloOccurredAt() throws Exception {
        String lixo = append(NotificationEvents.STREAM_TYPE, null, false);
        String valido = append(NotificationEvents.STREAM_TYPE, Instant.now().minusSeconds(31), false);
        try (Connection c = db.getConnection(); var st = c.createStatement()) {
            st.execute("UPDATE identity.event_store SET occurred_at = now() - interval '2 minutes', "
                    + "payload = payload || '{\"expiresAt\": \"isto não é data\"}'::jsonb WHERE stream_id = '" + lixo + "'");
        }

        assertEquals(2, retencao.purgeExpired(), "o malformado não derruba o job e o válido vencido também some");
        assertFalse(existe(lixo));
        assertFalse(existe(valido));
    }

    @Test
    void eventoSemExpiresAtContaComoVencidoNoOccurredAt() throws Exception {
        String antigo = append(NotificationEvents.STREAM_TYPE, null, false);
        try (Connection c = db.getConnection(); var st = c.createStatement()) {
            st.execute("UPDATE identity.event_store SET occurred_at = now() - interval '2 minutes' WHERE stream_id = '" + antigo + "'");
        }

        assertEquals(1, retencao.purgeExpired());
        assertFalse(existe(antigo));
    }
}
