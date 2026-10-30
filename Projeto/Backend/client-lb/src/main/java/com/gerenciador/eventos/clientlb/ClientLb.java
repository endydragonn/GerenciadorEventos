package com.gerenciador.eventos.clientlb;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.util.TimeValue;

/**
 * Balanceamento no cliente (trilha C da issue #197), sem Spring. Cada {@code *-client} monta o pool do HttpClient 5 como sempre e
 * chama {@link #aplicar} antes de {@code build()}: com a trilha desligada nada muda; ligada, soma ao que já está lá
 * <ul>
 *   <li>um {@code DnsResolver} round-robin (as conexões novas se espalham pelos endereços do Service headless);</li>
 *   <li>o tempo de vida da conexão ({@code CLIENT_LB_TTL_MS}, default {@value #TTL_PADRAO_MS}): em até um TTL o pool se refaz e passa a
 *       incluir os pods novos e a esquecer os que saíram;</li>
 *   <li>reuso FIFO: as conexões abertas giram em vez de se concentrar na última devolvida.</li>
 * </ul>
 * Sem retry próprio: o cliente segue com {@code disableAutomaticRetries}; o failover entre endereços é do operador de conexão.
 * Os timeouts (conexão, socket, resposta) ficam os que o cliente já definiu: aqui só entra o TTL.
 */
public final class ClientLb {

    /** TTL padrão da conexão ({@code CLIENT_LB_TTL_MS}). */
    public static final long TTL_PADRAO_MS = 15_000;

    private static final ClientLb DESLIGADO = new ClientLb(false, TTL_PADRAO_MS);

    private final boolean ativo;
    private final long ttlMs;
    private final DnsResolver resolver;

    public ClientLb(boolean ativo, long ttlMs) {
        this(ativo, ttlMs, SystemDefaultDnsResolver.INSTANCE);
    }

    /** O resolver de origem é injetável para o teste controlar os endereços (1 → 3 → 2) sem DNS de verdade. */
    public ClientLb(boolean ativo, long ttlMs, DnsResolver origem) {
        if (ativo && ttlMs <= 0) {
            // TTL 0 descartaria a conexão a cada lease (reconexão por requisição); negativo é erro de digitação
            throw new IllegalArgumentException("CLIENT_LB_TTL_MS (" + ttlMs + ") deve ser maior que 0 com CLIENT_LB_ENABLED=true");
        }
        this.ativo = ativo;
        this.ttlMs = ttlMs;
        this.resolver = new DnsRoundRobin(origem);
    }

    /** A montagem de hoje, sem nenhuma mudança (o default de todos os clientes). */
    public static ClientLb desligado() {
        return DESLIGADO;
    }

    /** Soma o balanceamento aos builders do cliente; desligado, não toca em nada. */
    public void aplicar(PoolingHttpClientConnectionManagerBuilder pool, ConnectionConfig.Builder conexao) {
        if (!ativo) {
            return;
        }
        pool.setDnsResolver(resolver).setConnPoolPolicy(PoolReusePolicy.FIFO);
        conexao.setTimeToLive(TimeValue.ofMilliseconds(ttlMs));
    }
}
