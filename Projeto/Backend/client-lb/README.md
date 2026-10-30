# client-lb

Lib (jar simples, **sem Spring**) do **balanceamento no cliente** — a trilha C da issue #197 (Kubernetes). O `Service` do Kubernetes balanceia por conexão TCP e os 5 clientes HTTP
(`reservation-client`, `stock-client`, `catalog-client`, `identity-client`, `admission-client`) mantêm as conexões abertas, então um pod novo não recebe tráfego das conexões que já existem. Esta lib
**acrescenta**, à montagem do pool que cada cliente já faz, o que espalha as conexões novas pelos pods:

- `DnsRoundRobin` (pacote interno): a cada conexão nova resolve o host e devolve os endereços rodados em uma posição. Se o endereço da frente recusa a conexão, o operador de conexão do HttpClient 5 tenta o
  próximo da lista (sem retry próprio: o cliente continua com `disableAutomaticRetries`).
- `ConnectionConfig.setTimeToLive` (`CLIENT_LB_TTL_MS`, default 15000): a conexão mais velha que isso é fechada ao voltar ao pool, então em até um TTL o pool inclui os pods novos e esquece os que saíram.
- `PoolReusePolicy.FIFO`: o reuso gira pelas conexões abertas em vez de concentrar na última devolvida.

Uso (dentro de cada `*Client`): `lb.aplicar(poolBuilder, connectionConfigBuilder)` antes do `build()`; os timeouts de conexão, socket e resposta que o cliente já definiu ficam como estão. **Desligado por padrão:**
`ClientLb.desligado()` não toca em nada (`CLIENT_LB_ENABLED=false`, o default de todos os serviços), então a montagem dos clientes, o compose e os testes são os de antes. O overlay `Projeto/k8s/overlays/trilha-c`
liga a env, aponta os `*_SERVICE_URL` para Services headless (`*-pods`) e passa o cache de DNS da JVM para 5 s.

Testes: `ClientLbTest` (aqui, sem rede) e `ClientLbCincoClientesTest` no módulo `monolito` (pelos 5 clientes reais contra servidores JDK em 127.0.0.1/2/3 e um DNS falso 1 → 3 → 2 endereços; mora no
monólito porque é o único módulo que enxerga os 5 clientes sem ciclo de módulos). Ver "Trilhas de balanceamento" no `../CLAUDE.md`.
