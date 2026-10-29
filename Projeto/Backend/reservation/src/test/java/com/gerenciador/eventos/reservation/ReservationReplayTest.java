package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.reservation.EventAvailabilityProjection;
import com.gerenciador.eventos.reservation.EventWalletRepository;
import com.gerenciador.eventos.reservation.EventWalletService;
import com.gerenciador.eventos.reservation.ReservationEvents;
import com.gerenciador.eventos.reservation.ReservationExpiryService;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.shared.events.EventEnvelope;
import com.gerenciador.eventos.shared.events.EventReplay;
import com.gerenciador.eventos.shared.events.EventStore;
import com.gerenciador.eventos.shared.events.IdempotentConsumer;
import com.gerenciador.eventos.shared.events.Projection;

/**
 * Teste de replay do stream {@code reservation.wallet} (issue #29): vários ciclos de reserva / confirma / cancela /
 * expira, de vários usuários em vários eventos, pelos services de verdade. Depois reconstrói NA SOMBRA, só a partir do
 * {@code event_store}, o {@code walletevent} (a projeção inline) e a projeção de {@code ativos}, e compara com as
 * vivas nas colunas comparáveis (as de timestamp do banco, como {@code applied_at}, ficam de fora).
 */
@SpringBootTest(properties = "events.consumer.enabled=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
@TestPropertySource(properties = "reservation.expiry.scan.ms=3600000")
public class ReservationReplayTest extends ReservationTestSupport {

    private static final String SOMBRA = "replay_t29_wallet";
    static final Map<String, List<String>> COMPARAVEIS = Map.of(
            "walletevent", List.of("user_id", "event_id", "status", "expires_at", "version", "hold_id"),
            "event_availability", List.of("event_id", "ativos", "last_occurred_at"));

    /**
     * O que o replay precisa reconstruir: a projeção de {@code ativos} (a MESMA do consumer) e o {@code walletevent}
     * (projeção inline, que na viva é gravada pelas guardas e na sombra sai só do log).
     */
    static class WalletEAtivos implements Projection {
        private final EventAvailabilityProjection ativos = new EventAvailabilityProjection();

        @Override
        public String name() {
            return "reservation.replay";
        }

        @Override
        public List<String> tables() {
            return List.of("walletevent", "event_availability");
        }

        @Override
        public void apply(Connection conn, EventEnvelope e, String schema) throws SQLException {
            ativos.apply(conn, e, schema);
            String sql = switch (e.eventType()) {
                case ReservationEvents.SLOT_RESERVED -> "INSERT INTO " + schema + ".walletevent "
                        + "(user_id, event_id, status, expires_at, version, hold_id) VALUES (?, ?, 'pending', ?, ?, ?) "
                        + "ON CONFLICT (user_id, event_id) DO UPDATE SET status = 'pending', "
                        + "expires_at = EXCLUDED.expires_at, version = EXCLUDED.version, hold_id = EXCLUDED.hold_id";
                case ReservationEvents.CONFIRMED -> transicao(schema, "confirmed");
                case ReservationEvents.CANCELLED -> transicao(schema, "cancelled");
                case ReservationEvents.EXPIRED -> transicao(schema, "expired");
                default -> throw new IllegalStateException("evento desconhecido: " + e.eventType());
            };
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (ReservationEvents.SLOT_RESERVED.equals(e.eventType())) {
                    ps.setLong(1, e.payload().get("userId").asLong());
                    ps.setLong(2, e.payload().get("eventId").asLong());
                    ps.setTimestamp(3, Timestamp.valueOf(LocalDateTime.parse(e.payload().get("expiresAt").asText())));
                    ps.setInt(4, e.version());
                    ps.setObject(5, java.util.UUID.fromString(e.payload().get("holdId").asText())); // a #47 reconstrói o hold_id do SlotReserved
                } else {
                    ps.setInt(1, e.version());
                    ps.setLong(2, e.payload().get("userId").asLong());
                    ps.setLong(3, e.payload().get("eventId").asLong());
                }
                ps.executeUpdate();
            }
        }

        private static String transicao(String schema, String status) {
            return "UPDATE " + schema + ".walletevent SET status = '" + status + "', expires_at = NULL, version = ? "
                    + "WHERE user_id = ? AND event_id = ?";
        }
    }
    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventWalletRepository eventWalletRepository;
    @Autowired private ReservationExpiryService reservationExpiryService;
    @Autowired private IdempotentConsumer idempotentConsumer;
    @Autowired private EventStore eventStore;
    @Autowired private EventReplay replay;
    @Autowired private DatabaseConnection databaseConnection;

    private final Projection projecaoDoReplay = new WalletEAtivos();

    @BeforeEach
    void limpar() throws Exception {
        EventsTestSupport.limpar(databaseConnection);
        try (Connection c = databaseConnection.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE TABLE reservation.walletevent, reservation.idempotency_key, stock.hold, stock.eventcap"); // a comparação é da tabela inteira: só o que sai do log (e o stock junto, para as invariantes da saga valerem)
        }
    }

    @AfterEach
    void derrubarSombra() throws Exception {
        replay.drop(SOMBRA);
    }

    @Test
    public void reconstruirWalletEventEAtivosDoLogDaOMesmoEstadoDasProjecoesVivas() throws Exception {
        List<Evento> eventos = new ArrayList<>();
        Usuario creator = novoUsuario("rp-creator@example.com", "rp-fone-00");
        eventos.add(novoEvento(creator.getId(), "Replay Cap3", 3));
        eventos.add(novoEvento(creator.getId(), "Replay Cap2", 2));
        eventos.add(novoEvento(creator.getId(), "Replay Ilimitado", -1));
        List<Usuario> usuarios = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            usuarios.add(novoUsuario("rp-u" + i + "@example.com", "rp-fone-1" + i));
        }

        Random rnd = new Random(29);
        for (int i = 0; i < 200; i++) {
            Usuario u = usuarios.get(rnd.nextInt(usuarios.size()));
            Long eid = eventos.get(rnd.nextInt(eventos.size())).getEvent_id();
            try {
                switch (rnd.nextInt(4)) {
                    case 0 -> eventWalletService.addLink(new EventWallet(u.getId(), eid), "rp-chave-" + i);
                    case 1 -> eventWalletService.confirm(u.getId(), eid);
                    case 2 -> eventWalletService.removeLink(u.getId(), eid);
                    default -> expirar(u.getId(), eid);
                }
            } catch (IllegalArgumentException ignorada) {
                // lotado / já existe / nada a confirmar: a jogada simplesmente não vira transição
            }
            drenarDevolucoes(); // a vaga volta pelo consumer do stock (assíncrono): sem isto o roteiro bateria em "lotado" por vaga presa
        }

        List<EventEnvelope> log = lerLog();
        assertTrue(log.size() > 60, "o roteiro tem de gerar bastante evento, gerou " + log.size());
        assertTrue(log.stream().map(EventEnvelope::eventType).distinct().count() == 4, "os 4 tipos de evento");
        conferirVersoesContiguasEIgualAsLinhas(log);

        // a projeção viva de ativos: o consumer aplicaria isto (aqui síncrono, sem o broker); ordem do log
        Projection ativos = new EventAvailabilityProjection();
        for (EventEnvelope e : log) {
            assertTrue(idempotentConsumer.process(ativos, e));
        }
        conferirAtivosContraWalletevent();

        replay.rebuild(projecaoDoReplay, ReservationEvents.STREAM_TYPE, SOMBRA);

        assertEquals(List.of(), replay.divergentTables(projecaoDoReplay, SOMBRA, COMPARAVEIS),
                "walletevent e ativos reconstruídos do log == vivos, nas colunas comparáveis");
        // sem a lista de colunas o SELECT * pega applied_at (clock_timestamp) e created_at/updated_at
        // (CURRENT_TIMESTAMP), que não são determinísticos
        assertEquals(List.of("walletevent", "event_availability"), replay.divergentTables(projecaoDoReplay, SOMBRA),
                "colunas de timestamp do banco diferem entre a viva e a sombra");
        InvariantesDaSaga.verificar(databaseConnection, eventos.stream().map(Evento::getEvent_id).toArray(Long[]::new));
    }

    @Test
    public void sombraDivergenteDaVivaEDetectadaNasColunasComparaveis() throws Exception {
        Usuario creator = novoUsuario("rp-div-creator@example.com", "rp-fone-20");
        Evento e = novoEvento(creator.getId(), "Replay Divergente", 5);
        Usuario u = novoUsuario("rp-div-u@example.com", "rp-fone-21");
        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));
        for (EventEnvelope ev : lerLog()) {
            idempotentConsumer.process(new EventAvailabilityProjection(), ev);
        }
        replay.rebuild(projecaoDoReplay, ReservationEvents.STREAM_TYPE, SOMBRA);
        assertEquals(List.of(), replay.divergentTables(projecaoDoReplay, SOMBRA, COMPARAVEIS));

        try (Connection c = databaseConnection.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE reservation.event_availability SET ativos = ativos + 1");
            st.execute("UPDATE reservation.walletevent SET status = 'confirmed'");
        }
        assertEquals(List.of("walletevent", "event_availability"),
                replay.divergentTables(projecaoDoReplay, SOMBRA, COMPARAVEIS));
    }

    // ===================== helpers =====================

    /** Vence a reserva pending do par (se houver) por SQL e roda o job: só ela é expirada. */
    private void expirar(Long userId, Long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE reservation.walletevent SET expires_at = now() - interval '1 minute' "
                 + "WHERE user_id = ? AND event_id = ? AND status = 'pending'")) {
            ps.setLong(1, userId);
            ps.setLong(2, eventId);
            if (ps.executeUpdate() == 1) {
                reservationExpiryService.expireStaleReservations();
                assertEquals("expired", eventWalletRepository.currentStatus(userId, eventId));
            }
        }
    }

    /** O log inteiro do stream de reservas, stream a stream em ordem de versão (a ordem entre streams não importa). */
    private List<EventEnvelope> lerLog() throws Exception {
        List<EventEnvelope> log = new ArrayList<>();
        try (Connection conn = databaseConnection.getConnection(); Statement st = conn.createStatement()) {
            List<String> streams = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT stream_id FROM reservation.event_store WHERE stream_type = '"
                    + ReservationEvents.STREAM_TYPE + "' GROUP BY stream_id ORDER BY min(global_position)")) {
                while (rs.next()) {
                    streams.add(rs.getString(1));
                }
            }
            for (String s : streams) {
                log.addAll(eventStore.readStream(conn, ReservationEvents.STREAM_TYPE, s, 1));
            }
        }
        return log;
    }

    /** Cada stream tem as versões 1..n sem buraco e {@code walletevent.version} = a última. */
    private void conferirVersoesContiguasEIgualAsLinhas(List<EventEnvelope> log) throws Exception {
        Map<String, Integer> ultima = new java.util.HashMap<>();
        for (EventEnvelope e : log) {
            int esperada = ultima.getOrDefault(e.streamId(), 0) + 1;
            assertEquals(esperada, e.version(), "versão contígua no stream " + e.streamId());
            ultima.put(e.streamId(), e.version());
        }
        try (Connection conn = databaseConnection.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT user_id || ':' || event_id, version FROM reservation.walletevent")) {
            int linhas = 0;
            while (rs.next()) {
                linhas++;
                assertEquals(ultima.get(rs.getString(1)), rs.getInt(2), "walletevent.version do stream " + rs.getString(1));
            }
            assertEquals(ultima.size(), linhas, "uma linha de walletevent por stream");
        }
    }

    /** A projeção assíncrona concorda com a inline: ativos = reservas pending/confirmed de cada evento. */
    private void conferirAtivosContraWalletevent() throws Exception {
        String sql = "SELECT count(*) FROM (SELECT event_id, count(*) FILTER (WHERE status IN ('pending','confirmed')) AS a "
                + "FROM reservation.walletevent GROUP BY event_id) w FULL JOIN reservation.event_availability ea USING (event_id) "
                + "WHERE coalesce(w.a, 0) <> coalesce(ea.ativos, 0)";
        try (Connection conn = databaseConnection.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            assertEquals(0, rs.getInt(1), "ativos da projeção == reservas ativas do walletevent, em todo evento");
        }
    }

}
