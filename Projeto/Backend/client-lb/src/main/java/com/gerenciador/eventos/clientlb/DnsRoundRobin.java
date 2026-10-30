package com.gerenciador.eventos.clientlb;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.DnsResolver;

/**
 * Resolve o host a CADA conexão nova e devolve os endereços rodados em uma posição por resolução: a primeira tentativa de cada
 * conexão cai num pod diferente e, se esse endereço recusar a conexão, o operador de conexão do HttpClient tenta o próximo da
 * lista (é assim que um pod que saiu do Service, mas ainda está no cache de DNS, não derruba a chamada).
 */
final class DnsRoundRobin implements DnsResolver {

    private final DnsResolver delegado;
    private final AtomicInteger proximo = new AtomicInteger();

    DnsRoundRobin(DnsResolver delegado) {
        this.delegado = delegado;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] enderecos = delegado.resolve(host);
        if (enderecos.length < 2) {
            return enderecos;
        }
        int inicio = Math.floorMod(proximo.getAndIncrement(), enderecos.length);
        InetAddress[] rodados = new InetAddress[enderecos.length];
        for (int i = 0; i < enderecos.length; i++) {
            rodados[i] = enderecos[(inicio + i) % enderecos.length];
        }
        return rodados;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return delegado.resolveCanonicalHostname(host);
    }
}
