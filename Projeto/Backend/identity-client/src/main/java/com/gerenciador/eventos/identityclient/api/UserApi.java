package com.gerenciador.eventos.identityclient.api;

import java.util.Collection;
import java.util.Map;

/**
 * Contrato público de usuários do {@code identity} (issue #23; desde a #36 na lib {@code identity-client}, implementado pelo
 * {@code IdentityClient} HTTP e, enquanto o identity não sai do processo, pelo {@code UserService} do monólito). O
 * {@code gateway} só chama usuários por aqui.
 *
 * <p>Não encontrado: {@link #findById}, {@link #findByEmail} e {@link #requestPasswordResetMfa} lançam
 * {@link IdentityNaoEncontradoException}; o serviço fora do ar lança {@link IdentityUnavailableException} (só no cliente HTTP).</p>
 */
public interface UserApi {

    User createUser(User user);

    User findById(Long id);

    User findByEmail(String email);

    User updateUser(User user);

    void deactivateUser(Long id);

    String getValidationErrors(User user);

    /**
     * Usuário autenticado + token JWT emitido pelo identity, ou {@code null} se e-mail/senha não conferem (o token é emitido do
     * lado do identity: o gateway não assina nada).
     */
    LoginResult login(String email, String password);

    boolean changePassword(Long userId, String oldPassword, String newPassword);

    /**
     * Gera o código MFA de reset de senha e o envia por e-mail ao usuário (módulo {@code notification}).
     * Lança {@link RuntimeException} se o usuário não existe ou o envio falha. Devolve o usuário (o gateway
     * usa o e-mail na resposta).
     */
    User requestPasswordResetMfa(Long userId);

    /** {@code false} = código inválido ou expirado. */
    boolean verifyPasswordResetMfaCode(Long userId, String mfaCode);

    void changePasswordWithMfa(Long userId, String newPassword, String mfaCode);

    /** Existe usuário com esse id? Não lança se não existir (diferente de {@link #findById}). */
    boolean exists(Long userId);

    /**
     * Nomes dos usuários ATIVOS ({@code isactive = true}) entre os ids pedidos, numa consulta só (issue #35: o gateway compõe
     * {@code creator_name}). Id inexistente ou de conta desativada fica fora do mapa.
     */
    Map<Long, String> findNames(Collection<Long> ids);
}
