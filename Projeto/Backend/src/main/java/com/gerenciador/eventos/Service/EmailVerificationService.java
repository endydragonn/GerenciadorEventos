package com.gerenciador.eventos.Service;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.POJO.PendingVerification;
import com.gerenciador.eventos.POJO.User;
import com.gerenciador.eventos.Repository.PendingVerificationRepository;
import com.gerenciador.eventos.Repository.UserRepository;
import com.gerenciador.eventos.config.EmailTemplates;

@Service
public class EmailVerificationService {

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PendingVerificationRepository pendingVerificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Value("${spring.mail.username:enricospanier@gmail.com}")
    private String fromEmail;

    @Value("${verification.code.length:6}")
    private int codeLength;

    @Value("${verification.code.expiration.minutes:5}")
    private int expirationMinutes;

    @Value("${verification.code.max.attempts:5}")
    private int maxAttempts;

    /**
     * Solicita verificação de email - armazena dados pendentes e envia código
     */
    public void requestEmailVerification(String email, String fone, String userName, String password, String birthDate)
            throws SQLException, IllegalArgumentException {
        
        // Converte String para LocalDate
        LocalDate birthDateLocal = LocalDate.parse(birthDate);
        
        // Valida se email já existe
        if (userRepository.emailExists(email)) {
            throw new IllegalArgumentException("Email já cadastrado");
        }

        // Valida se há verificação pendente para este email
        Optional<PendingVerification> existing = pendingVerificationRepository.findByEmail(email);
        if (existing.isPresent()) {
            // Deletar anterior e criar nova
            pendingVerificationRepository.deleteByEmail(email);
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

        // Salva no banco
        pendingVerificationRepository.save(verification);

        // Envia email com código
        sendVerificationEmail(email, userName, verificationCode);
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

        // Atualiza registro (precisamos fazer update no banco)
        pendingVerificationRepository.deleteByEmail(email);
        pendingVerificationRepository.save(verification);

        // Reenvia email
        sendVerificationEmail(email, verification.getUserName(), newCode);
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

    /**
     * Envia email com código de verificação
     */
    private void sendVerificationEmail(String toEmail, String userName, String verificationCode) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject(EmailTemplates.getVerificationCodeSubject());
            message.setText(EmailTemplates.getVerificationCodeTemplate(userName, verificationCode, expirationMinutes));
            
            mailSender.send(message);
        } catch (MailException e) {
            System.err.println("Erro ao enviar email: " + e.getMessage());
            // Log erro mas não bloqueia o fluxo
        }
    }

    /**
     * Envia código MFA para reset de senha
     */
    public void sendPasswordResetMfaEmail(String toEmail, String userName, String mfaCode) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject(EmailTemplates.getPasswordResetMfaSubject());
            message.setText(EmailTemplates.getPasswordResetMfaTemplate(userName, mfaCode));
            
            mailSender.send(message);
            System.out.println("✅ Email de MFA enviado com sucesso para: " + toEmail);
        } catch (MailException e) {
            System.err.println("❌ Erro ao enviar email de MFA: " + e.getMessage());
            throw new RuntimeException("Erro ao enviar email de MFA: " + e.getMessage());
        }
    }
}
