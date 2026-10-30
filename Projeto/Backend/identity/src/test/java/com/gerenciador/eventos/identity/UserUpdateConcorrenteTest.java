package com.gerenciador.eventos.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.gerenciador.eventos.TestSupport.GlobalDbTruncator;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.shared.DatabaseConnection;

/**
 * {@code UserRepository.update} (issue #170): o campo não informado é preservado pelo próprio {@code UPDATE} ({@code COALESCE}),
 * sem ler-mesclar-gravar. Antes, um {@code PUT /internal/users/{id}} sem {@code password}, concorrente a uma troca de senha, relia o
 * hash antigo e o regravava por cima do novo. Desde a #171 o {@code UPDATE} nem toca em {@code password} (o PUT ignora o campo).
 * O teste de concorrência é determinístico (sem corrida de threads): a troca de senha fica aberta, o PUT bloqueia no lock da linha e
 * só então a troca é comitada.
 */
@SpringBootTest
@Import(GlobalDbTruncator.class)
public class UserUpdateConcorrenteTest {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private DatabaseConnection db;
    @Autowired private PasswordEncoder passwordEncoder;

    private final String rodada = UUID.randomUUID().toString().substring(0, 8);

    private User criar(String tag, boolean admin) {
        String n = tag + "-" + rodada;
        User u = new User("Upd " + n, n + "@example.com", "11" + Math.abs(n.hashCode() % 900000000 + 100000000), "senha123",
                "2000-01-01");
        u.setIsAdmin(admin);
        return userService.createUser(u);
    }

    /** O PUT do gateway: só o que o usuário edita (nome, e-mail, fone), sem senha, nascimento nem admin. */
    private User putSemOpcionais(User base, String novoNome) {
        User put = new User();
        put.setId(base.getId());
        put.setName(novoNome);
        put.setEmail(base.getEmail());
        put.setFone(base.getFone());
        put.setIsAdmin(null); // o construtor vazio assume false; "não informado" é null explícito
        return put;
    }

    private int pidDa(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_backend_pid()"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private boolean alguemEsperaPor(int pid) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid))")) {
            ps.setInt(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    @Test
    void putSemSenhaConcorrenteATrocaDeSenhaNaoDesfazATroca() throws Exception {
        User u = criar("conc", false);
        String hashNovo = passwordEncoder.encode("novaSenha1");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection troca = db.getConnection()) {
            troca.setAutoCommit(false);
            try {
                // (1) a troca de senha escreve a linha e fica ABERTA (segura o lock de linha)
                assertTrue(userRepository.updatePassword(troca, u.getId(), hashNovo));
                int pidTroca = pidDa(troca);

                // (2) o PUT sem senha, em outra conexão, bloqueia no lock da linha
                User put = putSemOpcionais(u, "Renomeado " + rodada);
                Future<Boolean> putFuturo = pool.submit(() -> {
                    try (Connection c = db.getConnection()) {
                        return userRepository.update(c, put);
                    }
                });

                // (3) espera o PUT ficar bloqueado esperando a troca
                long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!alguemEsperaPor(pidTroca)) {
                    assertFalse(putFuturo.isDone(), "o PUT terminou sem esperar o lock da troca de senha");
                    assertTrue(System.nanoTime() < limite, "o PUT não ficou esperando o lock da linha em 15 s");
                    Thread.sleep(50);
                }

                // (4) a troca de senha comita
                troca.commit();

                // (5) o PUT termina e o hash novo sobrevive
                assertTrue(putFuturo.get(15, TimeUnit.SECONDS));
            } catch (Throwable t) {
                troca.rollback();
                throw t;
            }
        } finally {
            pool.shutdownNow();
        }
        User depois = userService.findById(u.getId());
        assertEquals(hashNovo, depois.getPassword(), "o PUT sem senha não pode regravar o hash antigo por cima da troca");
        assertEquals("Renomeado " + rodada, depois.getName(), "o PUT gravou o que veio");
        assertNotNull(userService.login(u.getEmail(), "novaSenha1"), "a senha nova vale");
        assertEquals(null, userService.login(u.getEmail(), "senha123"), "a senha antiga não vale mais");
    }

    @Test
    void camposNaoInformadosSaoPreservadosEOsInformadosSobrescrevem() throws Exception {
        User u = criar("coalesce", true);
        String hashAntigo = userService.findById(u.getId()).getPassword();

        // nada opcional: senha, nascimento e admin ficam
        assertTrue(runUpdate(putSemOpcionais(u, "Nome B " + rodada)));
        User a = userService.findById(u.getId());
        assertEquals(hashAntigo, a.getPassword());
        assertEquals("2000-01-01", a.getBirthDate());
        assertEquals(Boolean.TRUE, a.getIsAdmin());
        assertEquals("Nome B " + rodada, a.getName());

        // nascimento impossível de interpretar preserva o existente
        User lixo = putSemOpcionais(u, "Nome B " + rodada);
        lixo.setBirthDate("isto não é data");
        assertTrue(runUpdate(lixo));
        assertEquals("2000-01-01", userService.findById(u.getId()).getBirthDate());

        // informados sobrescrevem: nascimento (dd/MM/yyyy) e admin; a senha enviada é ignorada (#171)
        User tudo = putSemOpcionais(u, "Nome C " + rodada);
        tudo.setBirthDate("15/03/1990");
        tudo.setIsAdmin(false);
        tudo.setPassword("$2a$10$hashqualquerparaoteste");
        assertTrue(runUpdate(tudo));
        User c = userService.findById(u.getId());
        assertEquals("1990-03-15", c.getBirthDate());
        assertEquals(Boolean.FALSE, c.getIsAdmin());
        assertEquals(hashAntigo, c.getPassword(), "o repositório ignora a senha enviada (#171)");
        assertEquals("Nome C " + rodada, c.getName());
    }

    @Test
    void usuarioInexistenteDevolveFalseSemGravarNada() throws Exception {
        User fantasma = new User();
        fantasma.setId(987654321L);
        fantasma.setName("Fantasma");
        fantasma.setEmail("fantasma-" + rodada + "@example.com");
        fantasma.setFone("11900000000");
        assertFalse(runUpdate(fantasma));
        assertEquals(null, userRepository.findById(987654321L));
    }

    @Test
    void putSemSenhaPeloServicoMantemASenha() {
        User u = criar("semsenha", false);
        userService.updateUser(putSemOpcionais(u, "Sem senha " + rodada));
        assertNotNull(userService.login(u.getEmail(), "senha123"));
    }

    private boolean runUpdate(User u) throws Exception {
        try (Connection c = db.getConnection()) {
            return userRepository.update(c, u);
        }
    }
}
