package com.gerenciador.eventos.identity;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.gerenciador.eventos.identityclient.api.RecaptchaApi;
import com.gerenciador.eventos.identityclient.api.RecaptchaResponse;

/**
 * Verificação do reCAPTCHA v3 (issue #36: o {@code RECAPTCHA_SECRET_KEY} é só deste serviço). É o código que era do
 * {@code BffUserController}, com o MESMO limiar de score ({@code 0.7}) e a MESMA resposta: {@link RecaptchaResponse#status()} 200
 * com {@code success/score/action}, ou 500 com {@code success=false} e {@code error} (sem chave, Google fora ou lento, resposta
 * inválida). A URL do Google é property ({@code recaptcha.verify-url}) para o teste apontar para um fake local. O
 * {@link RestTemplate} tem timeout (conectar 2 s, ler 5 s): Google lento não prende a thread do identity.
 */
@Service
public class RecaptchaService implements RecaptchaApi {

    private static final Logger log = LoggerFactory.getLogger(RecaptchaService.class);
    private static final String ERRO_GENERICO = "Erro interno ao processar a solicitação";

    private final String secretKey;
    private final String verifyUrl;
    private final double scoreThreshold;
    private final RestTemplate http;

    public RecaptchaService(@Value("${recaptcha.secret-key:}") String secretKey,
            @Value("${recaptcha.verify-url}") String verifyUrl,
            @Value("${recaptcha.score-threshold:0.7}") double scoreThreshold) {
        this.secretKey = secretKey;
        this.verifyUrl = verifyUrl;
        this.scoreThreshold = scoreThreshold;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        this.http = new RestTemplate(factory);
    }

    @Override
    public RecaptchaResponse verify(String token, String action) {
        try {
            if (secretKey == null || secretKey.isEmpty()) {
                log.warn("RECAPTCHA_SECRET_KEY não configurada");
                return erro("reCAPTCHA não configurado");
            }
            // Google exige form-urlencoded, não JSON
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("secret", secretKey);
            body.add("response", token);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = http.postForObject(verifyUrl, new HttpEntity<>(body, headers), Map.class);
            if (response == null) {
                log.warn("Resposta nula do Google reCAPTCHA");
                return erro("Resposta inválida do reCAPTCHA");
            }
            boolean success = Boolean.TRUE.equals(response.get("success"));
            double score = ((Number) response.getOrDefault("score", 0.0)).doubleValue();
            String respondida = (String) response.getOrDefault("action", "");
            log.info("Resposta Google reCAPTCHA: Success={}, Score={}, Action={}, ErrorCodes={}", success, score, respondida,
                    response.getOrDefault("error-codes", "none"));

            boolean isValid = success && score >= scoreThreshold && respondida.equals(action);
            Map<String, Object> result = new HashMap<>();
            result.put("success", isValid);
            result.put("score", score);
            result.put("action", respondida);
            return new RecaptchaResponse(200, result);
        } catch (RestClientException e) {
            log.error("Erro ao conectar ao reCAPTCHA: {}", e.toString());
            return erro("Erro de conexão com reCAPTCHA");
        } catch (Exception e) {
            log.error("Erro ao validar reCAPTCHA", e);
            return erro(ERRO_GENERICO);
        }
    }

    private static RecaptchaResponse erro(String mensagem) {
        return new RecaptchaResponse(500, Map.of("success", false, "error", mensagem));
    }
}
