package com.gerenciador.eventos.stockclient.api;

/**
 * HTTP 409 do serviço stock (ex.: apagar a capacidade de um evento que tem reserva). Estende {@link IllegalStateException}
 * para quem já tratava o 409 assim; existe para o chamador distinguir o 409 de uma {@code IllegalStateException} qualquer (ex.:
 * pool do cliente HTTP fechado), que não é conflito (issue #35: o catalog mapeia só o conflito para 409).
 */
public class StockConflictException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public StockConflictException(String message) {
        super(message);
    }
}
