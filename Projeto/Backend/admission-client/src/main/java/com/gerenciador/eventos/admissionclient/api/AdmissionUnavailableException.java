package com.gerenciador.eventos.admissionclient.api;

/**
 * O admission não respondeu a uma CONSULTA (issue #43: {@link AdmissionApi#result}): rede, timeout, 5xx ou 401. Diferente do
 * {@code admit}, que faz fail-open, a consulta não tem um caminho alternativo: o gateway responde 503 genérico.
 */
public class AdmissionUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AdmissionUnavailableException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
