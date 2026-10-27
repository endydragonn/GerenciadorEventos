package com.gerenciador.eventos.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.identityclient.api.EmailVerificationApi;
import com.gerenciador.eventos.identityclient.api.LoginResult;
import com.gerenciador.eventos.identityclient.api.User;

/** Cadastro em duas fases (pending_verifications -> users) com o pedido de e-mail via evento (issue #32) e o JWT do login automático. */
@Service
public class EmailVerificationService implements EmailVerificationApi {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private IdentityNotifications notifications;

    @Autowired
    private IdentityEvents events;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PendingVerificationRepository pendingVerificationRepository;

    @Autowired
    private UserRepository userRepository;

    /** Emite o token do login automático (RS256, {@code uid}, {@code isHost} lido do banco). */
    @Autowired
    private JwtIssuer jwtIssuer;

    @Value("${verification.code.length:6}")
    private int codeLength;

    @Value("${verification.code.expiration.minutes:5}")
    private int expirationMinutes;

    @Value("${verification.code.max.attempts:5}")
    private int maxAttempts;

    /**
     * Solicita verificação de email - armazena dados pendentes e pede o envio do código (evento para o serviço notification)
     */
    public void requestEmailVerification(String email, String fone, String userName, String password, String birthDate)
            throws SQLException, IllegalArgumentException {
        if (email == null || email.isBlank() || fone == null || fone.isBlank() || userName == null || userName.isBlank()
                || password == null || password.isEmpty() || birthDate == null) {
            throw new IllegalArgumentException("Email, telefone, nome, senha e data de nascimento são obrigatórios");
        }
        LocalDate birthDateLocal;
        try {
            birthDateLocal = LocalDate.parse(birthDate);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Data de nascimento inválida (use yyyy-MM-dd)");
        }

        // Valida se email já existe
        if (userRepository.emailExists(email)) {
            throw new IllegalArgumentException("Email já cadastrado");
        }

        // Gera código de verificação
        String verificationCode = generateVerificationCode();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(expirationMinutes);

        // Cria registro de verificação pendente
        String passwordHash = passwordEncoder.encode(password);
        PendingVerification verification = new PendingVerification(
            email, fone, userName, passwordHash, birthDateLocal, verificationCode, expiresAt
        );
        verification.setMaxAttempts(maxAttempts);

        // Substitui qualquer verificação pendente deste e-mail e pede o envio do código, tudo na MESMA transação:
        // o e-mail em si é do serviço notification (evento no backbone, issue #32), o identity não envia.
        notifications.emTransacao(conn -> {
            pendingVerificationRepository.deleteByEmail(conn, email);
            pendingVerificationRepository.save(conn, verification);
            notifications.verificationCodeRequested(conn, email, userName, verificationCode, expirationMinutes);
        });
    }

    /**
     * Verifica o código e cria o usuário se válido; devolve o usuário com o token do login automático. Cada verificação consome
     * uma tentativa (atômico); o código errado só falha, e depois de {@code max_attempts} até o código certo é recusado
     * (o reenvio gera código novo e zera o contador).
     */
    public LoginResult verifyEmailAndCreateUser(String email, String verificationCode) throws SQLException, IllegalArgumentException {
        Optional<PendingVerification> pending = email == null ? Optional.empty() : pendingVerificationRepository.findByEmail(email);
        if (pending.isEmpty()) {
            throw new IllegalArgumentException("Código de verificação inválido");
        }
        PendingVerification verification = pending.get();

        if (verification.isExpired()) {
            pendingVerificationRepository.delete(verification.getId());
            throw new IllegalArgumentException("Código de verificação expirado");
        }
        if (!pendingVerificationRepository.consumeAttempt(verification.getId())) {
            pendingVerificationRepository.delete(verification.getId());
            throw new IllegalArgumentException("Máximo de tentativas excedido. Solicite um novo código.");
        }
        if (verificationCode == null || !MessageDigest.isEqual(verification.getVerificationCode().getBytes(StandardCharsets.UTF_8),
                verificationCode.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Código de verificação inválido");
        }

        // Cria usuário com os dados armazenados; o fim da verificação pendente e o UserRegistered entram na MESMA transação
        User criado = notifications.emTransacaoComResultado(conn -> {
            User user = new User();
            user.setName(verification.getUserName());
            user.setEmail(verification.getEmail());
            user.setFone(verification.getFone());
            user.setPassword(verification.getPasswordHash());
            user.setBirthDate(verification.getBirthdate().toString());
            user.setIsAdmin(false);
            user.setIsActive(true);
            userRepository.save(conn, user);
            pendingVerificationRepository.delete(conn, verification.getId());
            events.userRegistered(conn, user.getId());
            return user;
        });
        return new LoginResult(criado, jwtIssuer.emitir(criado.getEmail(), criado.getId(), Boolean.TRUE.equals(criado.getIsHost())));
    }

    /**
     * Reenvia código de verificação
     */
    public void resendVerificationCode(String email) throws SQLException, IllegalArgumentException {
        Optional<PendingVerification> pending = email == null ? Optional.empty() : pendingVerificationRepository.findByEmail(email);
        if (pending.isEmpty()) {
            throw new IllegalArgumentException("Nenhuma verificação pendente para este email");
        }
        PendingVerification verification = pending.get();

        // Verifica se expirou
        if (verification.isExpired()) {
            pendingVerificationRepository.delete(verification.getId());
            throw new IllegalArgumentException("Código expirado. Solicite um novo registro.");
        }

        // Gera novo código
        String newCode = generateVerificationCode();
        verification.setVerificationCode(newCode);
        verification.setExpiresAt(LocalDateTime.now().plusMinutes(expirationMinutes));
        verification.setAttempts(0); // Reseta tentativas

        // Troca o registro pendente e pede o reenvio do código na MESMA transação (evento no backbone, issue #32)
        notifications.emTransacao(conn -> {
            pendingVerificationRepository.deleteByEmail(conn, email);
            pendingVerificationRepository.save(conn, verification);
            notifications.verificationCodeRequested(conn, email, verification.getUserName(), newCode, expirationMinutes);
        });
    }

    /** Gera código de verificação aleatório (dígitos, SecureRandom) */
    private String generateVerificationCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < codeLength; i++) {
            code.append(RANDOM.nextInt(10));
        }
        return code.toString();
    }
}
