package com.gerenciador.eventos.Controller;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Controller para validar tokens do Google reCAPTCHA v3
 * Endpoint: POST /api/users/verify-recaptcha
 * CORS: Configurado em CorsConfig.java
 * 
 * Valida tokens reais com Google reCAPTCHA API
 */
@RestController
@RequestMapping("/api/users")
public class RecaptchaController {

    private static final Logger logger = Logger.getLogger(RecaptchaController.class.getName());

    @Value("${RECAPTCHA_SECRET_KEY:}")
    private String recaptchaSecretKey;

    private static final String RECAPTCHA_VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify";
    private static final double SCORE_THRESHOLD = 0.7;

    @PostMapping("/verify-recaptcha")
    public ResponseEntity<?> verifyRecaptcha(@RequestBody RecaptchaRequest request) {
        try {
            // Validar com Google reCAPTCHA API
            if (recaptchaSecretKey == null || recaptchaSecretKey.isEmpty()) {
                logger.warning("RECAPTCHA_SECRET_KEY não configurada no .env");
                return ResponseEntity.status(500).body(Map.of("success", false, "error", "reCAPTCHA não configurado"));
            }

            RestTemplate restTemplate = new RestTemplate();
            
            // Usar form-urlencoded em vez de JSON (Google requer esse format)
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("secret", recaptchaSecretKey);
            body.add("response", request.getToken());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

            logger.info("Enviando requisição para Google reCAPTCHA API...");
            
            Map<String, Object> response = restTemplate.postForObject(
                RECAPTCHA_VERIFY_URL,
                entity,
                Map.class
            );

            if (response == null) {
                logger.warning("Resposta nula do Google reCAPTCHA");
                return ResponseEntity.status(500).body(Map.of("success", false, "error", "Resposta inválida do reCAPTCHA"));
            }

            boolean success = Boolean.TRUE.equals(response.get("success"));
            double score = ((Number) response.getOrDefault("score", 0.0)).doubleValue();
            String action = (String) response.getOrDefault("action", "");

            logger.info(String.format("Resposta Google reCAPTCHA: Success=%b, Score=%.2f, Action=%s, ErrorCodes=%s", 
                success, score, action, response.getOrDefault("error-codes", "none")));

            // Verificar se o token é válido e a score é suficiente
            boolean isValid = success && score >= SCORE_THRESHOLD && action.equals(request.getAction());
            
            logger.info(String.format("Validação Final: isValid=%b (success=%b, score>=%.2f=%b, action_match=%b)", 
                isValid, success, SCORE_THRESHOLD, score >= SCORE_THRESHOLD, action.equals(request.getAction())));

            Map<String, Object> result = new HashMap<>();
            result.put("success", isValid);
            result.put("score", score);
            result.put("action", action);

            return ResponseEntity.ok(result);

        } catch (RestClientException e) {
            logger.log(Level.SEVERE, "Erro ao conectar ao reCAPTCHA", e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "Erro de conexão com reCAPTCHA"));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao validar reCAPTCHA", e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * DTO para requisição de verificação do reCAPTCHA
     */
    public static class RecaptchaRequest {
        private String token;
        private String action;

        public RecaptchaRequest() {}

        public RecaptchaRequest(String token, String action) {
            this.token = token;
            this.action = action;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }
    }
}
