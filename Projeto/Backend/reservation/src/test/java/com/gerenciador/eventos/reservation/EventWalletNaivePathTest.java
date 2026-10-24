package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Trava o lado do SERVIÇO do braço INGÊNUO (feature flag {@code reservation.readpath=naive} do gateway): a reserva com
 * {@code naive = true} insere em {@code walletevent} SEM CAS e nunca toca {@code eventcap}, e a disponibilidade defasada
 * ({@code eventcap LEFT JOIN} a projeção assíncrona {@code event_availability}) é uma LEITURA SEPARADA
 * ({@link EventWalletService#disponibilidade}). A checagem "se a disponibilidade é 0, lotado" NÃO está mais dentro da
 * reserva: é a chamada anterior do gateway, e é essa ida e volta de rede que alarga a janela check-then-act. As duas
 * chamadas HTTP, o "lotado" e a prova de overselling ponta a ponta estão em {@code ReservationApiE2ETest}.
 *
 * <p>Relay e consumer ficam DESLIGADOS neste contexto (os flags default): a projeção nunca avança sozinha, que é o
 * "consumer parado" determinístico. Para simular o consumer já ter aplicado as reservas, os testes gravam o
 * {@code ativos} direto na projeção ({@link #projetar}). O caminho real (append -> relay -> tópico -> consumer) é o de
 * {@code EventAvailabilityProjectionTest}.</p>
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class EventWalletNaivePathTest extends ReservationTestSupport {

    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventWalletRepository eventWalletRepository;
    @Autowired private EventAvailabilityRepository eventAvailabilityRepository;
    @Autowired private DatabaseConnection databaseConnection;

    /**
     * Overselling no nível do banco: sem a checagem de capacidade dentro da reserva, duas reservas no evento de 1 vaga
     * passam as duas e {@code eventcap} não se mexe. Cada reserva appenda {@code SlotReserved} (a projeção vê os dois
     * braços quando o consumer volta). Quem impede (ou não) isso no braço naive é a leitura anterior do gateway.
     */
    @Test
    public void duasReservasNaiveNoEventoDeUmaVagaPassamEAsDuasESemTocarOCas() throws Exception {
        Evento e = novoEvento(null, "Naive Oversell Cap1", 1);
        Usuario u1 = novoUsuario("u1");
        Usuario u2 = novoUsuario("u2");

        EventWallet r1 = eventWalletService.addLink(new EventWallet(u1.getId(), e.getEvent_id()), null, true);
        EventWallet r2 = eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id()), null, true);
        assertEquals("pending", r1.getStatus());
        assertEquals("pending", r2.getStatus(), "o serviço não confere a disponibilidade no naive: a 2ª também passa");

        eventWalletService.confirm(u1.getId(), e.getEvent_id());
        eventWalletService.confirm(u2.getId(), e.getEvent_id());

        assertEquals(2, contarWalletevent(e.getEvent_id(), "confirmed"),
                "2 linhas confirmadas para capacity 1 — overselling");
        assertEquals(1, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(),
                "o caminho ingênuo não tocou o CAS: eventcap.quant continua 1");
        assertEquals(1, eventAvailabilityRepository.findAvailable(e.getEvent_id()),
                "consumer parado: a projeção ainda diz 1 vaga, mesmo com 2 reservas ativas");
        assertEquals(2, contarSlotReserved(e.getEvent_id()), "o braço naive também appenda SlotReserved");
    }

    /** O contrário: o mesmo cenário no braço cas é barrado pelo CAS, dentro do serviço. */
    @Test
    public void omesmoCenarioNoCasEhBarradoPeloServico() {
        Evento e = novoEvento(null, "Cas Cap1", 1);
        eventWalletService.addLink(new EventWallet(novoUsuario("a").getId(), e.getEvent_id()), null, false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                eventWalletService.addLink(new EventWallet(novoUsuario("b").getId(), e.getEvent_id()), null, false));
        assertTrue(ex.getMessage().toLowerCase().contains("lotado"), "veio: " + ex.getMessage());
        assertEquals(0, eventCapRepository.findByEventId(e.getEvent_id()).getQuant());
    }

    /**
     * A disponibilidade é {@code eventcap LEFT JOIN} a projeção: um evento recém-criado, sem nenhuma linha na
     * projeção, já tem disponibilidade = capacidade, e a reserva passa.
     */
    @Test
    public void eventoNovoSemLinhaNaProjecaoJaTemDisponibilidadeIgualACapacidade() {
        Evento e = novoEvento(null, "Naive Disp Null", 3);

        assertEquals(3, eventAvailabilityRepository.findAvailable(e.getEvent_id()));
        assertEquals(3, eventWalletService.disponibilidade(e.getEvent_id()));
        assertEquals("pending", eventWalletService.addLink(new EventWallet(novoUsuario("u").getId(), e.getEvent_id()),
                null, true).getStatus());
    }

    /** Evento sem {@code eventcap} não tem disponibilidade ({@code null}: o controller responde 404). */
    @Test
    public void eventoSemEventcapNaoTemDisponibilidade() {
        assertEquals(null, eventWalletService.disponibilidade(987_654_321L));
    }

    /** Evento ilimitado ({@code capacity = -1}) tem disponibilidade -1 mesmo com {@code ativos} na projeção. */
    @Test
    public void eventoIlimitadoTemDisponibilidadeMenosUm() throws Exception {
        Evento e = novoEvento(null, "Naive Ilimitado", -1);
        projetar(e.getEvent_id(), 50);
        assertEquals(-1, eventAvailabilityRepository.findAvailable(e.getEvent_id()));
    }

    /** {@code ativos} negativo (um {@code Cancelled} de linha anterior ao ES) não faz a disponibilidade passar da capacidade. */
    @Test
    public void ativosNegativoNaoFazADisponibilidadePassarDaCapacidade() throws Exception {
        Evento e = novoEvento(null, "Naive Ativos Negativo", 4);
        projetar(e.getEvent_id(), -1);
        assertEquals(4, eventAvailabilityRepository.findAvailable(e.getEvent_id()), "no máximo a capacidade");
    }

    /** A leitura nunca olha {@code eventcap.quant} (viraria o CAS): só {@code capacity - ativos}. */
    @Test
    public void disponibilidadeNaoLeEventcapQuant() throws Exception {
        Evento e = novoEvento(null, "Naive Nao Le Quant", 4);
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE reservation.eventcap SET quant = 0 WHERE event_id = ?")) {
            ps.setLong(1, e.getEvent_id());
            ps.executeUpdate();
        }
        projetar(e.getEvent_id(), 1);
        assertEquals(3, eventAvailabilityRepository.findAvailable(e.getEvent_id()),
                "capacity 4 - ativos 1, ignorando quant = 0");
    }

    /** Reserva duplicada do mesmo usuário é barrada antes do caminho ingênuo. */
    @Test
    public void reservaDuplicadaDoMesmoUsuarioEhRejeitada() {
        Evento e = novoEvento(null, "Naive Duplicada", 5);
        Usuario u = novoUsuario("u");

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), null, true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), null, true));
        assertTrue(ex.getMessage().toLowerCase().contains("já existente")
                        || ex.getMessage().toLowerCase().contains("ja existente"),
                "veio: " + ex.getMessage());
    }

    /** Idempotência vale também no braço ingênuo: a repetição devolve a 1ª reserva, sem 2ª linha. */
    @Test
    public void repeticaoComAMesmaChaveNaoGravaDeNovoNoCaminhoIngenuo() throws Exception {
        Evento e = novoEvento(null, "Naive Idempotente", 1);
        Usuario u = novoUsuario("u");

        EventWallet primeira = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "naive-chave-1", true);
        EventWallet repetida = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "naive-chave-1", true);

        assertEquals("pending", repetida.getStatus());
        assertEquals(primeira.getExpiresAt(), repetida.getExpiresAt());
        assertEquals(1, contarWalletevent(e.getEvent_id(), "pending"));
        assertEquals(1, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(), "naive não toca eventcap");
        assertEquals(1, contarSlotReserved(e.getEvent_id()), "a repetição não appenda de novo");
    }

    // ===================== helpers =====================

    /** Estado da projeção como se o consumer já tivesse aplicado {@code ativos} reservas do evento. */
    private void projetar(Long eventId, int ativos) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO reservation.event_availability (event_id, ativos, last_occurred_at) VALUES (?, ?, now()) "
                 + "ON CONFLICT (event_id) DO UPDATE SET ativos = EXCLUDED.ativos")) {
            ps.setLong(1, eventId);
            ps.setInt(2, ativos);
            ps.executeUpdate();
        }
    }

    private int contarSlotReserved(Long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM reservation.event_store WHERE stream_type = 'reservation.wallet' "
                 + "AND event_type = 'SlotReserved' AND partition_key = ?")) {
            ps.setString(1, String.valueOf(eventId));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int contarWalletevent(Long eventId, String status) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM reservation.walletevent WHERE event_id = ? AND status = ?")) {
            ps.setLong(1, eventId);
            ps.setString(2, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
