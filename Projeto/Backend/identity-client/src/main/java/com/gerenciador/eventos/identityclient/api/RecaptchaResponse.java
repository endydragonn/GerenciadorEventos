package com.gerenciador.eventos.identityclient.api;

import java.util.Map;

/**
 * Resposta do reCAPTCHA: {@code status} HTTP (200 com {@code success/score/action}; 500 com {@code success=false} e {@code error}
 * quando a chave não está configurada, o Google não responde ou dá erro) e o corpo JSON.
 */
public record RecaptchaResponse(int status, Map<String, Object> body) {
}
