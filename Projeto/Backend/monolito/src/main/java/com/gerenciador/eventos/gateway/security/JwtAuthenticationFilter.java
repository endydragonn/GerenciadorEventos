package com.gerenciador.eventos.gateway.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Valida o {@code Authorization: Bearer} do {@code /bff/**} SEM estado (issue #36): assinatura RS256 contra o JWKS do identity (cache
 * por {@code kid}, ver {@link JwtDecoderConfig}), {@code iss}, {@code exp} e {@code nbf}. Token válido com {@code uid} numérico e
 * {@code sub} vira um {@link JwtAuthenticationToken} (nome = e-mail do {@code sub}, {@code Jwt} disponível para {@link JwtAuthorization}).
 *
 * <p>Filtro próprio (e não {@code oauth2ResourceServer()}) de propósito: bearer inválido, vencido ou de outra chave numa rota PÚBLICA
 * segue como anônimo (200); o {@code oauth2ResourceServer()} devolveria 401 até em {@code permitAll}. Em rota protegida o resultado é o
 * mesmo de sempre, 401 do {@code authenticationEntryPoint}. Qualquer falha na validação (assinatura, claims, JWKS fora do ar, rate limit
 * do refetch) é "não autenticado": nunca 500 e nunca a mensagem do erro na resposta.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtDecoder decoder;

    public JwtAuthenticationFilter(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            autenticar(header.substring(BEARER.length()).trim());
        }
        filterChain.doFilter(request, response);
    }

    private void autenticar(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            if (JwtAuthorization.uid(jwt) != null && StringUtils.hasText(jwt.getSubject())) {
                SecurityContextHolder.getContext().setAuthentication(
                        new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("USER"), jwt.getSubject()));
            }
        } catch (RuntimeException ex) {
            // só a classe: a mensagem do erro pode ecoar parte do token
            log.debug("[gateway] bearer recusado ({})", ex.getClass().getSimpleName());
        }
    }
}
