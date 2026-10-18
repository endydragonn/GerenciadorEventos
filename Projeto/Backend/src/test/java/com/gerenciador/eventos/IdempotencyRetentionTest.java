package com.gerenciador.eventos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.TestPropertySource;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.catalog.EventService;
import com.gerenciador.eventos.catalog.api.Event;
import com.gerenciador.eventos.identity.UserService;
import com.gerenciador.eventos.identity.api.User;
import com.gerenciador.eventos.reservation.EventCapRepository;
import com.gerenciador.eventos.reservation.EventWalletService;
import com.gerenciador.eventos.reservation.IdempotencyKeyRepository;
import com.gerenciador.eventos.reservation.IdempotencyKeyRetentionService;
import com.gerenciador.eventos.reservation.MyWalletService;
import com.gerenciador.eventos.reservation.api.EventWallet;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Retenção da {@code idempotency_key} (issue #130): o job apaga as chaves com {@code created_at} mais
 * antigo que {@code idempotency.retention.hours} (default 24h), em lotes, e preserva as retidas.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(GlobalDbTruncator.class)
// Estica o timer do @Scheduled para 1h: o job nunca dispara sozinho durante os testes; eles chamam
// purgeExpired() direto (mesma ideia do ReservationExpiryTest; o run-tests.sh faz o mesmo via env).
@TestPropertySource(properties = "idempotency.retention.scan.ms=3600000")
public class IdempotencyRetentionTest {

    @Autowired private UserService userService;
    @Autowired private MyWalletService myWalletService;
    @Autowired private EventService eventService;
    @Autowired private EventWalletService eventWalletService;
    @Autowired private EventCapRepository eventCapRepository;
    @Autowired private IdempotencyKeyRetentionService retentionService;
    @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired private DatabaseConnection databaseConnection;
    @Autowired private ThreadPoolTaskScheduler taskScheduler;

    /**
     * O scheduler do Boot tem 1 thread por padrão, compartilhada com ReadModelRefreshService (o lag do braço
     * naive) e ReservationExpiryService: um purge longo seguraria o refresh. O pool precisa ter mais de 1.
     */
    @Test
    public void schedulerTemMaisDeUmaThread() {
        assertTrue(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize() > 1,
                "spring.task.scheduling.pool.size deve ser > 1");
    }

    @Test
    public void chaveAntigaEhApagadaERecenteFica() throws Exception {
        User u = novoUsuario("ret-basico@example.com");
        Event e1 = novoEvento(u.getId(), "Ret Antiga", 5);
        Event e2 = novoEvento(u.getId(), "Ret Recente", 5);
        String velha = UUID.randomUUID().toString();
        String nova = UUID.randomUUID().toString();
        eventWalletService.addLink(new EventWallet(u.getId(), e1.getEvent_id()), velha);
        eventWalletService.addLink(new EventWallet(u.getId(), e2.getEvent_id()), nova);
        envelhecer(velha, 25);

        int apagadas = retentionService.purgeExpired();

        assertEquals(1, apagadas);
        assertEquals(0, contarChave(velha), "passou do prazo de retenção: apagada");
        assertEquals(1, contarChave(nova), "dentro do prazo: fica");
    }

    @Test
    public void repeticaoComChaveAindaRetidaContinuaIdempotente() throws Exception {
        User u = novoUsuario("ret-idem@example.com");
        Event e = novoEvento(u.getId(), "Ret Idem", 5);
        String chave = UUID.randomUUID().toString();
        EventWallet primeira = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave);
        envelhecer(chave, 23); // ainda dentro das 24h

        retentionService.purgeExpired();

        EventWallet repetida = eventWalletService.addLink(new EventWallet(u.getId(), e.getEvent_id()), chave);
        assertEquals(primeira.getExpiresAt(), repetida.getExpiresAt(), "mesmo resultado da primeira");
        assertEquals(4, eventCapRepository.findByEventId(e.getEvent_id()).getQuant(), "um único decremento");
        assertEquals(1, contarChave(chave));
    }

    /** 2500 chaves velhas (> 2 lotes de 1000): o laço apaga tudo e a recente sobrevive. */
    @Test
    public void apagaEmLotesAteEsvaziarAsVencidas() throws Exception {
        exec("INSERT INTO idempotency_key (idempotency_key, user_id, event_id, expires_at, created_at) "
                + "SELECT 'lote-' || g, g, 1, now(), now() - interval '48 hours' FROM generate_series(1, 2500) g");
        exec("INSERT INTO idempotency_key (idempotency_key, user_id, event_id, expires_at) "
                + "VALUES ('lote-recente', 1, 1, now())");

        // um lote devolve EXATAMENTE o LIMIT (prova o LIMIT); o job apaga o resto no laço
        assertEquals(1000, idempotencyKeyRepository.deleteOlderThan(24, 1000));
        int apagadas = retentionService.purgeExpired();

        assertEquals(1500, apagadas, "restante das 2500 (o laço do job: 1000 + 500)");
        assertEquals(1, contarChaveLike("lote-%"), "só a recente sobrou");
        assertEquals(1, contarChave("lote-recente"));
    }

    // ===================== helpers =====================

    private User novoUsuario(String email) {
        User u = new User();
        u.setName("Ret " + email);
        u.setEmail(email);
        u.setFone("118" + Math.abs(email.hashCode() % 100000000));
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

    /** Envelhece a linha: created_at = agora - horas. */
    private void envelhecer(String chave, int horas) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE idempotency_key SET created_at = now() - make_interval(hours => ?) "
                             + "WHERE idempotency_key = ?")) {
            ps.setInt(1, horas);
            ps.setString(2, chave);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private void exec(String sql) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private int contarChave(String chave) throws Exception {
        return contar("idempotency_key = ?", chave);
    }

    private int contarChaveLike(String padrao) throws Exception {
        return contar("idempotency_key LIKE ?", padrao);
    }

    private int contar(String where, String param) throws Exception {
        try (Connection conn = databaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM idempotency_key WHERE " + where)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
