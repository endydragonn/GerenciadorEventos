package com.gerenciador.eventos.identityclient.api;

/**
 * Verificação do reCAPTCHA v3 (o {@code RECAPTCHA_SECRET_KEY} é só do identity). Devolve o mesmo status e corpo que o
 * {@code POST /bff/users/verify-recaptcha} dá hoje; só rede/timeout/5xx genérico do serviço viram {@link IdentityUnavailableException}.
 */
public interface RecaptchaApi {

    RecaptchaResponse verify(String token, String action);
}
