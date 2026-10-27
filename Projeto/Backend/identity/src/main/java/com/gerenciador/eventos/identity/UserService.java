package com.gerenciador.eventos.identity;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.identityclient.api.IdentityNaoEncontradoException;
import com.gerenciador.eventos.identityclient.api.LoginResult;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.identityclient.api.UserApi;

/**
 * Service - lógica de negócio e validações de usuário (issue #36: o mesmo código que era do monólito, agora dentro do serviço
 * identity). Toda escrita e o seu evento de ciclo de vida ({@link IdentityEvents}) vão na MESMA transação.
 */
@Service
public class UserService implements UserApi {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private IdentityNotifications notifications;

    @Autowired
    private IdentityEvents events;

    /** Emite o token do login: o gateway não assina nada. */
    @Autowired
    private JwtIssuer jwtIssuer;

    /** Criar novo usuário com validações */
    public User createUser(User user) {
        validateRequiredFields(user);
        validateDuplicates(user);
        user.setIsActive(true);
        // Hash da senha antes de salvar
        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }
        try {
            return notifications.emTransacaoComResultado(conn -> {
                User salvo = userRepository.save(conn, user);
                events.userRegistered(conn, salvo.getId());
                return salvo;
            });
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao salvar usuário: " + e.getMessage(), e);
        }
    }

    /** Criar usuário completo (método helper) */
    public User createNewUser(String name, String email, String password, String fone) {
        return createUser(new User(name, email, fone, password, null));
    }

    /** Obter erros de validação em formato String */
    public String getValidationErrors(User user) {
        StringBuilder errors = new StringBuilder();

        if (user.getName() == null || user.getName().trim().isEmpty()) {
            errors.append("Nome não pode estar vazio. ");
        }
        if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
            errors.append("Email não pode estar vazio. ");
        }
        if (user.getEmail() != null && !user.getEmail().trim().isEmpty()
            && userRepository.emailExists(user.getEmail())) {
            errors.append("Email '").append(user.getEmail()).append("' já está cadastrado. ");
        }
        if (user.getName() != null && !user.getName().trim().isEmpty()
            && userRepository.nameExists(user.getName())) {
            errors.append("Nome '").append(user.getName()).append("' já está cadastrado. ");
        }
        if (user.getFone() != null && !user.getFone().trim().isEmpty()
            && userRepository.foneExists(user.getFone())) {
            errors.append("Telefone '").append(user.getFone()).append("' já está cadastrado. ");
        }

        return errors.toString().trim();
    }

    /** Buscar usuário por ID */
    public User findById(Long id) {
        User user = userRepository.findById(id);
        if (user == null) {
            throw new IdentityNaoEncontradoException("Usuário não encontrado com ID: " + id);
        }
        return user;
    }

    @Override
    public boolean exists(Long userId) {
        return userRepository.findById(userId) != null;
    }

    @Override
    public Map<Long, String> findNames(Collection<Long> ids) {
        return ids == null || ids.isEmpty() ? Map.of() : userRepository.findActiveNames(ids);
    }

    /** Buscar usuário por email */
    public User findByEmail(String email) {
        User user = userRepository.findByEmail(email);
        if (user == null) {
            throw new IdentityNaoEncontradoException("Usuário não encontrado com email: " + email);
        }
        return user;
    }

    /**
     * Atualizar usuário. {@code host} NÃO é gravável (nenhuma rota promove a host). Devolve a linha como ficou no banco
     * (relida), não o que o chamador mandou.
     */
    public User updateUser(User user) {
        if (user.getId() == null) {
            throw new IllegalArgumentException("ID do usuário não pode ser nulo");
        }
        validateRequiredFields(user);
        try {
            return notifications.emTransacaoComResultado(conn -> {
                if (!userRepository.update(conn, user)) {
                    throw new IdentityNaoEncontradoException("Usuário não encontrado com ID: " + user.getId());
                }
                events.userUpdated(conn, user.getId());
                return userRepository.findById(conn, user.getId());
            });
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao atualizar usuário: " + e.getMessage(), e);
        }
    }

    /** Desativar usuário (soft delete). Usuário inexistente: no-op, sem evento. */
    public void deactivateUser(Long id) {
        try {
            notifications.emTransacao(conn -> {
                if (userRepository.softDelete(conn, id)) {
                    events.userDeactivated(conn, id);
                }
            });
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao deletar usuário: " + e.getMessage(), e);
        }
    }

    // ========== Validações Privadas ==========

    private void validateRequiredFields(User user) {
        if (user.getName() == null || user.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Nome não pode estar vazio");
        }
        if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
            throw new IllegalArgumentException("Email não pode estar vazio");
        }
        if (user.getFone() == null || user.getFone().trim().isEmpty()) {
            throw new IllegalArgumentException("Telefone não pode estar vazio");
        }
    }

    private void validateDuplicates(User user) {
        if (user.getName() != null && !user.getName().trim().isEmpty()
            && userRepository.nameExists(user.getName())) {
            throw new IllegalArgumentException("Nome já existente: " + user.getName());
        }
        if (user.getEmail() != null && !user.getEmail().trim().isEmpty()
            && userRepository.emailExists(user.getEmail())) {
            throw new IllegalArgumentException("Email já existente: " + user.getEmail());
        }
        if (user.getFone() != null && !user.getFone().trim().isEmpty()
            && userRepository.foneExists(user.getFone())) {
            throw new IllegalArgumentException("Telefone já existente: " + user.getFone());
        }
    }

    /**
     * Usuário + token (RS256, com {@code uid} e {@code isHost} lido do banco agora), ou {@code null} se e-mail/senha não
     * conferem (inclusive e-mail ou senha nulos: nunca vira erro 500).
     */
    public LoginResult login(String email, String password) {
        if (email == null || password == null) {
            return null;
        }
        User user = userRepository.findByEmail(email);
        if (user != null && passwordEncoder.matches(password, user.getPassword())) {
            return new LoginResult(user, token(user));
        }
        return null;
    }

    /** Token do usuário como ele está no banco AGORA (o {@code isHost} vem da linha lida, nunca do corpo da requisição). */
    String token(User user) {
        return jwtIssuer.emitir(user.getEmail(), user.getId(), Boolean.TRUE.equals(user.getIsHost()));
    }

    /**
     * Alterar senha do usuário
     * @param userId ID do usuário
     * @param oldPassword Senha atual (para validação)
     * @param newPassword Nova senha
     * @return true se a senha foi alterada com sucesso
     */
    public boolean changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("Usuário não encontrado");
        }
        // Validar senha antiga (nula = errada: o BCrypt lançaria outra exceção)
        if (oldPassword == null || !passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new IllegalArgumentException("Senha atual incorreta");
        }
        String hash = hashDaNovaSenha(newPassword);
        try {
            notifications.emTransacao(conn -> {
                userRepository.updatePassword(conn, userId, hash);
                events.userPasswordChanged(conn, userId);
            });
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao alterar senha: " + e.getMessage(), e);
        }
        return true;
    }

    /**
     * Gerar o código MFA de reset de senha e pedir o envio por e-mail: grava o código e appenda o evento
     * {@code PasswordResetMfaRequested} (event_store + outbox) na MESMA transação; quem envia é o serviço
     * {@code notification} (issue #32), então o identity não toca em SMTP.
     */
    public User requestPasswordResetMfa(Long userId) {
        User user = findById(userId);
        String mfaCode = generateAlphanumericCode(8);
        try {
            notifications.emTransacao(conn -> {
                userRepository.setPasswordResetMfaCode(conn, userId, mfaCode);
                notifications.passwordResetMfaRequested(conn, user.getEmail(), user.getName(), mfaCode);
            });
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao pedir código MFA: " + e.getMessage(), e);
        }
        return user;
    }

    /** Validar código MFA para reset de senha (usuário inexistente ou código nulo = {@code false}) */
    public boolean verifyPasswordResetMfaCode(Long userId, String mfaCode) {
        return mfaCode != null && userRepository.verifyPasswordResetMfaCode(userId, mfaCode);
    }

    /**
     * Alterar senha com MFA (sem exigir senha antiga). A troca é um UPDATE condicional que consome o código (CAS, mesma transação do
     * {@code UserPasswordChanged}): reuso do mesmo código, inclusive concorrente, troca a senha UMA vez e as demais chamadas recebem
     * "Código MFA inválido ou expirado".
     */
    public void changePasswordWithMfa(Long userId, String newPassword, String mfaCode) {
        if (!verifyPasswordResetMfaCode(userId, mfaCode)) {
            throw new IllegalArgumentException("Código MFA inválido ou expirado");
        }
        String hash = hashDaNovaSenha(newPassword);
        try {
            notifications.emTransacao(conn -> {
                if (!userRepository.updatePasswordConsumingMfa(conn, userId, hash, mfaCode)) {
                    throw new IllegalArgumentException("Código MFA inválido ou expirado");
                }
                events.userPasswordChanged(conn, userId);
            });
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao alterar senha: " + e.getMessage(), e);
        }
    }

    /** Valida a senha nova e devolve o hash BCrypt. */
    private String hashDaNovaSenha(String newPassword) {
        if (newPassword == null || newPassword.trim().length() < 6) {
            throw new IllegalArgumentException("A nova senha deve ter no mínimo 6 caracteres");
        }
        return passwordEncoder.encode(newPassword);
    }

    /** Gerar código MFA alfanumérico */
    private String generateAlphanumericCode(int length) {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < length; i++) {
            code.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return code.toString();
    }
}
