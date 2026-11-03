package com.gerenciador.eventos.clientlb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;

/** Sem rede e sem clientes: o resolver gira os endereços e o {@link ClientLb} só mexe nos builders quando ligado. */
class ClientLbTest {

    private static InetAddress ip(int ultimo) throws UnknownHostException {
        return InetAddress.getByAddress(new byte[] {10, 0, 0, (byte) ultimo});
    }

    private static DnsResolver fixo(InetAddress... enderecos) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                return enderecos.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    @Test
    void cadaResolucaoComecaPeloEnderecoSeguinte() throws Exception {
        DnsResolver rr = new DnsRoundRobin(fixo(ip(1), ip(2), ip(3)));
        assertArrayEquals(new InetAddress[] {ip(1), ip(2), ip(3)}, rr.resolve("x"));
        assertArrayEquals(new InetAddress[] {ip(2), ip(3), ip(1)}, rr.resolve("x"));
        assertArrayEquals(new InetAddress[] {ip(3), ip(1), ip(2)}, rr.resolve("x"));
        assertArrayEquals(new InetAddress[] {ip(1), ip(2), ip(3)}, rr.resolve("x"));
    }

    @Test
    void ttlZeroOuNegativoComOBalanceamentoLigadoERecusado() {
        assertThrows(IllegalArgumentException.class, () -> new ClientLb(true, 0));
        assertThrows(IllegalArgumentException.class, () -> new ClientLb(true, -1));
        new ClientLb(true, 1); // o menor valido
        new ClientLb(false, 0); // desligado nao usa o TTL: nao valida
    }

    @Test
    void umEnderecoSoNaoMuda() throws Exception {
        assertArrayEquals(new InetAddress[] {ip(1)}, new DnsRoundRobin(fixo(ip(1))).resolve("x"));
    }

    @Test
    void desligadoNaoMexeNoBuilderDeConexao() {
        ConnectionConfig.Builder conexao = ConnectionConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(123));
        ClientLb.desligado().aplicar(PoolingHttpClientConnectionManagerBuilder.create(), conexao);
        ConnectionConfig cfg = conexao.build();
        assertNull(cfg.getTimeToLive());
        assertEquals(123, cfg.getConnectTimeout().toMilliseconds());
    }

    @Test
    void ligadoSomaOTtlSemTrocarOsTimeoutsQueJaEstavam() {
        ConnectionConfig.Builder conexao = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(123)).setSocketTimeout(Timeout.ofMilliseconds(456));
        new ClientLb(true, 1234).aplicar(PoolingHttpClientConnectionManagerBuilder.create(), conexao);
        ConnectionConfig cfg = conexao.build();
        assertEquals(1234, cfg.getTimeToLive().convert(TimeUnit.MILLISECONDS));
        assertEquals(123, cfg.getConnectTimeout().toMilliseconds());
        assertEquals(456, cfg.getSocketTimeout().toMilliseconds());
    }
}
