# Setup da máquina de geração de carga (issue #3)

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
