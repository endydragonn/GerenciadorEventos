package com.gerenciador.eventos.identity;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /.well-known/jwks.json} (issue #36): o JWKS com SÓ a chave pública de assinatura, que o gateway usa para validar o JWT
 * sem estado (cache por {@code kid}). Único endpoint isento do {@code X-Internal-Token} ({@link InternalTokenFilter}: só a URI exata).
 */
@RestController
public class JwksController {

    private final JwtSigningKey chave;

    public JwksController(JwtSigningKey chave) {
        this.chave = chave;
    }

    @GetMapping(value = InternalTokenFilter.JWKS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public String jwks() {
        return chave.jwksJson();
    }
}
