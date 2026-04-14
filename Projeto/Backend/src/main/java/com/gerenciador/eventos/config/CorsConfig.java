package com.gerenciador.eventos.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuração CORS centralizada para TODOS os endpoints
 * Lê origins do .env via CORS_ALLOWED_ORIGINS
 * 
 * Aplica-se automaticamente a:
 * - /api/users/** (usuários, login, registro, etc)
 * - /api/events/** (eventos)
 * - /api/wallet/** (carteira)
 * - Qualquer outro endpoint /api/**
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${CORS_ALLOWED_ORIGINS:http://localhost:8088,http://localhost:3000}")
    private String corsAllowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = corsAllowedOrigins.split(",");
        
        // Aplicar CORS a TODOS os endpoints /api/**
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization", "Content-Type")
                .allowCredentials(true)
                .maxAge(3600);
    }
}