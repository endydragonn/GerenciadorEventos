package com.gerenciador.eventos.TestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Prepara UMA vez por JVM de teste, antes de qualquer contexto Spring (registrado em
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}): (1) uma chave de assinatura do JWT TEMPORÁRIA
 * ({@code jwt.signing-key-path} num diretório novo; o 1º contexto a gera, os demais a relêem: mesma chave na JVM inteira, nada
 * escrito em {@code /var/lib/identity}); (2) o reCAPTCHA falso ({@code recaptcha.verify-url}) e uma chave de reCAPTCHA de teste
 * ({@code recaptcha.secret-key}, que vence a {@code RECAPTCHA_SECRET_KEY} real do {@code .env} passada ao container): a chave real
 * nunca sai para o Google nos testes. Propriedade de sistema vence o {@code application.properties} e o ambiente.
 */
public class IdentityTestSession implements LauncherSessionListener {

    public static final String SECRET_DE_TESTE = "segredo-recaptcha-de-teste";

    private Path dirDaChave;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            dirDaChave = Files.createTempDirectory("identity-jwt-test");
            System.setProperty("jwt.signing-key-path", dirDaChave.resolve("jwt-signing-key.json").toString());
            System.setProperty("recaptcha.verify-url", FakeRecaptchaServer.iniciar().url());
            System.setProperty("recaptcha.secret-key", SECRET_DE_TESTE);
        } catch (IOException ex) {
            throw new IllegalStateException("Não consegui preparar o ambiente de teste do identity", ex);
        }
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        if (dirDaChave != null) {
            try (var arquivos = Files.walk(dirDaChave)) {
                arquivos.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ex) {
                // limpeza best-effort do diretório temporário
            }
        }
    }
}
