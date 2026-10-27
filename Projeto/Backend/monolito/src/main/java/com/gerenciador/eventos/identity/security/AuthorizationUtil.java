package com.gerenciador.eventos.identity.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.gerenciador.eventos.identity.api.HostAuthorizationApi;
import com.gerenciador.eventos.identityclient.api.User;
import com.gerenciador.eventos.identity.UserRepository;

/**
 * Utilitário para verificação de autorização baseada em roles
 * Verifica permissões do usuário autenticado para ações específicas
 */
@Component
public class AuthorizationUtil implements HostAuthorizationApi {

    @Autowired
    private UserRepository userRepository;

    /**
     * Verifica se o usuário autenticado é um host
     * @return true se o usuário é host, false caso contrário
     * @throws IllegalArgumentException se nenhum usuário está autenticado
     */
    public Boolean isCurrentUserHost() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalArgumentException("Nenhum usuário autenticado");
        }

        String userEmail = auth.getName();
        User user = userRepository.findByEmail(userEmail);
        
        if (user == null) {
            throw new IllegalArgumentException("Usuário não encontrado: " + userEmail);
        }

        return user.getIsHost() != null && user.getIsHost();
    }

    /**
     * Verifica se o usuário autenticado pode criar eventos
     * Apenas hosts podem criar eventos
     * @throws IllegalArgumentException se o usuário não é host
     */
    public void ensureUserIsHost() {
        if (!isCurrentUserHost()) {
            throw new IllegalArgumentException("Apenas usuários com status de host podem criar eventos");
        }
    }

    @Override
    public Long currentUserId() {
        return getCurrentUser().getId();
    }

    /**
     * Extrai o email do usuário autenticado
     * @return email do usuário autenticado
     * @throws IllegalArgumentException se nenhum usuário está autenticado
     */
    public String getCurrentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalArgumentException("Nenhum usuário autenticado");
        }
        return auth.getName();
    }

    /**
     * Obtém o usuário autenticado completo
     * @return User object do usuário autenticado
     * @throws IllegalArgumentException se nenhum usuário está autenticado ou não encontrado
     */
    public User getCurrentUser() {
        String userEmail = getCurrentUserEmail();
        User user = userRepository.findByEmail(userEmail);
        
        if (user == null) {
            throw new IllegalArgumentException("Usuário não encontrado: " + userEmail);
        }

        return user;
    }
}
