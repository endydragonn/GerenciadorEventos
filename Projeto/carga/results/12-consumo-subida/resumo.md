<!-- gerado por carga/consumo/analisar-consumo.py; mediana de 3 reps (servicos) e 3 reps (monolito) -->

### Microsserviços: custo de subir cada container (na ordem das dependências)

| passo | container novo | pronto (s) | JVM start (s) | CPU boot (CPU-s) | CPU ociosa (% núcleo) | anon (MB) | mem (MB) | Δtotal anon (MB) | % do bloco (anon) |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | db | 6,6 | – | 1,6 | 0,26 | 7 | 136 | 7 | 0,2% |
| 2 | supabase-pooler | 6,6 | – | 11,0 | 0,94 | 219 | 356 | 229 | 7,4% |
| 3 | redpanda | 6,4 | – | 0,2 | 0,63 | 102 | 118 | 103 | 3,4% |
| 4 | redis | 6,4 | – | 0,1 | 0,63 | 3 | 6 | 4 | 0,1% |
| 5 | rabbitmq | 6,4 | – | 7,2 | 46,87 | 65 | 147 | 65 | 2,2% |
| 6 | mailpit | 1,6 | – | 0,1 | 0,43 | 7 | 35 | 1 | 0,2% |
| 7 | identity | 4,9 | 3,00 | 13,7 | 2,31 | 359 | 530 | 366 | 12,1% |
| 8 | catalog | 3,4 | 2,08 | 10,6 | 2,18 | 310 | 474 | 328 | 10,4% |
| 9 | stock | 3,5 | 2,20 | 10,9 | 0,97 | 313 | 476 | 342 | 10,6% |
| 10 | reservation | 4,9 | 2,68 | 13,5 | 2,55 | 436 | 607 | 459 | 14,7% |
| 11 | admission | 5,0 | 2,73 | 12,2 | 0,68 | 338 | 499 | 380 | 11,4% |
| 12 | notification | 3,5 | 1,86 | 6,4 | 0,86 | 208 | 364 | 250 | 7,0% |
| 13 | backend | 5,0 | 3,54 | 15,9 | 0,70 | 391 | 588 | 445 | 13,2% |
| 14 | frontend | 6,5 | – | 0,1 | 0,37 | 11 | 24 | 17 | 0,4% |
| 15 | identity+1 | 4,8 | 2,62 | 12,7 | 2,13 | 340 | 345 | 368 | 11,5% |
| 16 | catalog+1 | 3,4 | 2,35 | 12,2 | 2,03 | 329 | 333 | 357 | 11,1% |
| 17 | reservation+1 | 4,8 | 2,66 | 12,3 | 2,54 | 419 | 424 | 452 | 14,1% |
| 18 | admission+1 | 4,8 | 2,81 | 13,3 | 0,67 | 350 | 355 | 424 | 11,8% |
| 19 | notification+1 | 3,4 | 1,63 | 6,6 | 0,93 | 220 | 223 | 272 | 7,4% |
| 20 | backend+1 | 4,9 | 3,60 | 17,3 | 0,72 | 429 | 433 | 464 | 14,5% |

### Monólito pré-#32: custo de subir cada container

| passo | container novo | pronto (s) | JVM start (s) | CPU boot (CPU-s) | CPU ociosa (% núcleo) | anon (MB) | mem (MB) | Δtotal anon (MB) | % do bloco (anon) |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | db | 6,6 | – | 1,6 | 0,26 | 7 | 190 | 7 | 0,9% |
| 2 | supabase-pooler | 6,6 | – | 11,0 | 0,97 | 217 | 384 | 228 | 28,1% |
| 3 | redpanda | 6,4 | – | 0,2 | 0,63 | 102 | 220 | 102 | 13,1% |
| 4 | backend | 4,9 | 3,83 | 16,3 | 2,01 | 439 | 472 | 446 | 56,8% |
| 5 | backend+1 | 4,7 | 3,69 | 16,8 | 2,05 | 438 | 560 | 442 | 56,6% |

### Bloco inicial (1 instância de cada; sem mailpit e frontend)

| bloco | containers | subida sequencial (s) | CPU boot (CPU-s) | CPU ociosa (% núcleo) | anon (MB) | dos quais infra (MB) | mem (MB) | VM usada (MB) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| microsserviços | 12 | 62,6 | 103,3 | 60,9 | 2.966 | 396 | 4.511 | 5.004 |
| monólito | 4 | 24,5 | 29,1 | 6,0 | 774 | 326 | 1.012 | 2.235 |

Razão microsserviços ÷ monólito: anon 3,83×, CPU de boot 3,55×, CPU ociosa 10,11×, subida sequencial 2,56×.

### Microsserviços: +1 instância de cada serviço, em % do bloco

| +1 instância | anon próprio (MB) | % do bloco (anon) | Δtotal anon (MB) | % do bloco (Δtotal) | CPU boot (CPU-s) | % do boot do bloco | CPU ociosa (% núcleo) | % da CPU ociosa do bloco | mem própria (MB) | % do bloco (mem) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| identity+1 | 340 | 11,5% | 368 | 12,4% | 12,7 | 12,3% | 2,13 | 3,5% | 345 | 7,7% |
| catalog+1 | 329 | 11,1% | 357 | 12,0% | 12,2 | 11,8% | 2,03 | 3,3% | 333 | 7,4% |
| reservation+1 | 419 | 14,1% | 452 | 15,2% | 12,3 | 11,9% | 2,54 | 4,2% | 424 | 9,4% |
| admission+1 | 350 | 11,8% | 424 | 14,3% | 13,3 | 12,9% | 0,67 | 1,1% | 355 | 7,9% |
| notification+1 | 220 | 7,4% | 272 | 9,2% | 6,6 | 6,4% | 0,93 | 1,5% | 223 | 4,9% |
| backend+1 | 429 | 14,5% | 464 | 15,6% | 17,3 | 16,7% | 0,72 | 1,2% | 433 | 9,6% |

### Monólito: +1 instância, em % do bloco

| +1 instância | anon próprio (MB) | % do bloco (anon) | Δtotal anon (MB) | % do bloco (Δtotal) | CPU boot (CPU-s) | % do boot do bloco | CPU ociosa (% núcleo) | % da CPU ociosa do bloco | mem própria (MB) | % do bloco (mem) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| backend+1 | 438 | 56,6% | 442 | 57,1% | 16,8 | 57,7% | 2,05 | 34,1% | 560 | 55,3% |
