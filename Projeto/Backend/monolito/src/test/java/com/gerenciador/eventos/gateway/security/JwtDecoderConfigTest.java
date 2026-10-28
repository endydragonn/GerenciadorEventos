package com.gerenciador.eventos.gateway.security;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.gerenciador.eventos.TestSupport.FakeIdentityServer;

/**
 * A fonte de chaves do gateway (issue #36, spec "Validação no gateway"; testes 11 e 7 do spec) contra o {@link FakeIdentityServer},
 * sem Spring nem banco: (1) {@code kid} desconhecido NÃO vira um fetch por requisição (limite de taxa); (2) o identity fora do ar não
 * derruba a validação enquanto a chave conhecida está no cache (tolerância a queda, aqui com TTL de 500 ms para não esperar minutos);
 * (3) identity fora com cache vazio: o token não valida, rápido (timeout de 3 s), sem travar.
 */
public class JwtDecoderConfigTest {

    private final FakeIdentityServer fake = FakeIdentityServer.get();

    @AfterEach
    void religar() {
        fake.foraDoAr(false);
    }

    private JwtDecoder decoder(Duration ttl, Duration intervalo, Duration tolerancia) throws Exception {
        return JwtDecoderConfig.decoder(JwtDecoderConfig.jwkSource(new URL(fake.url() + "/.well-known/jwks.json"), ttl,
                intervalo, tolerancia), FakeIdentityServer.ISSUER);
    }

    private String tokenBom() {
        return fake.tokenCustom("jwks@example.com", 42L, true, FakeIdentityServer.ISSUER, Instant.now().plusSeconds(3600));
    }

    @Test
    void tokenValidoTemUidEIsHostLidosDosClaims() throws Exception {
        Jwt jwt = decoder(Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofHours(24)).decode(tokenBom());
        assertEquals(42L, ((Number) jwt.getClaim("uid")).longValue());
        assertEquals(Boolean.TRUE, jwt.getClaim("isHost"));
        assertEquals("jwks@example.com", jwt.getSubject());
    }

    @Test
    void kidDesconhecido100Vezes_noMaximoUmFetchPorIntervalo() throws Exception {
        JwtDecoder decoder = decoder(Duration.ofHours(24), Duration.ofSeconds(2), Duration.ofHours(24));
        decoder.decode(tokenBom()); // 1º fetch (cache frio)
        Thread.sleep(2300); // passou o intervalo do limite: o 1º kid desconhecido pode buscar, os 99 seguintes não
        int base = fake.jwksHits();
        String desconhecido = fake.tokenComKidDesconhecido();

        for (int i = 0; i < 100; i++) {
            assertThrows(JwtException.class, () -> decoder.decode(desconhecido));
        }

        int fetches = fake.jwksHits() - base;
        assertEquals(1, fetches, "100 bearers com kid desconhecido: UM fetch ao identity no intervalo (os demais caem no limite)");
        decoder.decode(tokenBom()); // a chave conhecida segue valendo (o cache não foi envenenado)
    }

    @Test
    void identityForaComAChaveEmCache_aValidacaoSegue() throws Exception {
        JwtDecoder decoder = decoder(Duration.ofMillis(500), Duration.ofMillis(300), Duration.ofHours(24));
        String bom = tokenBom();
        decoder.decode(bom); // esquenta o cache
        fake.foraDoAr(true);
        Thread.sleep(1200); // o TTL do cache venceu: o refetch falha e a tolerância devolve a chave que já se conhecia

        assertEquals(42L, ((Number) decoder.decode(bom).getClaim("uid")).longValue());
    }

    @Test
    void identityForaComCacheVazio_tokenNaoValidaRapidoESemTravar() throws Exception {
        JwtDecoder decoder = decoder(Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofHours(24));
        fake.foraDoAr(true);
        String bom = tokenBom();

        long ini = System.nanoTime();
        assertThrows(JwtException.class, () -> decoder.decode(bom));

        assertTrue(Duration.ofNanos(System.nanoTime() - ini).toSeconds() < 5, "a falha tem de vir dentro do timeout do fetch");
    }
}
