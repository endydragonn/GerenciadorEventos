package com.gerenciador.eventos.TestSupport;

import java.io.IOException;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Sobe o {@link FakeAdmissionServer} UMA vez por JVM de teste, antes de qualquer contexto Spring, e aponta o {@code AdmissionClient}
 * do gateway para ele pela propriedade de sistema {@code admission.service.url} (issue #41; registrado em
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}, como o {@link ReservationFakeSession}).
 */
public class AdmissionFakeSession implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            System.setProperty("admission.service.url", FakeAdmissionServer.iniciar().url());
        } catch (IOException ex) {
            throw new IllegalStateException("Não consegui subir o servidor falso do admission", ex);
        }
    }
}
