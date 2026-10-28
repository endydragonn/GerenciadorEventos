package com.gerenciador.eventos.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Issue #133: a configuração de CORS é um único {@link CorsConfigurationSource}, com origens exatas lidas de
 * {@code CORS_ALLOWED_ORIGINS} (sem {@code *}, já que as credenciais ficam habilitadas).
 */
class CorsConfigTest {

    private static CorsConfiguration configDe(String origens, String caminho) {
        CorsConfigurationSource source = new CorsConfig().corsConfigurationSource(origens);
        return source.getCorsConfiguration(new MockHttpServletRequest("OPTIONS", caminho));
    }

    @Test
    void origens_viramListaExata_comTrimEDescarteDeVazio() {
        CorsConfiguration config = configDe(" http://localhost:8088 , ,http://localhost:3000,", "/bff/events");

        assertEquals(List.of("http://localhost:8088", "http://localhost:3000"), config.getAllowedOrigins());
    }

    @Test
    void origemForaDaLista_naoEPermitida_ePermitidaNaLista() {
        CorsConfiguration config = configDe("http://localhost:8088", "/bff/events");

        assertEquals("http://localhost:8088", config.checkOrigin("http://localhost:8088"));
        assertNull(config.checkOrigin("http://evil.example"));
    }

    @Test
    void naoUsaPatterns_eMantemCredenciais() {
        CorsConfiguration config = configDe("http://localhost:8088", "/bff/events");

        assertNull(config.getAllowedOriginPatterns());
        assertEquals(Boolean.TRUE, config.getAllowCredentials());
    }

    @Test
    void exposedHeaders_mantemAuthorizationContentTypeEIdempotencyKey() {
        CorsConfiguration config = configDe("http://localhost:8088", "/bff/event-wallets");

        assertTrue(config.getExposedHeaders().containsAll(List.of("Authorization", "Content-Type", "Idempotency-Key")),
                "exposedHeaders = " + config.getExposedHeaders());
    }

    @Test
    void cobreQualquerCaminho_inclusiveForaDeBff() {
        assertNotNull(configDe("http://localhost:8088", "/actuator/health"));
        assertNotNull(configDe("http://localhost:8088", "/bff/my-wallets/1"));
    }

    @Test
    void asterisco_naConfiguracao_eRecusadoNaSubida() {
        assertThrows(IllegalStateException.class, () -> new CorsConfig().corsConfigurationSource("*"));
        assertThrows(IllegalStateException.class,
                () -> new CorsConfig().corsConfigurationSource("http://localhost:8088, *"));
    }
}
