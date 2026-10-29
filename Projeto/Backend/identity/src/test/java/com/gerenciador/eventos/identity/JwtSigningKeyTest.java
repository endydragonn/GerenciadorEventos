package com.gerenciador.eventos.identity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Chave de assinatura do JWT (issue #36), sem Spring e sem banco: a mesma chave sobrevive ao "restart" (novo
 * {@link JwtSigningKey} no mesmo caminho) e o token antigo continua válido; arquivo corrompido ou incompleto derruba o boot e NUNCA é
 * regerado por cima; o {@code kid} é o thumbprint RFC 7638 e o JWKS só carrega a parte pública.
 */
public class JwtSigningKeyTest {

    @TempDir
    Path dir;

    @Test
    void restartComOMesmoCaminhoReleAMesmaChaveEOTokenAntigoValidaNoJwksDoSegundo() throws Exception {
        Path arquivo = dir.resolve("sub/jwt-signing-key.json");
        JwtSigningKey primeira = new JwtSigningKey(arquivo.toString());
        String token = new JwtIssuer(primeira, 3600, "gerenciador-identity").emitir("a@b.com", 7, true);
        assertTrue(Files.exists(arquivo), "1º boot gera e persiste (criando o diretório)");

        JwtSigningKey segunda = new JwtSigningKey(arquivo.toString());

        assertEquals(primeira.kid(), segunda.kid(), "mesma chave depois do restart");
        SignedJWT jwt = SignedJWT.parse(token);
        JWK publica = JWKSet.parse(segunda.jwksJson()).getKeyByKeyId(jwt.getHeader().getKeyID());
        assertNotNull(publica, "o kid do token antigo está no JWKS do segundo boot");
        assertTrue(jwt.verify(new RSASSAVerifier(publica.toRSAKey())), "token do primeiro valida na chave do segundo");
        assertEquals(7L, jwt.getJWTClaimsSet().getLongClaim("uid"));
        assertEquals(Boolean.TRUE, jwt.getJWTClaimsSet().getBooleanClaim("isHost"));
    }

    @Test
    void kidEOThumbprintRfc7638EOJwksNaoTemParteprivada() throws Exception {
        JwtSigningKey chave = new JwtSigningKey(dir.resolve("k.json").toString());

        JWK publica = JWKSet.parse(chave.jwksJson()).getKeys().get(0);
        assertEquals(publica.computeThumbprint().toString(), chave.kid());
        assertFalse(publica.isPrivate());
        assertEquals(2048, publica.toRSAKey().size());
        assertFalse(chave.jwksJson().contains("\"d\""), "sem o expoente privado");
        assertEquals("RS256", publica.getAlgorithm().getName());
    }

    @Test
    void arquivoCorrompidoFalhaNoBootENaoRegeraPorCima() throws Exception {
        Path arquivo = dir.resolve("k.json");
        Files.writeString(arquivo, "{isto não é uma JWK");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new JwtSigningKey(arquivo.toString()));

        assertTrue(ex.getMessage().contains("corrompida"), ex.getMessage());
        assertEquals("{isto não é uma JWK", Files.readString(arquivo), "o arquivo ruim NÃO foi sobrescrito");
    }

    @Test
    void arquivoVazioOuSoComAPartePublicaTambemFalha() throws Exception {
        Path vazio = dir.resolve("vazio.json");
        Files.writeString(vazio, "");
        assertThrows(IllegalStateException.class, () -> new JwtSigningKey(vazio.toString()));

        Path origem = dir.resolve("origem.json");
        String publicaSo = JWKSet.parse(new JwtSigningKey(origem.toString()).jwksJson()).getKeys().get(0).toJSONString();
        Path soPublica = dir.resolve("publica.json");
        Files.writeString(soPublica, publicaSo);
        assertThrows(IllegalStateException.class, () -> new JwtSigningKey(soPublica.toString()), "sem parte privada não assina");
        assertEquals(publicaSo, Files.readString(soPublica));
    }

    @Test
    void permissaoDoArquivoEOsSeisZeroZeroQuandoOSistemaSuporta() throws Exception {
        Path arquivo = dir.resolve("k.json");
        new JwtSigningKey(arquivo.toString());
        if (Files.getFileStore(arquivo).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(arquivo));
        }
        try (var sobras = Files.list(dir)) {
            assertEquals(1, sobras.count(), "nenhum arquivo temporário sobra");
        }
    }

    @Test
    void chavesDeInstalacoesDiferentesTemKidsDiferentes() {
        assertNotEquals(new JwtSigningKey(dir.resolve("a.json").toString()).kid(), new JwtSigningKey(dir.resolve("b.json").toString()).kid());
    }

    @Test
    void tokenAdulteradoOuDeOutraChaveNaoVerifica() throws Exception {
        JwtSigningKey a = new JwtSigningKey(dir.resolve("a.json").toString());
        JwtSigningKey b = new JwtSigningKey(dir.resolve("b.json").toString());
        SignedJWT deB = SignedJWT.parse(new JwtIssuer(b, 60, "gerenciador-identity").emitir("x@y.com", 1, false));

        assertFalse(deB.verify(new RSASSAVerifier(JWKSet.parse(a.jwksJson()).getKeys().get(0).toRSAKey())));
    }

    /**
     * N "réplicas" do identity (autoescala, issue #112) fazendo o PRIMEIRO boot ao mesmo tempo no mesmo volume vazio: todas terminam com a
     * MESMA chave (a privada inteira, não só o {@code kid}) e é a do arquivo; quem perde a corrida relê a do vencedor e nunca sobrescreve.
     * Com {@code rename(2)} ({@code ATOMIC_MOVE}) cada instância guardaria na memória a chave que ela mesma gerou, e o arquivo ficaria com a
     * da última a publicar. Repetido, para dar chance à corrida. Depois, um token emitido por qualquer réplica valida no JWKS de toda outra.
     */
    @RepeatedTest(5)
    void primeirosBootsConcorrentesNoMesmoCaminhoTerminamComAMesmaChaveEUmTokenValidaNoJwksDeQualquerOutra() throws Exception {
        Path arquivo = dir.resolve("corrida/jwt-signing-key.json");
        int n = 8;
        CyclicBarrier largada = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<JwtSigningKey> replicas = new ArrayList<>();
        try {
            List<Future<JwtSigningKey>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> {
                    largada.await();
                    return new JwtSigningKey(arquivo.toString());
                }));
            }
            for (Future<JwtSigningKey> f : fs) {
                replicas.add(f.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        Set<String> chaves = new HashSet<>();
        for (JwtSigningKey r : replicas) {
            chaves.add(r.chave().toJSONString());
        }
        assertEquals(1, chaves.size(), "todas as réplicas ficaram com a mesma chave privada (kids: "
                + replicas.stream().map(JwtSigningKey::kid).collect(Collectors.toSet()) + ")");
        JwtSigningKey doArquivo = new JwtSigningKey(arquivo.toString());
        assertEquals(doArquivo.chave().toJSONString(), chaves.iterator().next(), "e é a que ficou no arquivo");
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(arquivo), "continua 600");

        // token de cada réplica i valida no JWKS de cada réplica j (o gateway pode buscar o JWKS em qualquer uma)
        for (int i = 0; i < n; i++) {
            SignedJWT jwt = SignedJWT.parse(new JwtIssuer(replicas.get(i), 3600, "gerenciador-identity").emitir("r" + i + "@x.com", i, false));
            for (int j = 0; j < n; j++) {
                JWK publica = JWKSet.parse(replicas.get(j).jwksJson()).getKeyByKeyId(jwt.getHeader().getKeyID());
                assertNotNull(publica, "o kid do token da réplica " + i + " está no JWKS da réplica " + j);
                assertTrue(jwt.verify(new RSASSAVerifier(publica.toRSAKey())), "token da réplica " + i + " valida no JWKS da " + j);
            }
        }
        try (var sobras = Files.list(arquivo.getParent())) {
            assertEquals(1, sobras.count(), "nenhum temporário sobra");
        }
    }
}
