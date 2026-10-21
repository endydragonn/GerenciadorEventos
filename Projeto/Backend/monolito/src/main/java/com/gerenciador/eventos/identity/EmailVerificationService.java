package com.gerenciador.eventos.identity;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.identity.api.EmailVerificationApi;
import com.gerenciador.eventos.identity.api.User;

@Service
public class EmailVerificationService implements EmailVerificationApi {

    @Autowired
    private IdentityNotifications notifications;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PendingVerificationRepository pendingVerificationRepository;

    @Autowired
    private UserRepository userRepository;

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
        
        // Converte String para LocalDate
        LocalDate birthDateLocal = LocalDate.parse(birthDate);
        
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
        // o e-mail em si é do serviço notification (evento no backbone, issue #32), o monólito não envia.
        notifications.emTransacao(conn -> {
            pendingVerificationRepository.deleteByEmail(conn, email);
            pendingVerificationRepository.save(conn, verification);
            notifications.verificationCodeRequested(conn, email, userName, verificationCode, expirationMinutes);
        });
    }

    /**
     * Verifica o código e cria o usuário se válido
     */
    public User verifyEmailAndCreateUser(String email, String verificationCode) throws SQLException, IllegalArgumentException {
        
        // Busca registro pendente
        Optional<PendingVerification> pending = pendingVerificationRepository.findByEmailAndCode(email, verificationCode);
        
        if (pending.isEmpty()) {
            throw new IllegalArgumentException("Código de verificação inválido");
        }

        PendingVerification verification = pending.get();

        // Verifica se expirou
        if (verification.isExpired()) {
            pendingVerificationRepository.delete(verification.getId());
            throw new IllegalArgumentException("Código de verificação expirado");
        }

        // Verifica se ultrapassou tentativas
        if (verification.hasExceededAttempts()) {
            pendingVerificationRepository.delete(verification.getId());
            throw new IllegalArgumentException("Máximo de tentativas excedido. Solicite um novo código.");
        }

        // Incrementa tentativa
        pendingVerificationRepository.incrementAttempts(verification.getId());

        // Cria usuário com os dados armazenados
        User user = new User();
        user.setName(verification.getUserName());
        user.setEmail(verification.getEmail());
        user.setFone(verification.getFone());
        user.setPassword(verification.getPasswordHash());
        user.setBirthDate(verification.getBirthdate().toString());
        user.setIsAdmin(false);
        user.setIsActive(true);

        // Salva usuário
        userRepository.save(user);

        // Deleta registro pendente
        pendingVerificationRepository.delete(verification.getId());

        return user;
    }

    /**
     * Reenvia código de verificação
     */
    public void resendVerificationCode(String email) throws SQLException, IllegalArgumentException {
        
        Optional<PendingVerification> pending = pendingVerificationRepository.findByEmail(email);
        
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

    /**
     * Gera código de verificação aleatório
     */
    private String generateVerificationCode() {
        Random random = new Random();
        StringBuilder code = new StringBuilder();
        
        for (int i = 0; i < codeLength; i++) {
            code.append(random.nextInt(10));
        }
        
        return code.toString();
    }
}
