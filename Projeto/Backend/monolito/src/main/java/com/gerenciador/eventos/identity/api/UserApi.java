package com.gerenciador.eventos.identity.api;

/**
 * Interface pública do módulo {@code identity} para usuários (issue #23). Outros módulos e o
 * {@code gateway} só chamam usuários por aqui, nunca por {@code UserService}/{@code UserRepository}.
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

    /**
     * Gera o código MFA de reset de senha e o envia por e-mail ao usuário (módulo {@code notification}).
     * Lança {@link RuntimeException} se o usuário não existe ou o envio falha. Devolve o usuário (o gateway
     * usa o e-mail na resposta).
     */
    User requestPasswordResetMfa(Long userId);

    boolean verifyPasswordResetMfaCode(Long userId, String mfaCode);

    void changePasswordWithMfa(Long userId, String newPassword, String mfaCode);

    /** Existe usuário com esse id? Não lança se não existir (diferente de {@link #findById}). */
    boolean exists(Long userId);

    /**
     * Nomes dos usuários ATIVOS ({@code isactive = true}) entre os ids pedidos, numa consulta só (issue #35: o gateway compõe
     * {@code creator_name}). Id inexistente ou de conta desativada fica fora do mapa.
     */
    java.util.Map<Long, String> findNames(java.util.Collection<Long> ids);
}
