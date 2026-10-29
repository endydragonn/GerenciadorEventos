package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.reservation.EventWalletRepository;
import com.gerenciador.eventos.reservation.EventWalletService;
import com.gerenciador.eventos.reservation.ReservationEvents;
import com.gerenciador.eventos.reservation.ReservationExpiryService;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventStore;
import com.gerenciador.eventos.shared.events.NovoEvento;

/**
 * Lado wallet de {@code reservation} em event sourcing (issue #29): o stream {@code reservation.wallet}
 * ({@code SlotReserved}/{@code ReservationConfirmed}/{@code ReservationCancelled}/{@code ReservationExpired}) sai
 * das transições de {@code walletevent}, com a versão vinda da própria guarda e o append só quando a guarda afeta 1
 * linha, na mesma transação. Sem relay nem consumer: aqui só se confere o log e a atomicidade.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
@TestPropertySource(properties = "reservation.expiry.scan.ms=3600000")
public class ReservationEventSourcingTest extends ReservationTestSupport {
    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventWalletRepository eventWalletRepository;
    @Autowired private ReservationExpiryService reservationExpiryService;
    @Autowired private EventStore eventStore;
    @Autowired private ObjectMapper json;
    @Autowired private DatabaseConnection databaseConnection;

    private record Ev(int version, String tipo, JsonNode payload, JsonNode metadata, String chave) {
    }

    @Test
    public void cicloCompletoGeraOsQuatroEventosNoMesmoStreamComAVersaoDaGuarda() throws Exception {
        Usuario creator = novoUsuario("es-ciclo-creator@example.com", "es-fone-01");
        Evento e = novoEvento(creator.getId(), "ES Ciclo", 5);
        Usuario u = novoUsuario("es-ciclo-u@example.com", "es-fone-02");
        Long uid = u.getId();
        Long eid = e.getEvent_id();

        EventWallet r = eventWalletService.addLink(new EventWallet(uid, eid), "es-chave-ciclo");
        eventWalletService.confirm(uid, eid);
        eventWalletService.removeLink(uid, eid);
        eventWalletService.addLink(new EventWallet(uid, eid), "es-chave-ciclo-2"); // revive: o stream continua
        forcarExpiresAtNoPassado(uid, eid);
        reservationExpiryService.expireStaleReservations();

        List<Ev> log = ler(uid, eid);
        assertEquals(List.of("SlotReserved", "ReservationConfirmed", "ReservationCancelled", "SlotReserved",
                "ReservationExpired"), log.stream().map(Ev::tipo).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), log.stream().map(Ev::version).toList(), "versões contíguas, do mesmo stream");
        assertEquals(5, versaoDaLinha(uid, eid), "walletevent.version = versão do último evento");
        assertEquals("expired", eventWalletRepository.currentStatus(uid, eid));
        assertEquals(5, capacidade(eid).getQuant(), "todas as vagas devolvidas");

        // SlotReserved leva os valores calculados (o expiresAt devolvido ao cliente) e a chave de idempotência
        Ev slot = log.get(0);
        assertEquals(uid, slot.payload().get("userId").asLong());
        assertEquals(eid, slot.payload().get("eventId").asLong());
        assertEquals(r.getExpiresAt(), LocalDateTime.parse(slot.payload().get("expiresAt").asText()));
        assertEquals("es-chave-ciclo", slot.metadata().get("idempotencyKey").asText());
        // partição = event_id do domínio, para todos os eventos do stream
        assertTrue(log.stream().allMatch(x -> x.chave().equals(String.valueOf(eid))));
    }

    /** removeLink de reserva PENDING (nunca confirmada): vaga devolvida, status cancelled e ReservationCancelled appendado. */
    @Test
    public void cancelarReservaPendingDevolveAVagaMarcaCancelledEAppendaReservationCancelled() throws Exception {
        Usuario creator = novoUsuario("es-pcancel-creator@example.com", "es-fone-20");
        Evento e = novoEvento(creator.getId(), "ES Cancel Pending", 3);
        Usuario u = novoUsuario("es-pcancel-u@example.com", "es-fone-21");
        Long uid = u.getId();
        Long eid = e.getEvent_id();
        eventWalletService.addLink(new EventWallet(uid, eid));
        assertEquals("pending", eventWalletRepository.currentStatus(uid, eid));
        assertEquals(2, capacidade(eid).getQuant());

        eventWalletService.removeLink(uid, eid);

        assertEquals("cancelled", eventWalletRepository.currentStatus(uid, eid));
        assertEquals(3, capacidade(eid).getQuant(), "a vaga da pending volta");
        List<Ev> log = ler(uid, eid);
        assertEquals(List.of("SlotReserved", "ReservationCancelled"), log.stream().map(Ev::tipo).toList());
        assertEquals(2, log.get(1).version());
        assertEquals(2, versaoDaLinha(uid, eid), "walletevent.version = versão do ReservationCancelled");
    }

    @Test
    public void payloadNaoTemDadoPessoal() throws Exception {
        Usuario creator = novoUsuario("es-pii-creator@example.com", "es-fone-03");
        Evento e = novoEvento(creator.getId(), "ES Sem PII", 5);
        Usuario u = novoUsuario("es-pii-u@example.com", "es-fone-04");
        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        eventWalletService.confirm(u.getId(), e.getEvent_id());
        eventWalletService.removeLink(u.getId(), e.getEvent_id());

        for (Ev ev : ler(u.getId(), e.getEvent_id())) {
            List<String> campos = new ArrayList<>();
            ev.payload().fieldNames().forEachRemaining(campos::add);
            assertTrue(List.of("userId", "eventId", "expiresAt", "holdId").containsAll(campos),
                    "payload só com ids e valores calculados, veio: " + campos);
            assertTrue(!ev.payload().toString().contains("@") && !ev.payload().toString().contains("es-fone"),
                    "nada de e-mail/telefone no payload");
        }
    }

    @Test
    public void transicaoQueAGuardaNaoAceitaNaoGeraEvento() throws Exception {
        Usuario creator = novoUsuario("es-noop-creator@example.com", "es-fone-05");
        Evento e = novoEvento(creator.getId(), "ES NoOp", 1);
        Usuario u1 = novoUsuario("es-noop-u1@example.com", "es-fone-06");
        Usuario u2 = novoUsuario("es-noop-u2@example.com", "es-fone-07");
        Long eid = e.getEvent_id();

        eventWalletService.addLink(new EventWallet(u1.getId(), eid));
        // lotado (CAS): nada gravado, nem evento
        assertThrows(IllegalArgumentException.class, () -> eventWalletService.addLink(new EventWallet(u2.getId(), eid)));
        assertEquals(0, ler(u2.getId(), eid).size(), "reserva rejeitada não deixa evento");
        // confirmar o que não está pending / cancelar o que não está ativo / expirar o que foi confirmado
        assertThrows(IllegalArgumentException.class, () -> eventWalletService.confirm(u2.getId(), eid));
        eventWalletService.removeLink(u2.getId(), eid);
        eventWalletService.confirm(u1.getId(), eid);
        forcarExpiresAtNoPassado(u1.getId(), eid);
        reservationExpiryService.expireStaleReservations();
        eventWalletService.removeLink(u1.getId(), eid);
        eventWalletService.removeLink(u1.getId(), eid); // idempotente: a 2ª não é transição

        assertEquals(0, ler(u2.getId(), eid).size());
        assertEquals(List.of("SlotReserved", "ReservationConfirmed", "ReservationCancelled"),
                ler(u1.getId(), eid).stream().map(Ev::tipo).toList());
    }

    /** O evento só vale com o commit: conflito de versão no append desfaz a reserva inteira (walletevent e evento) e compensa o hold (#47). */
    @Test
    public void conflitoNoAppendDaReservaFazRollbackDeTudoEDizVinculoJaExistente() throws Exception {
        Usuario creator = novoUsuario("es-conf1-creator@example.com", "es-fone-08");
        Evento e = novoEvento(creator.getId(), "ES Conflito Reserva", 3);
        Usuario u = novoUsuario("es-conf1-u@example.com", "es-fone-09");
        Long uid = u.getId();
        Long eid = e.getEvent_id();
        appendIntruso(uid, eid, 1);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(uid, eid), "es-chave-conf1"));
        assertTrue(ex.getMessage().contains("Vínculo já existente"), ex.getMessage());
        assertEquals(3, capacidade(eid).getQuant(), "a vaga voltou com o rollback");
        assertEquals(null, eventWalletRepository.currentStatus(uid, eid), "walletevent não foi gravado");
        assertEquals("compensated", statusDaSaga("es-chave-conf1", uid), "a saga foi compensada (a vaga voltou pelo release, a linha da chave fica como log)");
        InvariantesDaSaga.verificar(databaseConnection, eid);
        assertEquals(1, ler(uid, eid).size(), "só o evento intruso (a reserva não appendou)");
    }

    @Test
    public void conflitoNoAppendDeConfirmFazRollbackEFicaPending() throws Exception {
        Usuario creator = novoUsuario("es-conf2-creator@example.com", "es-fone-10");
        Evento e = novoEvento(creator.getId(), "ES Conflito Confirm", 3);
        Usuario u = novoUsuario("es-conf2-u@example.com", "es-fone-11");
        Long uid = u.getId();
        Long eid = e.getEvent_id();
        eventWalletService.addLink(new EventWallet(uid, eid));
        appendIntruso(uid, eid, 2);

        assertThrows(IllegalStateException.class, () -> eventWalletService.confirm(uid, eid));
        assertEquals("pending", eventWalletRepository.currentStatus(uid, eid), "a guarda foi desfeita com o rollback");
        assertEquals(1, versaoDaLinha(uid, eid));
    }

    @Test
    public void conflitoNoAppendDeCancelamentoFazRollbackEPreservaAVaga() throws Exception {
        Usuario creator = novoUsuario("es-conf3-creator@example.com", "es-fone-12");
        Evento e = novoEvento(creator.getId(), "ES Conflito Cancel", 3);
        Usuario u = novoUsuario("es-conf3-u@example.com", "es-fone-13");
        Long uid = u.getId();
        Long eid = e.getEvent_id();
        eventWalletService.addLink(new EventWallet(uid, eid));
        appendIntruso(uid, eid, 2);

        assertThrows(RuntimeException.class, () -> eventWalletService.removeLink(uid, eid));
        assertEquals("pending", eventWalletRepository.currentStatus(uid, eid));
        assertEquals(2, capacidade(eid).getQuant(), "a devolução da vaga foi desfeita junto");
    }

    @Test
    public void conflitoNoAppendDeExpiracaoFazRollbackEOJobSegueComAsOutras() throws Exception {
        Usuario creator = novoUsuario("es-conf4-creator@example.com", "es-fone-14");
        Evento e = novoEvento(creator.getId(), "ES Conflito Expira", 3);
        Usuario ruim = novoUsuario("es-conf4-u1@example.com", "es-fone-15");
        Usuario boa = novoUsuario("es-conf4-u2@example.com", "es-fone-16");
        Long eid = e.getEvent_id();
        eventWalletService.addLink(new EventWallet(ruim.getId(), eid));
        eventWalletService.addLink(new EventWallet(boa.getId(), eid));
        appendIntruso(ruim.getId(), eid, 2);
        forcarExpiresAtNoPassado(ruim.getId(), eid);
        forcarExpiresAtNoPassado(boa.getId(), eid);

        reservationExpiryService.expireStaleReservations();

        assertEquals("pending", eventWalletRepository.currentStatus(ruim.getId(), eid), "conflito: rollback, segue pending");
        assertEquals("expired", eventWalletRepository.currentStatus(boa.getId(), eid), "o job não parou na que falhou");
        assertEquals(List.of("SlotReserved", "ReservationExpired"),
                ler(boa.getId(), eid).stream().map(Ev::tipo).toList());
        assertEquals(2, capacidade(eid).getQuant(), "só a vaga da expirada voltou");
    }

    // ===================== helpers =====================

    private List<Ev> ler(Long userId, Long eventId) throws Exception {
        String sql = "SELECT version, event_type, payload::text, metadata::text, partition_key FROM reservation.event_store "
                + "WHERE stream_type = ? AND stream_id = ? ORDER BY version";
        List<Ev> out = new ArrayList<>();
        try (Connection conn = databaseConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ReservationEvents.STREAM_TYPE);
            ps.setString(2, ReservationEvents.streamId(userId, eventId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Ev(rs.getInt(1), rs.getString(2), json.readTree(rs.getString(3)),
                            json.readTree(rs.getString(4)), rs.getString(5)));
                }
            }
        }
        return out;
    }

    /** Outra transação "gravou" a versão antes: simula a corrida que o conflito otimista detecta. */
    private void appendIntruso(Long userId, Long eventId, int version) throws Exception {
        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            eventStore.append(conn, NovoEvento.of(ReservationEvents.STREAM_TYPE,
                    ReservationEvents.streamId(userId, eventId), version, "Intruso",
                    json.createObjectNode(), String.valueOf(eventId))).orElseThrow();
            conn.commit();
        }
    }

    private int versaoDaLinha(Long userId, Long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT version FROM reservation.walletevent WHERE user_id = ? AND event_id = ?")) {
            ps.setLong(1, userId);
            ps.setLong(2, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private String statusDaSaga(String chave, Long userId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT saga_status FROM reservation.idempotency_key WHERE idempotency_key = ? AND user_id = ?")) {
            ps.setString(1, chave);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void forcarExpiresAtNoPassado(Long userId, Long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE reservation.walletevent SET expires_at = now() - interval '1 minute' WHERE user_id = ? AND event_id = ? "
                 + "AND status = 'pending'")) {
            ps.setLong(1, userId);
            ps.setLong(2, eventId);
            ps.executeUpdate();
        }
    }

}
