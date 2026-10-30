package com.gerenciador.eventos.admissionclient.api;

/**
 * Recusa de NEGÓCIO do admission (issue #43): {@code 422} (a {@code Idempotency-Key} já foi usada por este usuário em outro evento) ou
 * {@code 429} (limite de mensagens em espera por usuário, ou fila cheia). O serviço está vivo e respondeu: não é falha dele, então o cliente
 * NÃO faz fail-open nem conta para o disjuntor, e o gateway repassa o {@link #status()} e a mensagem ao cliente HTTP.
 */
public class AdmissionRecusadaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public AdmissionRecusadaException(int status, String mensagem) {
        super(mensagem);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
