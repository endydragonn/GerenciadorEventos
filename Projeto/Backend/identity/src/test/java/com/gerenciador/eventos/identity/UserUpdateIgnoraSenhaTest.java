package com.gerenciador.eventos.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.EventsTestSupport;
import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * Issue #171: o {@code PUT /internal/users/{id}} IGNORA o campo {@code password} (nulo, vazio ou com valor): trocar a senha só é possível
 * por {@code /password} e {@code /password-with-mfa}. O hash guardado fica byte a byte o mesmo, o login com a senha antiga segue valendo,
 * a resposta não devolve senha nem hash e o PUT appenda só {@code UserUpdated} (nunca {@code UserPasswordChanged}).
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class UserUpdateIgnoraSenhaTest {

    @Autowired private UserService userService;
    @Autowired private DatabaseConnection db;

    private final String rodada = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void limpar() throws Exception {
        EventsTestSupport.limpar(db);
    }

    private User criar(String tag) {
        String n = tag + "-" + rodada;
        return userService.createUser(new User("Ign " + n, n + "@example.com",
                "11" + String.format("%09d", Math.abs(n.hashCode()) % 1_000_000_000), "senha123", "2000-01-01"));
    }

    private User put(User base, String nome, String password) {
        User put = new User();
        put.setId(base.getId());
        put.setName(nome);
        put.setEmail(base.getEmail());
        put.setFone(base.getFone());
        put.setIsAdmin(null);
        put.setPassword(password);
        return put;
    }

    private List<String> tiposDoStream(long userId) throws Exception {
        List<String> tipos = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT event_type FROM identity.event_store WHERE stream_type = 'identity.user' AND stream_id = ? ORDER BY version")) {
            ps.setString(1, String.valueOf(userId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tipos.add(rs.getString(1));
                }
            }
        }
        return tipos;
    }

    @Test
    void putComPasswordNuloVazioOuComValorNaoMudaOHashEOLoginAntigoSegueValendo() {
        User u = criar("ignora");
        String hashAntes = userService.findById(u.getId()).getPassword();

        String[] enviados = {null, "", "   ", "nova", "$2a$10$hashqualquerparaoteste", "novaSenhaLonga123"};
        for (String enviado : enviados) {
            User resposta = userService.updateUser(put(u, "Nome " + enviado + " " + rodada, enviado));

            assertEquals(hashAntes, userService.findById(u.getId()).getPassword(), "PUT com password=" + enviado + " não toca no hash");
            assertEquals("Nome " + enviado + " " + rodada, resposta.getName(), "o resto do PUT continua valendo");
            assertNull(resposta.getPassword(), "a resposta não devolve senha nem hash (password=" + enviado + ")");
        }
        assertNotNull(userService.login(u.getEmail(), "senha123"), "a senha antiga segue valendo");
        assertNull(userService.login(u.getEmail(), "nova"), "o valor enviado no PUT nunca vira senha");
        assertNull(userService.login(u.getEmail(), "novaSenhaLonga123"), "nem uma senha válida (>= 6 caracteres) enviada no PUT");
    }

    @Test
    void putAppendaSoUserUpdatedENuncaUserPasswordChanged() throws Exception {
        User u = criar("evento");

        userService.updateUser(put(u, "Evento A " + rodada, "novaSenhaLonga123"));
        userService.updateUser(put(u, "Evento B " + rodada, null));

        assertEquals(List.of("UserRegistered", "UserUpdated", "UserUpdated"), tiposDoStream(u.getId()));
    }

    @Test
    void trocaDeSenhaPelaRotaPropriaContinuaFuncionando() throws Exception {
        User u = criar("rota");

        userService.updateUser(put(u, "Rota " + rodada, "ignorada123"));
        userService.changePassword(u.getId(), "senha123", "novaSenha456");

        assertNotNull(userService.login(u.getEmail(), "novaSenha456"));
        assertNull(userService.login(u.getEmail(), "senha123"));
        assertNull(userService.login(u.getEmail(), "ignorada123"));
        assertEquals(List.of("UserRegistered", "UserUpdated", "UserPasswordChanged"), tiposDoStream(u.getId()));
    }
}
