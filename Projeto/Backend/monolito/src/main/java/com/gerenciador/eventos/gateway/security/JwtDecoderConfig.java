package com.gerenciador.eventos.gateway.security;

import java.net.MalformedURLException;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * {@link JwtDecoder} do gateway (issue #36, spec "Validação no gateway"): só {@code RS256}, chaves do JWKS do identity
 * ({@code IDENTITY_SERVICE_URL/.well-known/jwks.json}) e validação de {@code iss}/{@code exp}/{@code nbf}. O {@link JWKSource} é
 * montado À MÃO (o {@code withJwkSetUri} do Spring vem com cache de 5 min e sem limite de taxa):
 * <ul>
 *   <li><b>cache de 24 h</b> ({@code jwt.jwks.cache-ttl-ms}); um {@code kid} desconhecido força um refetch;</li>
 *   <li><b>limite de taxa</b>: no máximo UM fetch a cada 30 s ({@code jwt.jwks.min-refresh-interval-ms}, vale também para a expiração
 *       do cache e para as tentativas com o identity fora), então {@code kid} aleatório num bearer anônimo nunca vira um fetch por
 *       requisição (a validação falha: anônimo). O limitador é NOSSO ({@link BuscaPorIntervalo}, na camada do fetch): o
 *       {@code RateLimitedJWKSetSource} do Nimbus deixou passar DUAS buscas por janela (medido no JwtDecoderConfigTest), e o spec exige no máximo uma;</li>
 *   <li><b>tolerância a queda</b> ({@code jwt.jwks.outage-tolerance-ms}, 24 h): identity fora não derruba a validação enquanto a
 *       chave conhecida estiver no cache;</li>
 *   <li>fetch com connect 2 s / read 3 s: identity fora nunca prende a thread além disso.</li>
 * </ul>
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${identity.service.url:http://localhost:8084}") String identityUrl,
            @Value("${jwt.issuer:gerenciador-identity}") String issuer,
            @Value("${jwt.jwks.cache-ttl-ms:86400000}") long cacheTtlMs,
            @Value("${jwt.jwks.min-refresh-interval-ms:30000}") long minRefreshIntervalMs,
            @Value("${jwt.jwks.outage-tolerance-ms:86400000}") long outageToleranceMs) throws MalformedURLException {
        JWKSource<SecurityContext> jwks = jwkSource(new URL(identityUrl + "/.well-known/jwks.json"),
                Duration.ofMillis(cacheTtlMs), Duration.ofMillis(minRefreshIntervalMs), Duration.ofMillis(outageToleranceMs));
        return decoder(jwks, issuer);
    }

    /** Cache + limite de taxa + tolerância a queda sobre o JWKS remoto (público para o teste da fonte de chaves). */
    public static JWKSource<SecurityContext> jwkSource(URL jwksUrl, Duration cacheTtl, Duration minRefreshInterval,
            Duration outageTolerance) {
        ResourceRetriever busca = new BuscaPorIntervalo(new DefaultResourceRetriever(2000, 3000, 51_200), minRefreshInterval);
        return JWKSourceBuilder.<SecurityContext>create(jwksUrl, busca)
                .cache(cacheTtl.toMillis(), 5_000)
                .refreshAheadCache(false) // sem thread de refresh antecipado: toda busca nasce de uma validação
                .rateLimited(false) // o limite é o da BuscaPorIntervalo (estrito)
                .outageTolerant(outageTolerance.toMillis())
                .build();
    }

    /** Decoder que aceita SÓ RS256 (HS256/none ficam sem chave candidata) e valida {@code iss}, {@code exp} e {@code nbf}. */
    public static JwtDecoder decoder(JWKSource<SecurityContext> jwks, String issuer) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwks));
        // exp/nbf/iss são do validador do Spring abaixo (o do Nimbus duplicaria a checagem com outra tolerância)
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    /**
     * Deixa passar no máximo UMA busca por intervalo (a primeira passa; as seguintes dentro da janela falham na hora com
     * {@link IOException}, sem rede). A tentativa conta mesmo que a busca falhe, então o identity fora também não é martelado. A falha
     * vira {@code KeySourceException} no Nimbus; a tolerância a queda devolve então o conjunto de chaves que já se conhecia.
     */
    private static final class BuscaPorIntervalo implements ResourceRetriever {
        private final ResourceRetriever busca;
        private final long intervaloNanos;
        private long proximaPermitida; // System.nanoTime(); só vale se jaBuscou
        private boolean jaBuscou;

        BuscaPorIntervalo(ResourceRetriever busca, Duration intervalo) {
            this.busca = busca;
            this.intervaloNanos = intervalo.toNanos();
        }

        @Override
        public Resource retrieveResource(URL url) throws IOException {
            synchronized (this) {
                long agora = System.nanoTime();
                if (jaBuscou && agora - proximaPermitida < 0) {
                    throw new IOException("JWKS: nova busca antes do intervalo mínimo");
                }
                jaBuscou = true;
                proximaPermitida = agora + intervaloNanos;
            }
            return busca.retrieveResource(url);
        }
    }
}
