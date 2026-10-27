package com.gerenciador.eventos.gateway.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Autorização pelos claims do JWT assinado pelo identity (issue #36, validação sem estado): {@code uid} é o id do usuário e
 * {@code isHost} o papel NO MOMENTO da emissão. Nenhuma consulta ao banco nem ao identity (some o {@code LOWER(email)} por reserva da
 * #57). Consequência aceita: promover/rebaixar host só vale para tokens novos.
 */
@Component
public class JwtAuthorization implements HostAuthorizationApi {

    static final String CLAIM_UID = "uid";
    static final String CLAIM_IS_HOST = "isHost";

    @Override
    public void ensureUserIsHost() {
        if (!Boolean.TRUE.equals(jwtAtual().getClaim(CLAIM_IS_HOST))) {
            throw new IllegalArgumentException("Apenas usuários com status de host podem criar eventos");
        }
    }

    @Override
    public Long currentUserId() {
        Long uid = uid(jwtAtual());
        if (uid == null) {
            throw new IllegalArgumentException("Nenhum usuário autenticado");
        }
        return uid;
    }

    /** {@code uid} numérico do token, ou {@code null} se ausente/não numérico (o filtro trata como não autenticado). */
    static Long uid(Jwt jwt) {
        return jwt.getClaim(CLAIM_UID) instanceof Number n ? n.longValue() : null;
    }

    private static Jwt jwtAtual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            return token.getToken();
        }
        throw new IllegalArgumentException("Nenhum usuário autenticado");
    }
}
