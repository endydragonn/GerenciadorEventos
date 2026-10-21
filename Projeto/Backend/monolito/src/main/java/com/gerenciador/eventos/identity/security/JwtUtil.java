package com.gerenciador.eventos.identity.security;

import java.security.Key;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.gerenciador.eventos.identity.api.TokenApi;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtUtil implements TokenApi {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expirationSeconds}")
    private long expirationSeconds;

    private Key getSigningKey() {
        // Usa a secret diretamente (deve ter pelo menos 256 bits / 32 bytes para HS256)
        byte[] keyBytes = secret.getBytes();
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String subject) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expirationSeconds * 1000);
        return Jwts.builder()
                .setSubject(subject)
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String generateToken(String subject, Boolean isHost) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expirationSeconds * 1000);
        return Jwts.builder()
                .setSubject(subject)
                .claim("isHost", isHost != null ? isHost : false)
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    public String extractSubject(String token) {
        return extractAllClaims(token).getSubject();
    }

    public Boolean extractIsHost(String token) {
        Claims claims = extractAllClaims(token);
        Object isHostClaim = claims.get("isHost");
        if (isHostClaim instanceof Boolean b) {
            return b;
        }
        if (isHostClaim instanceof Number n) {
            return n.longValue() != 0;
        }
        if (isHostClaim instanceof String s) {
            // Use equalsIgnoreCase to avoid temporary string conversion
            return "true".equalsIgnoreCase(s);
        }
        if (isHostClaim != null) {
            return "true".equalsIgnoreCase(isHostClaim.toString());
        }
        return false;
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
