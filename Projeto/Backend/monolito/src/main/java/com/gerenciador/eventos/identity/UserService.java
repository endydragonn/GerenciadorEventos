package com.gerenciador.eventos.identity;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.identity.api.User;
import com.gerenciador.eventos.identity.api.UserApi;

/**
 * Service - lógica de negócio e validações
 * Coordena as operações entre Controller e Repository
 */
@Service
public class UserService implements UserApi {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private IdentityNotifications notifications;

    /**
     * Criar novo usuário com validações
     */
    public User createUser(User user) {
        validateRequiredFields(user);
        validateDuplicates(user);
        user.setIsActive(true);
        // Hash da senha antes de salvar
        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }
        return userRepository.save(user);
    }

    /**
     * Criar usuário completo (método helper)
     */
    public User createNewUser(String name, String email, String password, String fone) {
        User user = new User(name, email, fone, password, null);
        return createUser(user);
    }

    /**
     * Verificar se usuário pode ser criado (sem lançar exceção)
     */
    public boolean canBeCreated(User user) {
        try {
            validateRequiredFields(user);
            validateDuplicates(user);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Obter erros de validação em formato String
     */
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

    /**
     * Buscar usuário por ID
     */
    public User findById(Long id) {
        User user = userRepository.findById(id);
        if (user == null) {
            throw new RuntimeException("Usuário não encontrado com ID: " + id);
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

    /**
     * Buscar usuário por email
     */
    public User findByEmail(String email) {
        User user = userRepository.findByEmail(email);
        if (user == null) {
            throw new RuntimeException("Usuário não encontrado com email: " + email);
        }
        return user;
    }

    /**
     * Atualizar usuário
     */
    public User updateUser(User user) {
        if (user.getId() == null) {
            throw new IllegalArgumentException("ID do usuário não pode ser nulo");
        }
        validateRequiredFields(user);
        return userRepository.update(user);
    }

    /**
     * Desativar usuário (soft delete)
     */
    public void deactivateUser(Long id) {
        userRepository.softDelete(id);
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

    public User login(String email, String password) {
        User user = userRepository.findByEmail(email);
        if (user != null && passwordEncoder.matches(password, user.getPassword())) {
            return user;
        }
        return null; // ou lançar exceção
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
        
        // Validar senha antiga
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new IllegalArgumentException("Senha atual incorreta");
        }
        
        // Validar nova senha
        if (newPassword == null || newPassword.trim().length() < 6) {
            throw new IllegalArgumentException("A nova senha deve ter no mínimo 6 caracteres");
        }
        
        // Hash da nova senha e atualizar
        String hashedPassword = passwordEncoder.encode(newPassword);
        user.setPassword(hashedPassword);
        userRepository.update(user);
        
        return true;
    }

    /**
     * Gerar e guardar código MFA alfanumérico para reset de senha
     */
    public String generatePasswordResetMfa(Long userId) {
        String mfaCode = generateAlphanumericCode(8);
        userRepository.setPasswordResetMfaCode(userId, mfaCode);
        return mfaCode;
    }

    /**
     * Gerar o código MFA de reset de senha e pedir o envio por e-mail: grava o código e appenda o evento
     * {@code PasswordResetMfaRequested} (event_store + outbox) na MESMA transação; quem envia é o serviço
     * {@code notification} (issue #32), então o monólito não toca em SMTP.
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

    /**
     * Validar código MFA para reset de senha
     */
    public boolean verifyPasswordResetMfaCode(Long userId, String mfaCode) {
        return userRepository.verifyPasswordResetMfaCode(userId, mfaCode);
    }

    /**
     * Alterar senha com MFA (sem exigir senha antiga)
     */
    public void changePasswordWithMfa(Long userId, String newPassword, String mfaCode) {
        // Validar MFA
        if (!verifyPasswordResetMfaCode(userId, mfaCode)) {
            throw new IllegalArgumentException("Código MFA inválido ou expirado");
        }

        User user = userRepository.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("Usuário não encontrado");
        }

        // Validar nova senha
        if (newPassword == null || newPassword.trim().length() < 6) {
            throw new IllegalArgumentException("A nova senha deve ter no mínimo 6 caracteres");
        }

        // Hash da nova senha e atualizar
        String hashedPassword = passwordEncoder.encode(newPassword);
        user.setPassword(hashedPassword);
        userRepository.update(user);

        // Limpar MFA após uso
        userRepository.clearPasswordResetMfaCode(userId);
    }

    /**
     * Gerar código MFA alfanumérico
     */
    private String generateAlphanumericCode(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz";
        StringBuilder code = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < length; i++) {
            code.append(chars.charAt(random.nextInt(chars.length())));
        }
        return code.toString();
    }


}

