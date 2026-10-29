package com.gerenciador.eventos.reservationclient.api;

import java.util.List;

/**
 * Inscrição/reserva em eventos, do ponto de vista de quem CHAMA o serviço reservation (o gateway do monólito). Cada
 * método é uma chamada HTTP à API interna; o {@code userId} vai sempre como parâmetro explícito (a correção do IDOR
 * #57 mexe só no gateway). Erros: {@link IllegalArgumentException} (regra de negócio, HTTP 400),
 * {@link IdempotencyConflictException} (422) ou {@link RuntimeException} (falha do
 * serviço ou da rede).
 */
public interface EventWalletApi {

    /**
     * Reserva idempotente. {@code idempotencyKey} nula/em branco => o serviço gera uma (volta em
     * {@code getIdempotencyKey()} do resultado). A repetição com a mesma chave (do mesmo usuário, para o mesmo evento)
     * devolve o resultado da primeira sem tocar a capacidade; o mesmo usuário com outro evento => conflito (422).
     *
     * <p>{@code naive}: braço de controle do experimento. Em vez de um CAS atômico no serviço (uma chamada), o cliente
     * pergunta a disponibilidade ao serviço (GET) e SÓ DEPOIS pede a reserva sem CAS (POST): a janela check-then-act
     * inclui uma ida e volta de rede. Oversella proporcional ao lag da projeção de disponibilidade.</p>
     */
    EventWallet addLink(EventWallet ew, String idempotencyKey, boolean naive);

    EventWallet confirm(Long userId, Long eventId);

    void removeLink(Long userId, Long eventId);

    List<EventWallet> listByUser(Long userId);

    /** Existe reserva ativa ({@code pending}/{@code confirmed}) do usuário para o evento? */
    boolean existsActive(Long userId, Long eventId);
}
