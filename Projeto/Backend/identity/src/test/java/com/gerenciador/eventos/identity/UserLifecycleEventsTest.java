package com.gerenciador.eventos.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Eventos de ciclo de vida do usuário (issue #36): stream {@code identity.user}, um stream por usuário, na MESMA transação da
 * escrita. {@code UserRegistered} / {@code UserUpdated} / {@code UserPasswordChanged} / {@code UserDeactivated} com versões
 * contíguas, payload só com ids (nenhum dado pessoal), e atomicidade: sem evento, sem escrita.
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class UserLifecycleEventsTest {

    @Autowired private UserService userService;
    @Autowired private DatabaseConnection db;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private IdentityEvents eventos;

    private final String rodada = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void limpar() throws Exception {
        EventsTestSupport.limpar(db);
    }

    private User criar(String tag) {
        String n = tag + "-" + rodada;
        return userService.createUser(new User("Ciclo " + n, n + "@example.com",
                "11" + String.format("%09d", Math.abs(n.hashCode()) % 1_000_000_000), "senha123", "1990-01-01"));
    }

    /** (eventType, version, payload) do stream do usuário, em ordem de versão. */
    private List<JsonNode> stream(long userId) throws Exception {
        List<JsonNode> lista = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT event_type, version, payload::text, partition_key FROM identity.event_store "
                        + "WHERE stream_type = 'identity.user' AND stream_id = ? ORDER BY version")) {
            ps.setString(1, String.valueOf(userId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    var no = json.createObjectNode().put("tipo", rs.getString(1)).put("versao", rs.getInt(2))
                            .put("chave", rs.getString(4));
                    no.set("payload", json.readTree(rs.getString(3)));
                    lista.add(no);
                }
            }
        }
        return lista;
    }

    private long contar(String sql) throws Exception {
        try (Connection c = db.getConnection(); var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void cicloDeVidaGeraOsQuatroEventosComVersoesContiguasEPayloadSoDeIds() throws Exception {
        User u = criar("vida");
        u.setFone(u.getFone().substring(0, 10) + "9");
        userService.updateUser(u);
        userService.changePassword(u.getId(), "senha123", "novasenha1");
        userService.deactivateUser(u.getId());

        List<JsonNode> eventos = stream(u.getId());
        assertEquals(List.of("UserRegistered", "UserUpdated", "UserPasswordChanged", "UserDeactivated"),
                eventos.stream().map(e -> e.get("tipo").asText()).toList());
        assertEquals(List.of(1, 2, 3, 4), eventos.stream().map(e -> e.get("versao").asInt()).toList());
        for (JsonNode e : eventos) {
            assertEquals(String.valueOf(u.getId()), e.get("chave").asText(), "chave de partição = user_id");
            JsonNode p = e.get("payload");
            assertEquals(u.getId(), p.get("userId").asLong());
            assertEquals(1, p.get("schemaVersion").asInt());
            assertEquals(2, p.size(), "só ids: nada de e-mail, nome, telefone ou senha: " + p);
        }
        assertEquals(4, contar("SELECT count(*) FROM identity.event_outbox o JOIN identity.event_store e USING (global_position) "
                + "WHERE e.stream_type = 'identity.user'"), "o outbox recebe o evento na mesma transação (o relay publica)");
    }

    @Test
    void desativarUsuarioInexistenteNaoGeraEvento() throws Exception {
        userService.deactivateUser(987_654_321L);
        assertEquals(0, contar("SELECT count(*) FROM identity.event_store WHERE stream_type = 'identity.user'"));
    }

    @Test
    void falhaNoAppendDesfazACriacaoDoUsuarioENaoDeixaCarteiraNemEvento() throws Exception {
        doThrow(new SQLException("append falhou (simulado)")).when(eventos).userRegistered(any(), anyLong());
        User u = new User("Atomico " + rodada, "atomico-" + rodada + "@example.com", "11900000" + Math.abs(rodada.hashCode() % 10), "senha123", null);

        assertThrows(RuntimeException.class, () -> userService.createUser(u));

        assertEquals(0, contar("SELECT count(*) FROM identity.users WHERE email = 'atomico-" + rodada + "@example.com'"),
                "sem evento, sem usuário (mesma transação)");
        assertEquals(0, contar("SELECT count(*) FROM identity.event_store"));
    }

    @Test
    void falhaNoAppendDaTrocaDeSenhaMantemASenhaAntiga() throws Exception {
        User u = criar("senha");
        doThrow(new SQLException("append falhou (simulado)")).when(eventos).userPasswordChanged(any(), anyLong());

        assertThrows(RuntimeException.class, () -> userService.changePassword(u.getId(), "senha123", "novasenha1"));

        assertTrue(userService.login(u.getEmail(), "senha123") != null, "a senha antiga continua valendo");
        assertFalse(userService.login(u.getEmail(), "novasenha1") != null);
    }

    @Test
    void escritasConcorrentesNoMesmoUsuarioNaoPerdemNemRepetemVersao() throws Exception {
        User u = criar("conc");
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> {
                    User copia = userService.findById(u.getId());
                    userService.updateUser(copia);
                }));
            }
            for (Future<?> f : fs) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        List<JsonNode> eventos = stream(u.getId());
        assertEquals(1 + n, eventos.size());
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 1 + n).boxed().toList(),
                eventos.stream().map(e -> e.get("versao").asInt()).toList(), "versões contíguas, sem buraco nem repetição");
    }

    @Test
    void mfaConsumidoPorUpdateCondicionalMesmoCodigoEmOitoThreadsTrocaASenhaUmaVez() throws Exception {
        User u = criar("mfacas");
        userService.requestPasswordResetMfa(u.getId());
        String codigo;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT password_reset_mfa_code FROM identity.users WHERE user_id = ?")) {
            ps.setLong(1, u.getId());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                codigo = rs.getString(1);
            }
        }
        final String code = codigo;
        int n = 8;
        java.util.concurrent.CountDownLatch largada = new java.util.concurrent.CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        int ok = 0;
        try {
            List<Future<Boolean>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final String nova = "novasenha" + i;
                fs.add(pool.submit(() -> {
                    largada.await();
                    try {
                        userService.changePasswordWithMfa(u.getId(), nova, code);
                        return true;
                    } catch (IllegalArgumentException recusado) {
                        return false;
                    }
                }));
            }
            largada.countDown();
            for (Future<Boolean> f : fs) {
                if (f.get()) {
                    ok++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, ok, "o código é de uso único mesmo sob concorrência");
        assertEquals(1, stream(u.getId()).stream().filter(e -> e.get("tipo").asText().equals("UserPasswordChanged")).count(),
                "um só UserPasswordChanged");
        assertFalse(userService.verifyPasswordResetMfaCode(u.getId(), code));
    }
}
