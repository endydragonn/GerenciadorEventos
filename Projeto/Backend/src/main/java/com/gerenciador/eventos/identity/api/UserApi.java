package com.gerenciador.eventos.identity.api;

/**
 * Interface pública do módulo {@code identity} para usuários (issue #23). Outros módulos e o
 * {@code bff} só chamam usuários por aqui, nunca por {@code UserService}/{@code UserRepository}.
 */
public interface UserApi {

    User createUser(User user);

    User findById(Long id);

    User findByEmail(String email);

    User updateUser(User user);

    void deactivateUser(Long id);

    String getValidationErrors(User user);

    /** Usuário autenticado ou {@code null} se e-mail/senha não conferem. */
    User login(String email, String password);

    boolean changePassword(Long userId, String oldPassword, String newPassword);

    String generatePasswordResetMfa(Long userId);

    boolean verifyPasswordResetMfaCode(Long userId, String mfaCode);

    void changePasswordWithMfa(Long userId, String newPassword, String mfaCode);

    /** Existe usuário com esse id? Não lança se não existir (diferente de {@link #findById}). */
    boolean exists(Long userId);
}
