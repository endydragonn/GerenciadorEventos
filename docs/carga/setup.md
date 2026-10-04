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
