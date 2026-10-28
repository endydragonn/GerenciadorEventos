package com.gerenciador.eventos.admissionclient.api;

/**
 * Contrato do serviço admission visto pelo gateway (issue #41). Conta a tentativa de reserva no contador do evento e devolve o
 * modo. Nunca falha: qualquer problema com o serviço (indisponível, lento, token errado, pedido que ele recusa com 400) devolve
 * {@code sync} (fail-open) e a reserva segue pelo caminho de hoje. O admission é só sinal de carga na #41: nada é decidido por ele.
 */
public interface AdmissionApi {

    AdmissionResult admit(AdmissionRequest request);
}
