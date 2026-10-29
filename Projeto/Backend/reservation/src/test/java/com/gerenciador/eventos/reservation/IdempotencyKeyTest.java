package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.reservation.EventWalletService;
import com.gerenciador.eventos.reservationclient.api.EventWallet;
import com.gerenciador.eventos.reservationclient.api.IdempotencyConflictException;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Idempotência do {@code addLink} (issue #24): a repetição com a mesma {@code Idempotency-Key}
 * devolve o resultado da primeira e NÃO decrementa {@code eventcap} de novo — inclusive sob
 * concorrência (a PK da tabela {@code idempotency_key} serializa as requisições com a mesma chave).
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
public class IdempotencyKeyTest extends ReservationTestSupport {
    @Autowired private EventWalletService eventWalletService;
    @Autowired private DatabaseConnection databaseConnection;

    @Test
    public void repeticaoComAMesmaChaveNaoDecrementaDeNovo() throws Exception {
        Usuario u = novoUsuario("idem-seq@example.com");
        Evento e = novoEvento(u.getId(), "Idem Sequencial", 5);
        String chave = UUID.randomUUID().toString();

        EventWallet primeira = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave);
        // a reserva ainda está ativa: sem a chave, o 2º addLink seria "Vínculo já existente"
        EventWallet repetida = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave);

        assertEquals("pending", repetida.getStatus());
        assertEquals(primeira.getExpiresAt(), repetida.getExpiresAt(), "mesmo corpo: expiresAt idêntico");
        assertEquals(chave, repetida.getIdempotencyKey());
        assertEquals(4, capacidade(e.getEvent_id()).getQuant(), "um único decremento");
        assertEquals(1, contar("walletevent", e.getEvent_id()));
        assertEquals(1, contar("idempotency_key", e.getEvent_id()));
    }

    @Test
    public void repeticaoDepoisDeConfirmarAindaDevolveOResultadoDaPrimeira() {
        Usuario u = novoUsuario("idem-confirm@example.com");
        Evento e = novoEvento(u.getId(), "Idem Confirmada", 3);
        String chave = UUID.randomUUID().toString();

        EventWallet primeira = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave);
        eventWalletService.confirm(u.getId(), e.getEvent_id());
        EventWallet repetida = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave);

        assertEquals("pending", repetida.getStatus(), "resultado da primeira, não o estado atual");
        assertEquals(primeira.getExpiresAt(), repetida.getExpiresAt());
        assertEquals(2, capacidade(e.getEvent_id()).getQuant());
    }

    @Test
    public void semChaveOServicoGeraUmaEDevolve() {
        Usuario u = novoUsuario("idem-gerada@example.com");
        Evento e = novoEvento(u.getId(), "Idem Gerada", 2);

        EventWallet r = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()));

        assertNotNull(r.getIdempotencyKey(), "chave gerada no servidor");
        EventWallet repetida = eventWalletService.addLink(
                new EventWallet(u.getId(), e.getEvent_id()), r.getIdempotencyKey());
        assertEquals(r.getExpiresAt(), repetida.getExpiresAt());
        assertEquals(1, capacidade(e.getEvent_id()).getQuant());
    }

    @Test
    public void mesmaChaveDoMesmoUsuarioComOutroEventoEhConflito() {
        Usuario u = novoUsuario("idem-conf-u1@example.com");
        Evento e1 = novoEvento(u.getId(), "Idem Conflito 1", 5);
        Evento e2 = novoEvento(u.getId(), "Idem Conflito 2", 5);
        String chave = UUID.randomUUID().toString();
        eventWalletService.addLink(new EventWallet(u.getId(), e1.getEvent_id()), chave);

        assertThrows(IdempotencyConflictException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e2.getEvent_id()), chave));
        assertEquals(4, capacidade(e1.getEvent_id()).getQuant());
        assertEquals(5, capacidade(e2.getEvent_id()).getQuant(), "conflito não reserva nada");
    }

    /** Issue #129: a chave é escopada por usuário — a mesma chave em dois usuários não colide. */
    @Test
    public void mesmaChaveEmUsuariosDiferentesNoMesmoEventoNaoColide() throws Exception {
        Usuario u1 = novoUsuario("idem-escopo-u1@example.com");
        Usuario u2 = novoUsuario("idem-escopo-u2@example.com");
        Evento e = novoEvento(u1.getId(), "Idem Escopo", 5);
        String chave = UUID.randomUUID().toString();

        EventWallet r1 = eventWalletService.addLink(new EventWallet(u1.getId(), e.getEvent_id()), chave);
        EventWallet r2 = eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id()), chave);

        assertEquals("pending", r1.getStatus());
        assertEquals("pending", r2.getStatus());
        assertEquals(3, capacidade(e.getEvent_id()).getQuant(), "um decremento por usuário");
        assertEquals(2, contar("walletevent", e.getEvent_id()));
        assertEquals(2, contar("idempotency_key", e.getEvent_id()));

        // a repetição de cada um continua devolvendo a própria reserva, sem decrementar de novo
        EventWallet rep1 = eventWalletService.addLink(new EventWallet(u1.getId(), e.getEvent_id()), chave);
        EventWallet rep2 = eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id()), chave);
        assertEquals(r1.getExpiresAt(), rep1.getExpiresAt(), "o lookup devolve a reserva do próprio usuário");
        assertEquals(r2.getExpiresAt(), rep2.getExpiresAt(), "o lookup devolve a reserva do próprio usuário");
        assertEquals(3, capacidade(e.getEvent_id()).getQuant());
    }

    @Test
    public void reservaQueFalhaNaoGravaAChaveEPodeSerRepetida() throws Exception {
        Usuario creator = novoUsuario("idem-lot-creator@example.com");
        Evento e = novoEvento(creator.getId(), "Idem Lotado", 1);
        Usuario u1 = novoUsuario("idem-lot-u1@example.com");
        Usuario u2 = novoUsuario("idem-lot-u2@example.com");
        String chave = UUID.randomUUID().toString();
        eventWalletService.addLink(new EventWallet(u1.getId(), e.getEvent_id()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id()), chave));
        assertTrue(ex.getMessage().toLowerCase().contains("lotado"));

        // libera a vaga: a MESMA chave agora reserva (a falha não ficou gravada)
        eventWalletService.removeLink(u1.getId(), e.getEvent_id());
        drenarDevolucoes(); // a vaga volta pelo consumer do stock (assíncrono)
        EventWallet r = eventWalletService.addLink(new EventWallet(u2.getId(), e.getEvent_id()), chave);
        assertEquals("pending", r.getStatus());
        assertEquals(0, capacidade(e.getEvent_id()).getQuant());
    }

    @Test
    public void chaveGrandeDemaisEhRejeitada() {
        Usuario u = novoUsuario("idem-invalida@example.com");
        Evento e = novoEvento(u.getId(), "Idem Invalida", 2);
        assertThrows(IllegalArgumentException.class,
                () -> eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), "x".repeat(256)));
    }

    /** 8 requisições simultâneas com a MESMA chave: todas têm sucesso, 1 decremento, 1 linha. */
    @Test
    public void concorrenciaComAMesmaChaveDecrementaUmaVezSo() throws Exception {
        Usuario u = novoUsuario("idem-conc@example.com");
        Evento e = novoEvento(u.getId(), "Idem Concorrente", 5);
        String chave = UUID.randomUUID().toString();
        int n = 8;
        CyclicBarrier largada = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<EventWallet>> futuros = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futuros.add(pool.submit(() -> {
                    EventWallet ew = new EventWallet(u.getId(), e.getEvent_id()); // 1 instância por thread
                    largada.await();
                    return eventWalletService.addLink(ew, chave);
                }));
            }
            LocalDateTime esperado = null;
            for (Future<EventWallet> f : futuros) {
                EventWallet r = f.get(); // qualquer exceção (ex.: "Vínculo já existente") falha o teste
                assertEquals("pending", r.getStatus());
                if (esperado == null) esperado = r.getExpiresAt();
                assertEquals(esperado, r.getExpiresAt(), "todas devolvem o resultado da mesma reserva");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(4, capacidade(e.getEvent_id()).getQuant(), "um único decremento");
        assertEquals(1, contar("walletevent", e.getEvent_id()));
        assertEquals(1, contar("idempotency_key", e.getEvent_id()));
    }

    // ===================== helpers =====================

    private int contar(String tabela, Long eventId) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM reservation." + tabela + " WHERE event_id = ?")) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
