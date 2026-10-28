package com.gerenciador.eventos.gateway;

/**
 * Corpo genérico dos 500 do {@code gateway} (issue #89): a mensagem da exceção pode trazer detalhe de SQL/infra, então só vai
 * para o log do servidor (com stack trace), nunca para o chamador.
 */
final class ErroInterno {

    static final String MENSAGEM = "Erro interno ao processar a solicitação";

    private ErroInterno() {
    }
}
