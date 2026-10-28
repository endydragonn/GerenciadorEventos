package com.gerenciador.eventos.identity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.identityclient.api.User;

/**
 * Issue #166: {@code updateUser} recusa (400 = {@link IllegalArgumentException}) o e-mail que já é de OUTRA conta, comparando sem
 * diferenciar maiúsculas (a UNIQUE do banco é sensível a caixa e não pegaria {@code A@x.com} contra {@code a@x.com}). O próprio dono
 * reenviando o seu e-mail, inclusive com outra caixa, segue.
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class UserUpdateEmailDuplicadoTest {

    @Autowired private UserService userService;

    private final String rodada = UUID.randomUUID().toString().substring(0, 8);

    private User criar(String tag) {
        String n = tag + "-" + rodada;
        return userService.createUser(new User("Dup " + n, n + "@example.com",
                "11" + String.format("%09d", Math.abs(n.hashCode()) % 1_000_000_000), "senha123", "1990-01-01"));
    }

    @Test
    void emailDeOutraContaERecusadoSemDiferenciarMaiusculas() {
        User a = criar("dono-a");
        User b = criar("dono-b");

        b.setEmail(a.getEmail().toUpperCase());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> userService.updateUser(b));
        assertEquals(UserService.CADASTRO_DUPLICADO, e.getMessage());

        b.setEmail(a.getEmail());
        assertThrows(IllegalArgumentException.class, () -> userService.updateUser(b), "mesma caixa também");
        assertEquals("dono-b-" + rodada + "@example.com", userService.findById(b.getId()).getEmail(), "nada foi gravado");
    }

    @Test
    void proprioEmailSegueMesmoComOutraCaixa() {
        User a = criar("proprio");

        a.setEmail(a.getEmail().toUpperCase());
        a.setName("Renomeado " + rodada);
        User salvo = userService.updateUser(a);

        assertEquals(a.getEmail(), salvo.getEmail());
        assertEquals("Renomeado " + rodada, salvo.getName());
    }

    @Test
    void emailLivreSegue() {
        User a = criar("livre");

        a.setEmail("novo-" + rodada + "@example.com");

        assertEquals("novo-" + rodada + "@example.com", userService.updateUser(a).getEmail());
    }
}
