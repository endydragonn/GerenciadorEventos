package com.gerenciador.eventos.TestSupport;

import java.io.IOException;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Sobe o {@link FakeReservationServer} UMA vez por JVM de teste, antes de qualquer contexto Spring, e aponta o cliente do
 * monólito para ele pela propriedade de sistema {@code reservation.service.url} (registrado em
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}).
 */
public class ReservationFakeSession implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            System.setProperty("reservation.service.url", FakeReservationServer.iniciar().url());
        } catch (IOException ex) {
            throw new IllegalStateException("Não consegui subir o servidor falso do reservation", ex);
        }
    }
}
