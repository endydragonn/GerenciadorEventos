package com.gerenciador.eventos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.gerenciador.eventos.catalog.api.Event;
import com.gerenciador.eventos.reservation.api.EventWallet;
import com.gerenciador.eventos.identity.api.User;
import com.gerenciador.eventos.reservation.EventAvailabilityRepository;
import com.gerenciador.eventos.reservation.EventCapRepository;
import com.gerenciador.eventos.reservation.EventWalletRepository;
import com.gerenciador.eventos.reservation.IdempotencyKeyRepository;
import com.gerenciador.eventos.catalog.EventService;
import com.gerenciador.eventos.reservation.EventWalletService;
import com.gerenciador.eventos.reservation.MyWalletService;
import com.gerenciador.eventos.identity.UserService;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Trava o caminho de leitura INGÊNUO ({@code reservation.readpath=naive}): lê a disponibilidade calculada sobre a
 * projeção assíncrona {@code event_availability} e insere em {@code walletevent} SEM CAS — nunca toca
 * {@code eventcap}. É o braço de controle do experimento de concorrência e OVERSELLA de propósito quando a projeção
 * está defasada.
 *
 * <p>Relay e consumer ficam DESLIGADOS neste contexto (os flags default): a projeção nunca avança sozinha, que é o
 * "consumer parado" determinístico da prova de overselling. Para simular o consumer já ter aplicado as reservas, os
 * testes gravam o {@code ativos} direto na projeção ({@link #projetar}). O caminho real (append -> relay -> tópico ->
 * consumer) é o de {@code EventAvailabilityProjectionTest}.</p>
 */
@SpringBootTest(properties = "reservation.readpath=naive")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class EventWalletNaivePathTest {

    @Autowired private UserService userService;
    @Autowired private MyWalletService myWalletService;
    @Autowired private EventService eventService;
    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventCapRepository eventCapRepository;
    @Autowired private EventWalletRepository eventWalletRepository;
    @Autowired private EventAvailabilityRepository eventAvailabilityRepository;
    @Autowired private DatabaseConnection databaseConnection;
    @MockitoSpyBean private IdempotencyKeyRepository idempotencyKeyRepository;

    /**
     * Overselling demonstrado (prova do braço de controle da #9): com o consumer parado, a projeção não vê as
     * reservas; duas requisições que leem o mesmo {@code available > 0} defasado passam ambas e {@code eventcap} não
     * se mexe. Mesmo assim cada reserva appenda {@code SlotReserved} (a projeção vê os dois braços quando o
     * consumer volta).
     */
    @Test
    public void doisAddLinkNaMesmaJanelaOversellamSemTocarOCas() throws Exception {
        User creator = novoUsuario("naive-oversell-creator@example.com", "naive-fone-01");
        Event e = novoEvento(creator.getId(), "Naive Oversell Cap1", 1);
        User u1 = novoUsuario("naive-oversell-u1@example.com", "naive-fone-02");
        User u2 = novoUsuario("naive-oversell-u2@example.com", "naive-fone-03");

        EventWallet r1 = eventWalletService.addLink(new EventWallet(u1.getId(), e.getEvent_id()));
        EventWallet r2 = eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id()));
        assertEquals("pending", r1.getStatus());
        assertEquals("pending", r2.getStatus(), "2ª reserva na mesma janela de lag também passa");

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

    /**
     * A disponibilidade é {@code eventcap LEFT JOIN} a projeção: um evento recém-criado, sem nenhuma linha na
     * projeção, já tem disponibilidade = capacidade (não existe mais o "ainda não calculada" do refresh por timer),
     * e a reserva passa.
     */
    @Test
    public void eventoNovoSemLinhaNaProjecaoJaTemDisponibilidadeIgualACapacidade() {
        User creator = novoUsuario("naive-null-creator@example.com", "naive-fone-04");
        Event e = novoEvento(creator.getId(), "Naive Disp Null", 3);
        User u = novoUsuario("naive-null-u@example.com", "naive-fone-05");

        assertEquals(3, eventAvailabilityRepository.findAvailable(e.getEvent_id()));
        assertEquals("pending", eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id())).getStatus());
    }

    /** Evento ilimitado ({@code capacity = -1}) tem disponibilidade -1 mesmo com {@code ativos} na projeção. */
    @Test
    public void eventoIlimitadoTemDisponibilidadeMenosUm() throws Exception {
        User creator = novoUsuario("naive-ilim-creator@example.com", "naive-fone-16");
        Event e = novoEvento(creator.getId(), "Naive Ilimitado", -1);
        projetar(e.getEvent_id(), 50);
        assertEquals(-1, eventAvailabilityRepository.findAvailable(e.getEvent_id()));
    }

    /** {@code ativos} negativo (um {@code Cancelled} de linha anterior ao ES) não faz a disponibilidade passar da capacidade. */
    @Test
    public void ativosNegativoNaoFazADisponibilidadePassarDaCapacidade() throws Exception {
        User creator = novoUsuario("naive-neg-creator@example.com", "naive-fone-18");
        Event e = novoEvento(creator.getId(), "Naive Ativos Negativo", 4);
        projetar(e.getEvent_id(), -1);
        assertEquals(4, eventAvailabilityRepository.findAvailable(e.getEvent_id()), "no máximo a capacidade");
    }

    /** A leitura nunca olha {@code eventcap.quant} (viraria o CAS): só {@code capacity - ativos}. */
    @Test
    public void disponibilidadeNaoLeEventcapQuant() throws Exception {
        User creator = novoUsuario("naive-quant-creator@example.com", "naive-fone-17");
        Event e = novoEvento(creator.getId(), "Naive Nao Le Quant", 4);
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE eventcap SET quant = 0 WHERE event_id = ?")) {
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
        User creator = novoUsuario("naive-dup-creator@example.com", "naive-fone-06");
        Event e = novoEvento(creator.getId(), "Naive Duplicada", 5);
        User u = novoUsuario("naive-dup-u@example.com", "naive-fone-07");

        eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id())));
        assertTrue(ex.getMessage().toLowerCase().contains("já existente")
                        || ex.getMessage().toLowerCase().contains("ja existente"),
                "veio: " + ex.getMessage());
    }

    /** {@code available == 0} na disponibilidade defasada => "Evento lotado". */
    @Test
    public void disponibilidadeZeroNaProjecaoBloqueiaAReserva() throws Exception {
        User creator = novoUsuario("naive-lotado-creator@example.com", "naive-fone-08");
        Event e = novoEvento(creator.getId(), "Naive Lotado", 1);
        User u1 = novoUsuario("naive-lotado-u1@example.com", "naive-fone-09");
        User u2 = novoUsuario("naive-lotado-u2@example.com", "naive-fone-10");

        eventWalletService.addLink(new EventWallet(u1.getId(), e.getEvent_id()));
        projetar(e.getEvent_id(), 1); // o consumer aplicou a reserva: available = 0

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id())));
        assertTrue(ex.getMessage().toLowerCase().contains("lotado"), "veio: " + ex.getMessage());
    }

    /** Idempotência vale também no braço ingênuo: a repetição devolve a 1ª reserva, sem 2ª linha. */
    @Test
    public void repeticaoComAMesmaChaveNaoGravaDeNovoNoCaminhoIngenuo() throws Exception {
        User creator = novoUsuario("naive-idem-creator@example.com", "naive-fone-11");
        Event e = novoEvento(creator.getId(), "Naive Idempotente", 1);
        User u = novoUsuario("naive-idem-u@example.com", "naive-fone-12");

        EventWallet primeira = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "naive-chave-1");
        projetar(e.getEvent_id(), 1); // available = 0: sem a chave a repetição seria "lotado"
        EventWallet repetida = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "naive-chave-1");

        assertEquals("pending", repetida.getStatus());
        assertEquals(primeira.getExpiresAt(), repetida.getExpiresAt());
        assertEquals(1, contarWalletevent(e.getEvent_id(), "pending"));
        assertEquals(1, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(), "naive não toca eventcap");
    }

    /**
     * Rejeição do naive com chave GERADA pelo servidor não consulta {@code idempotency_key} (ela nunca
     * existe): senão o naive pagaria um SELECT a mais que o cas por rejeição. Com chave do CLIENTE a
     * consulta acontece (pode ser a repetição de uma reserva que já vale).
     */
    @Test
    public void rejeicaoComChaveGeradaNaoConsultaAChave() throws Exception {
        User creator = novoUsuario("naive-nolookup-creator@example.com", "naive-fone-13");
        Event e = novoEvento(creator.getId(), "Naive Sem Lookup", 1);
        User u1 = novoUsuario("naive-nolookup-u1@example.com", "naive-fone-14");
        User u2 = novoUsuario("naive-nolookup-u2@example.com", "naive-fone-15");
        eventWalletService.addLink(new EventWallet(u1.getId(), e.getEvent_id()));
        projetar(e.getEvent_id(), 1); // available = 0
        clearInvocations(idempotencyKeyRepository);

        assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id())));
        verify(idempotencyKeyRepository, never()).find(anyString(), anyLong());

        assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id()), "naive-chave-cliente"));
        verify(idempotencyKeyRepository, times(1)).find(anyString(), anyLong());
    }

    // ===================== helpers =====================

    private User novoUsuario(String email, String fone) {
        User u = new User();
        u.setName("Naive " + email);
        u.setEmail(email);
        u.setFone(fone);
        u = userService.createUser(u);
        myWalletService.ensureExists(u.getId());
        return u;
    }

    private Event novoEvento(Long criadorId, String nome, int capacidade) {
        Event e = new Event();
        e.setCreator_id(criadorId);
        e.setEvent_name(nome);
        e.setIs_EAD(true);
        e.setEvent_date(LocalDateTime.now().plusDays(10));
        e.setBuy_time_limit(null);
        e.setLot_quantity(capacidade);
        e.setDescription("desc");
        return eventService.createEvent(e);
    }

    /** Estado da projeção como se o consumer já tivesse aplicado {@code ativos} reservas do evento. */
    private void projetar(Long eventId, int ativos) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO event_availability (event_id, ativos, last_occurred_at) VALUES (?, ?, now()) "
                 + "ON CONFLICT (event_id) DO UPDATE SET ativos = EXCLUDED.ativos")) {
            ps.setLong(1, eventId);
            ps.setInt(2, ativos);
            ps.executeUpdate();
        }
    }

    private int contarSlotReserved(Long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM event_store WHERE stream_type = 'reservation.wallet' "
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
                 "SELECT count(*) FROM walletevent WHERE event_id = ? AND status = ?")) {
            ps.setLong(1, eventId);
            ps.setString(2, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
