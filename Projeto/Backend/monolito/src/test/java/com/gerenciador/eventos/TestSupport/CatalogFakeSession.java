package com.gerenciador.eventos.TestSupport;

import java.io.IOException;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Sobe o {@link FakeCatalogServer} UMA vez por JVM de teste, antes de qualquer contexto Spring, e aponta o {@code CatalogClient}
 * do gateway para ele pela propriedade de sistema {@code catalog.service.url} (issue #35; registrado em
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}, como o {@link ReservationFakeSession}).
 */
public class CatalogFakeSession implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            System.setProperty("catalog.service.url", FakeCatalogServer.iniciar().url());
        } catch (IOException ex) {
            throw new IllegalStateException("Não consegui subir o servidor falso do catalog", ex);
        }
    }
}
