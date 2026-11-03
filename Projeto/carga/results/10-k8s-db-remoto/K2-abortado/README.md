# K2 abortado (`Deg-330681`), 2026-11-01

Esta primeira tentativa do K2 foi abortada no 1º degrau. **Os dados não entram na análise.** Ficam aqui só como registro. O K2 válido é a
repetição, descrita no README da etapa.

## Por que abortou

- Entre o K1 e o K2 foram abertos no gerador um jogo (4 GB) e o Steam (1 GB).
- RAM livre antes do disparo: 5,82 GB. Depois que o k6 subiu, 2,46 GB. No K1 eram 9,92 GB.
- Quando a carga começou, o k6 passou de 3,5 GB para 5,7 GB. Às 18:11:12Z a RAM livre do gerador estava em 676 MB.
- Um gerador paginando infla a latência e os descartes. O usuário decidiu abortar e repetir com a máquina livre.

## Linha do tempo (UTC)

| Hora | Evento |
|---|---|
| 18:00:17 | vigias do Dell religados |
| 18:03 | limpeza 2 (`limpar-banco`, modo cluster), rc 0 |
| 18:08 | preparar: seed de 1000 usuários e 868 eventos com capacidade 500 |
| 18:09:06 | `coleta-host` e `telemetria-host` no host |
| 18:09:29 | início da coleta no Dell (`coleta-k8s-K2`) |
| 18:09:32 | início do k6 no gerador |
| 18:11:05 | setup do k6 com os 1000 logins concluídos |
| 18:11:12 | alerta de RAM < 1 GB no gerador |
| 18:11:14 | fim da coleta no Dell |
| 18:11:19 | fim da `coleta-host` (SIGINT); a `telemetria-host` foi parada logo depois |
| 18:11:36 | k6 encerrado à força, ~30 s depois do início do 1º degrau (20k) |

O k6 foi morto sem parada graciosa (exit -1), então o `summary-degraus-330681.json` não foi gravado e não há `gerador/` nem `degraus-*.csv`.

## Arquivos

- `plano-degraus-330681.json`.
- `host-telemetria/`: o que a `coleta-host` e a `telemetria-host` gravaram até a parada. O `resumo-saga` termina com `sagas_reserving 296`:
  a coleta parou durante a carga, com sagas ainda em voo. Esse número não é uma órfã medida: o `verificar` não rodou neste bloco, e o banco foi
  truncado antes da repetição.
- `dell/`: pacote da sessão DB (ref temporária `K8sDB-dell-K2-abortado`, commit 24f04a7). O sha256 do tar foi conferido
  (`875acb98…2c5f9b`, 260.797 bytes). O sar e o iostat foram filtrados com o mesmo recorte do K1. Pico de 132 conexões na janela.
