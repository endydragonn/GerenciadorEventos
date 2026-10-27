package com.gerenciador.eventos.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Emite o JWT do login e do cadastro (issue #36): RS256, header {@code kid} = thumbprint da chave. Claims: {@code sub} = e-mail,
 * {@code uid} (id do usuário), {@code isHost} (lido do banco NA EMISSÃO por quem chama), {@code iss}, {@code iat}, {@code exp}
 * ({@code JWT_EXPIRATION_SECONDS}). O gateway só valida (JWKS), nada assina fora daqui. Validação sem estado: conta
 * desativada, troca de senha ou mudança de {@code host} só valem para tokens NOVOS.
 */
@Component
public class JwtIssuer {

    private final JwtSigningKey chave;
    private final RSASSASigner signer;
    private final Duration validade;
    private final String issuer;

    public JwtIssuer(JwtSigningKey chave, @Value("${jwt.expiration-seconds:36000}") long expirationSeconds,
            @Value("${jwt.issuer:gerenciador-identity}") String issuer) {
        this.chave = chave;
        try {
            this.signer = new RSASSASigner(chave.chave());
        } catch (JOSEException ex) {
            throw new IllegalStateException("Chave de assinatura do JWT inválida", ex);
        }
        this.validade = Duration.ofSeconds(expirationSeconds);
        this.issuer = issuer;
    }

    public String emitir(String email, long userId, boolean isHost) {
        Instant agora = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(email)
                .issuer(issuer)
                .claim("uid", userId)
                .claim("isHost", isHost)
                .issueTime(Date.from(agora))
                .expirationTime(Date.from(agora.plus(validade)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(chave.kid()).type(JOSEObjectType.JWT).build(),
                claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException ex) {
            throw new IllegalStateException("Falha ao assinar o JWT", ex);
        }
        return jwt.serialize();
    }
}
