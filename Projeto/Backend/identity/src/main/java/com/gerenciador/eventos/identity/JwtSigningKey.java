package com.gerenciador.eventos.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

/**
 * Chave de assinatura do JWT (issue #36): RSA 2048, {@code kid} = thumbprint SHA-256 da JWK pública (RFC 7638). Gerada no 1º boot
 * e persistida como JWK (privada + pública) em {@code jwt.signing-key-path} ({@code JWT_SIGNING_KEY_PATH}; volume nomeado no
 * compose), com permissão 600 quando o sistema de arquivos suporta. Nos boots seguintes a MESMA chave é relida: um
 * {@code docker restart}/rebuild não invalida token nenhum.
 *
 * <p><b>Arquivo ilegível ou corrompido = falha no boot</b> ({@link IllegalStateException}): nunca regera por cima, senão todo token
 * válido sumiria em silêncio. Rotação = apagar o arquivo e reiniciar. A gravação é atômica e sem sobrescrever (arquivo temporário completo no mesmo diretório publicado por
 * {@code link(2)}, que falha se o destino já existe): duas instâncias subindo juntas acabam com a mesma chave (quem perde a corrida relê a do
 * vencedor).</p>
 */
@Component
public class JwtSigningKey {

    private static final Logger log = LoggerFactory.getLogger(JwtSigningKey.class);

    private final RSAKey key;

    public JwtSigningKey(@Value("${jwt.signing-key-path:/var/lib/identity/jwt-signing-key.json}") String path) {
        this.key = carregarOuGerar(Path.of(path));
    }

    /** A chave com a parte privada (só o {@link JwtIssuer} a usa). */
    RSAKey chave() {
        return key;
    }

    public String kid() {
        return key.getKeyID();
    }

    /** O JWKS publicável: SÓ a parte pública da chave. */
    public String jwksJson() {
        return new JWKSet(key.toPublicJWK()).toString(true);
    }

    private static RSAKey carregarOuGerar(Path arquivo) {
        if (Files.exists(arquivo)) {
            return ler(arquivo);
        }
        RSAKey nova = gerar();
        try {
            gravar(arquivo, nova);
            log.info("[identity] chave de assinatura do JWT gerada (kid={}) em {}", nova.getKeyID(), arquivo);
            return nova;
        } catch (FileAlreadyExistsException perdeuACorrida) {
            return ler(arquivo);
        } catch (IOException ex) {
            throw new IllegalStateException("Não consegui gravar a chave de assinatura do JWT em " + arquivo
                    + " (JWT_SIGNING_KEY_PATH; o volume/diretório precisa ser gravável): " + ex, ex);
        }
    }

    private static RSAKey gerar() {
        try {
            return new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint(true).generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Falha ao gerar a chave RSA do JWT", ex);
        }
    }

    private static RSAKey ler(Path arquivo) {
        try {
            RSAKey lida = RSAKey.parse(Files.readString(arquivo, StandardCharsets.UTF_8));
            if (!lida.isPrivate() || lida.getKeyID() == null || lida.size() < 2048) {
                throw new IllegalStateException("sem parte privada, sem kid ou menor que RSA 2048");
            }
            return lida;
        } catch (Exception ex) {
            // Qualquer falha (I/O, JSON ruim, JWK incompleta): NÃO regera. A mensagem não traz o conteúdo do arquivo.
            throw new IllegalStateException("Chave de assinatura do JWT ilegível ou corrompida em " + arquivo
                    + " (" + ex.getClass().getSimpleName() + "). Não vou gerar outra por cima: os tokens válidos deixariam de "
                    + "valer. Restaure o arquivo, ou apague-o conscientemente (rotação: todo token vira 401) e reinicie.", ex);
        }
    }

    /**
     * Publica o temporário (já completo) em {@code destino} SEM sobrescrever: {@code link(2)} falha atomicamente com {@code EEXIST}
     * ({@link FileAlreadyExistsException}) se outro boot já publicou, e quem perde relê a chave do vencedor. Um
     * {@code Files.move(ATOMIC_MOVE)} NÃO serve: no Linux é {@code rename(2)} e substitui o destino em silêncio. Se o sistema de
     * arquivos não suporta hard link, cai no {@code Files.move} sem opções (que também recusa sobrescrever).
     */
    private static void publicarSemSobrescrever(Path tmp, Path destino) throws IOException {
        try {
            Files.createLink(destino, tmp);
        } catch (FileAlreadyExistsException perdeu) {
            throw perdeu;
        } catch (UnsupportedOperationException | FileSystemException semHardLink) {
            Files.move(tmp, destino);
        }
    }

    private static void gravar(Path destino, RSAKey chave) throws IOException {
        Path dir = destino.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "jwt-signing-key", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException semPosix) {
                log.warn("[identity] sistema de arquivos sem permissões POSIX: a chave em {} fica com as permissões padrão", destino);
            }
            Files.writeString(tmp, chave.toJSONString(), StandardCharsets.UTF_8);
            publicarSemSobrescrever(tmp, destino);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
