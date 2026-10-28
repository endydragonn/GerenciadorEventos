package com.gerenciador.eventos.shared;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Único ponto de configuração CORS do backend (issue #133). O filtro do Spring Security
 * ({@code SecurityConfig}, via {@code .cors(...)}) consome este bean e responde o preflight antes
 * de qualquer controller; não existe outro mapping CORS.
 *
 * <p>Origens lidas de {@code CORS_ALLOWED_ORIGINS} (separadas por vírgula, comparadas de forma
 * exata). Como as credenciais ficam habilitadas, {@code *} é recusado na subida: origem fora da
 * lista não recebe {@code Access-Control-Allow-Origin}.</p>
 *
 * <p>O mapping é {@code /**} (e não só {@code /bff/**}): o filtro de segurança também atende o
 * preflight de rotas fora do gateway, como {@code /actuator/health}.</p>
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${CORS_ALLOWED_ORIGINS:http://localhost:8088,http://localhost:3000}") String corsAllowedOrigins) {
        List<String> origens = Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(o -> !o.isEmpty())
                .toList();
        if (origens.contains("*")) {
            throw new IllegalStateException(
                    "CORS_ALLOWED_ORIGINS não pode conter '*' (as credenciais estão habilitadas); liste as origens exatas");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origens);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "Retry-After"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
