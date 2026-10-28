package com.gerenciador.eventos.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.gerenciador.eventos.gateway.security.HostAuthorizationApi;

/**
 * Recusa de quem pede o recurso de OUTRO usuário (IDOR; issues #57 e #166): o id do path/corpo tem de ser o dono do token (claim
 * {@code uid}). O corpo é fixo e o 403 vale mesmo para id que não existe, para a negação não revelar se a outra conta existe.
 */
final class AcessoNegado {

    static final ResponseEntity<String> NEGADO = ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body("Acesso negado: o usuário informado não é o usuário autenticado");

    private AcessoNegado() {
    }

    /**
     * {@code null} quando {@code userId} é o dono do token (pode seguir); senão a resposta de recusa: 403 se difere, 401 se o token
     * não traz usuário. Deve rodar ANTES de qualquer chamada a outro serviço.
     */
    static ResponseEntity<String> barrarSeNaoDono(HostAuthorizationApi authorization, Long userId) {
        Long doToken;
        try {
            doToken = authorization.currentUserId();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Usuário autenticado não encontrado");
        }
        return doToken.equals(userId) ? null : NEGADO;
    }
}
