package com.gerenciador.eventos.TestSupport;

import java.io.IOException;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Sobe o {@link FakeIdentityServer} UMA vez por JVM de teste, antes de qualquer contexto Spring, e aponta o {@code IdentityClient} e o
 * JWKS do gateway para ele pela propriedade de sistema {@code identity.service.url} (issue #36; registrado em
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}, como o {@link CatalogFakeSession}).
 */
public class IdentityFakeSession implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            System.setProperty("identity.service.url", FakeIdentityServer.iniciar().url());
        } catch (IOException ex) {
            throw new IllegalStateException("Não consegui subir o servidor falso do identity", ex);
        }
    }
}
