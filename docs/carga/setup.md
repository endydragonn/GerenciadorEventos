# Setup da máquina de geração de carga (issue #4)

> Os passos 1–4 abaixo estão automatizados em `Projeto/carga/` (`setup-host.ps1`,
> `setup-gerador.ps1`, `rodar-teste.ps1`; ver `Projeto/carga/README.md`). Este guia fica como
> referência manual e como lugar do registro do ambiente (seção 5).

Objetivo: uma máquina separada do host do sistema dispara a carga, para que latência e
throughput absolutos sejam válidos (ver `BACKLOG.md`, "Decisões de arquitetura em vigor").

- **Host-alvo (SUT):** a máquina que roda `docker compose -f Projeto/docker-compose.yml up -d --build`
  (backend `:8081`, Postgres `:5433`, frontend `:8088`).
- **Gerador de carga:** a máquina nova. Não precisa de Docker, banco nem do `Projeto/.env`.

> Nunca copie o `Projeto/.env` para o gerador (contém credenciais reais).

## 1. No host-alvo

1. Subir a stack e validar o baseline: `docker compose -f Projeto/docker-compose.yml up -d --build`.
2. Descobrir o IP do host na rede local (`ipconfig`, adaptador em uso) e anotar: `<IP_HOST>`.
3. Liberar a porta da API no firewall do Windows (somente rede privada):

   ```powershell
   New-NetFirewallRule -DisplayName "TCC backend 8081" -Direction Inbound `
     -Protocol TCP -LocalPort 8081 -Action Allow -Profile Private
   ```

4. Deixar a rede como **Privada** (Configurações > Rede). Não expor `5433` para fora do host.
5. Do gerador, `http://<IP_HOST>:8081` deve responder (passo 4 abaixo).
6. Para uma medição estável: ligar na tomada, plano de energia "Alto desempenho", fechar apps
   pesados e anotar CPU/RAM/disco do host na seção "Ambiente" abaixo.

## 2. No gerador de carga

1. Conectar na **mesma rede** do host, de preferência por cabo (Wi-Fi adiciona jitter).
2. Instalar **k6** (`winget install k6 --source winget`) ou, se preferir, JMeter/Gatling.
   Confirmar com `k6 version`.
3. Clonar o repositório (para versionar os cenários em `Projeto/carga/`) e entrar na branch
   da issue. Sem `.env`.
4. Mesmo plano de energia/desempenho do passo 1.6.

## 3. Medir a latência base de rede (para descontar depois)

No gerador:

```powershell
ping -n 100 <IP_HOST>
curl.exe -s -o NUL -w "connect=%{time_connect}s ttfb=%{time_starttransfer}s total=%{time_total}s\n" http://<IP_HOST>:8081/
```

Rodar 3 vezes em momentos diferentes e registrar min/média/máx e perda de pacotes. Esses
valores são subtraídos das latências medidas nas rodadas da Fase 2.

## 4. Smoke test do k6

Arquivo `smoke.js` (ajustar a rota para um endpoint GET público do backend):

```js
import http from 'k6/http';
import { check } from 'k6';

export const options = { vus: 5, duration: '15s' };

export default function () {
  const res = http.get(`${__ENV.BASE_URL}/`);
  check(res, { 'status < 500': (r) => r.status < 500 });
}
```

Rodar: `k6 run -e BASE_URL=http://<IP_HOST>:8081 smoke.js`

Critério: sem erros de conexão e latência p95 coerente com a base medida no passo 3.

## 5. Registro (preencher no dia do teste)

| Item | Host-alvo | Gerador |
|---|---|---|
| CPU / RAM / disco | | |
| SO / versão | | |
| Rede (cabo/Wi-Fi, velocidade) | | |
| IP | | |
| Ferramenta e versão | | k6 |
| Ping min/média/máx | | |
| Perda de pacotes | | |
| Commit testado | | |

## Critério de aceite da issue #3

- [ ] Gerador com acesso de rede ao host-alvo.
- [ ] Ferramenta de carga instalada e smoke test passando.
- [ ] Latência base entre gerador e alvo medida e registrada neste documento.

## 6. Registro das rodadas

Fonte dos números: `Projeto/carga/results/verificacoes.log` (cada `-Verificar` anexa data/hora,
alvo e config ativa). Rodar o verificador **logo após** a rodada: depois do TTL as reservas
expiram e as vagas voltam, então só `eventos_com_overselling` continua valendo como prova.

Ambiente: host `192.168.68.61` (Ethernet, mesma rede do gerador); ping do gerador < 1 ms, 0% de perda.

| Data | Teste | Alvo | Config (read path / refresh) | Resultado do banco | Overselling |
|---|---|---|---|---|---|
| 2026-10-04 | Disputa | eventos 1–5 | cas / 5000 ms | capacity 20, 20 reservas `expired`, vagas 20 (verificado após o TTL) | 0 |
| 2026-10-04 | Disputa | evento 6 | cas / 5000 ms | capacity 20, 20 reservas `pending`, vagas 0 (dentro do TTL) | 0 |
| 2026-10-04 | Taxa fixa (RPM) | `Rpm-903185` | cas / 5000 ms | 11 eventos, capacidade total 1100, 1000 reservas `expired`, 0 ativas (após o TTL) | 0 (`eventos_com_overselling = 0`) |
| 2026-10-04 | Taxa fixa (RPM) | `Rpm-903506` | cas / 5000 ms | 79 eventos, capacidade total 7900, 5379 reservas (2312 `pending` ativas + 3067 `expired`), ocupação ~29% (dentro do TTL) | 0 (`eventos_com_overselling = 0`) |
| 2026-10-04 | Taxa fixa (RPM) | `Rpm-904217` | cas / 5000 ms, `max_connections=300` (CSV do k6) | 79 eventos, capacidade total 7900, ~5375 reservas (3413 `pending` + 1962 `expired`), ocupação ~43% (dentro do TTL) | 0 (`eventos_com_overselling = 0`) |
| 2026-10-04 | Aleatório (teste 3) | `Ale-904991` | cas / 5000 ms, `max_connections=1000`, commit `c9bd024` (PR #95); k6: 45 min, 1k–12k RPM, rpm médio 6150, **112.878 respostas 5xx**, 1735 iterações descartadas, reserva p95/p99 153/879 ms, leitura p95/p99 770/4392 ms, máx 23,2 s | 1464 eventos, capacidade total 146400, 81109 reservas `expired`, 0 ativas (após o TTL) | 0 (`eventos_com_overselling = 0`) |
| 2026-10-04 | Taxa fixa (RPM) | `Rpm-909189` | cas / 5000 ms, `max_connections=1000` | 53 eventos, capacidade total 5300, 2917 reservas `pending` ativas (~55%, dentro do TTL); 39.120 linhas de `BindException` no log; resumo do k6 pendente (não publicado) | 0 (`eventos_com_overselling = 0`) |
| 2026-10-04 | Taxa fixa (RPM) | `Rpm-909317` | cas / 5000 ms, `max_connections=1000` | 53 eventos, capacidade total 5300, ~2850 reservas (1343 `pending` + 1507 `expired`, parcialmente expiradas); 21.711 linhas de `BindException` no log; resumo do k6 pendente | 0 (`eventos_com_overselling = 0`) |

Pendente de preencher: RPM alvo, duração, usuários e commit testado de cada rodada.

### Observações das rodadas RPM (2026-10-04)

- **Erros no backend durante a carga** (`docker logs gerenciador-backend`, ~15:53Z): os jobs
  `@Scheduled` (atualização de `event_availability` e listagem de reservas expiradas) falharam com
  `PSQLException: The connection attempt failed` causada por
  `java.net.BindException: Cannot assign requested address`. Isso indica esgotamento de portas
  efêmeras (ou limite de conexões) na rota backend → Postgres, e não falha de lógica do CAS.
  O overselling continuou 0, mas os jobs de expiração/read model ficaram atrasados, o que
  explica reservas `pending` ainda ativas no verificador.
- **Ajuste:** `max_connections` do Postgres elevado para 1000 (informado pelo operador; confirmar com
  `SHOW max_connections` e registrar as rodadas seguintes com esse valor). Atenção: `BindException`
  é falha **do lado do cliente** (portas efêmeras do backend), então subir `max_connections` pode não
  bastar; conferir também o pool do backend (tamanho máximo) e, se persistir, o range de portas do
  Docker/WSL. Comparar a próxima rodada RPM com a anterior (`Rpm-903506`, 300).
- Rodadas com esse erro devem ser tratadas como **saturação do ambiente**, não como resultado
  de capacidade do sistema, até a causa ser isolada.
- **`Rpm-904217` (`max_connections=300` conforme o CSV do k6; o 1000 só foi aplicado depois):** o backend ainda registrou 42 linhas
  de `BindException`/`connection attempt failed` nos últimos 30 min. Ou seja, subir `max_connections`
  **não eliminou** o erro, o que reforça a hipótese de portas efêmeras / pool do backend. Mantém-se
  como saturação do ambiente. (`reservas_ativas` 3414 vs `pending` 3413: as duas consultas do
  verificador não são atômicas e a carga ainda corria.)
- **Causa identificada (2026-10-04):** o backend **não tem pool de conexões**.
  `DatabaseConnection.getConnection()` usa `DriverManager.getConnection(...)`, ou seja, abre uma
  conexão TCP nova no Postgres a cada operação de repositório. Sob carga isso gera milhares de
  sockets em `TIME_WAIT` (medido no container do backend: `tw 3363` logo após a rodada;
  faixa de portas efêmeras `32768–60999`, ~28 mil) até esgotar as portas →
  `BindException: Cannot assign requested address`. Por isso subir `max_connections` não ajudou
  (o Postgres tinha só ~16 conexões ativas/ociosas). `tcp_tw_reuse=2` já é o padrão do kernel (só loopback).
  As latências e a taxa sustentável medidas hoje refletem esse gargalo, não o CAS.
- **Correção possível (decisão do usuário, mudança de código de backend):** introduzir um pool
  (HikariCP, via `DataSource`) mantendo JDBC puro, sem JPA. Alternativa só de ambiente: ampliar a
  faixa de portas efêmeras no container (`sysctl net.ipv4.ip_local_port_range`), que apenas adia o problema.
- **`Ale-904991` (teste aleatório, PR #95):** 110407 linhas de `BindException`/`connection attempt failed`
  no log do backend em 30 min (contra 42 na `Rpm-904217`), sem pool de conexões. Overselling 0, mas
  a rodada é de saturação do ambiente; comparar com a rodada pós-pool.

#### Evidência de esgotamento de portas (série de `sockstat`, backend, 2026-10-04 14:26–14:30)

`results/sockstat.log` (amostragem a cada 2 s de `TCP: ... tw`):

- Repouso: `tw` ≈ 14. Durante a carga: **pico de 34.199 sockets em `TIME_WAIT`** às 14:30:20.
- A faixa de portas efêmeras do container é `32768–60999` = **28.232 portas**. Como o destino é
  sempre o mesmo `db:5432`, o pico (> 28.232) confirma o esgotamento: sem porta livre, o `connect()`
  falha com `Cannot assign requested address`.
- O `tw` só cai ~1.800 a cada 2 s depois do fim da carga (sockets expiram em ~60 s), então o backend
  fica sem conexões por minutos após cada rodada.
- Conclusão: o gargalo medido nas rodadas de 10k RPM (4718 erros 5xx, ~31%) e na `Ale-904991`
  (112.878 erros 5xx) é a falta de pool (#96), não o CAS nem o `max_connections` (1000 foi inútil: não
  mudou o 5xx). Overselling 0 em todas.

#### Rodadas pós-pool (2026-10-04, tarde) — issue #96

Backend `275d587`: `DatabaseConnection` usa o `DataSource` do Spring (HikariCP), pool
`maximum-pool-size` 1000 (default; `minimum-idle` 5, cresce sob demanda), host com
`max_connections=1000`. Resumos do k6 e CSVs: `Projeto/carga/results/` (PR #98). Overselling
conferido no banco do host com `make verificar-disputa` / `make verificar-rpm`.

**Ressalva de comparação:** a stack foi recriada antes dessas rodadas (banco zerado: `event_id` e
`user_id` voltaram a 1). A única mudança de código de backend entre manhã e tarde é o pool; a carga
(RPM, semente `617706`, mesmos 1464 eventos de 100 vagas, mesmos 200 usuários) é a mesma.

##### Teste 1 — disputa (CAS, capacidade 20; eventos 1, 2, 3)

| Clientes | Aceitas | Lotado | Erros | p50 / p95 | Vazão | p95 da manhã (sem pool) |
|---|---|---|---|---|---|---|
| 50 | 20 | 30 | 0 | 95,8 / 103,0 ms | 79,9/s | 260 ms |
| 100 | 20 | 80 | 0 | 41,4 / 63,5 ms | 89,6/s | 414 ms |
| 200 | 20 | 180 | 0 | 74,4 / 89,8 ms | 95,4/s | 695 ms |

Overselling 0 (banco: 20 reservas por evento, ativas ≤ capacidade). Na primeira bateria da manhã
ainda houve 13 e 105 respostas "outras" (100 e 200 clientes); agora 0. **Motivo da melhora:** cada
operação de repositório deixou de abrir uma conexão TCP + autenticação nova no Postgres.

##### Teste 2 — 10k RPM por 90 s (`Rpm-913181` e `Rpm-913395`)

| Rodada | RPM real | Aceitas | Lotado | 5xx | Descartadas | p50 / p95 / p99 | Overselling | `BindException` |
|---|---|---|---|---|---|---|---|---|
| `Rpm-913181` | 9786 | 7501 | 7500 | **0** | 0 | 2,7 / 4,7 / 13,2 ms | **0** | **0** |
| `Rpm-913395` | 9792 | 7500 | 7500 | **0** | 0 | 2,5 / 4,2 / 11,9 ms | **0** | **0** |

Antes do pool (`Rpm-903506`, `Rpm-904217`): 4718 respostas 5xx em ~15 mil (~31%), idênticas nas duas
rodadas, e 39.120 linhas de `BindException` na `Rpm-909189`. Agora 0 e 0. Critério da #96 atendido.

- **7501 aceitas não é overselling:** o k6 fez 15001 iterações (7501 + 7500), uma além das 15000
  planejadas. Com os pares em sequência (usuário `i % 200`, evento `i / 200`), a iteração 15000 cai
  no 76º evento, ainda com 100 vagas livres, e é aceita. Os 75 primeiros eventos somam 7500 = 75 × 100.
  O banco confirma: `eventos_com_overselling` 0.
- As duas rodadas deram praticamente o mesmo resultado: sem a saturação de portas o comportamento
  voltou a ser determinístico e limitado pelo CAS, não pelo ambiente.

##### Teste 3 — aleatório, 45 min (`Ale-913561`, semente 617706, a mesma da `Ale-904991`)

| Mesmo perfil (1k–12k RPM) | Manhã `Ale-904991` (sem pool) | Tarde `Ale-913561` (pool) |
|---|---|---|
| RPM médio (pico alvo) | 6150 (11926) | 6189 (11926) |
| Reservas aceitas | 81.109 | 195.005 |
| Reservas 5xx | 112.878 | **0** |
| Leituras ok / inesperadas | 28.693 / 54.306 | 83.716 / **0** |
| Iterações descartadas / VUs máx. | 1735 / 500 | **0** / 100 |
| Reserva p50 / p95 / p99 | 6,2 / 153 / 880 ms | 3,1 / 4,4 / 8,9 ms |
| Leitura p50 / p95 / p99 | 6,4 / 770 / 4393 ms | 1,9 / 456 / 492 ms |
| Latência máxima | 23,2 s | 563 ms |
| `BindException` no log | 110.407 | **0** |

Overselling 0 (`make verificar-rpm PREFIXO=Ale-913561`: 1464 eventos, 0 com overselling, 195.005
reservas todas `expired` ao fim). Critério da #96 atendido (0 5xx, 0 descartadas).

- **195.005 aceitas > 146.400 vagas (1464 × 100) não é overselling:** em 45 min as reservas
  `pending` expiram (~120 s) e a vaga é revendida. A invariante é ativas ≤ capacidade.
- **Mais aceitas do que de manhã (81 mil → 195 mil)** porque antes ~31% das tentativas morriam em
  5xx e 1735 iterações eram descartadas por falta de VU.

##### Achados e motivos

1. **Causa raiz confirmada: o gargalo era a falta de pool, não o CAS nem o `max_connections`.**
   `DriverManager` abria uma conexão TCP por operação → milhares de sockets em `TIME_WAIT` (pico
   34.199 > 28.232 portas efêmeras) → `BindException`. Com o pool: 0 5xx e 0 `BindException` nas
   três rodadas; subir `max_connections` não tinha efeito antes, o que já apontava para a conexão
   por operação.
2. **Overselling 0 em todas as rodadas**, antes e depois: o CAS é correto e o problema era de
   disponibilidade. Os números "a mais" (7501, 195.005) têm as explicações acima.
3. **Leitura bimodal (aberto):** leitura p50 1,9 ms mas p95 456 ms e p99 492 ms. Suspeita, *não
   medida por endpoint*: `GET /bff/events/search` varre todos os eventos em memória. Não é efeito do
   pool. Follow-up fora do escopo da #96 (o N+1 do `hydrate` e o `LIMIT`/`SKIP LOCKED` são da #61).
4. **`tw` do `sockstat` pós-pool não foi medido durante a carga:** o `results/sockstat.log` desta
   tarde tem só 5 linhas (15:42, `tw` máx. 6, em repouso) e não cobre as rodadas. O critério "`tw` de
   poucos milhares" não tem série própria; o proxy é `BindException` = 0 e 0 5xx.
5. **Default do pool 1000 só é seguro com `max_connections` alto:** o `db` do `docker-compose.yml`
   padrão usa `max_connections=100`; fora do host de carga, reduzir por
   `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`. O `minimum-idle` 5 evita que cada contexto Spring
   (inclusive os de teste) segure 1000 conexões ociosas. O tamanho ideal do pool não foi explorado:
   com 100 VUs e latências de poucos ms, o pool nunca precisou chegar perto de 1000.
6. **Manhã e tarde não são perfeitamente comparáveis** (banco zerado entre as baterias); a diferença
   é grande demais (5xx 112.878 → 0) para ser explicada por isso, então a comparação de latência é
   indicativa, não exata.
7. **Nenhum dos 3 testes finaliza vendas:** todas as reservas ficam `pending` e depois `expired`;
   nenhuma chega a `confirmed`. Isso mede só o caminho de reserva (CAS), não a confirmação. Registrado
   para o próximo trabalho (testes com vendas efetivamente finalizadas).
