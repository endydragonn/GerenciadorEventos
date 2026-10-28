# 🐳 Guia Docker - Gerenciador de Eventos

Este guia contém todos os comandos necessários para trabalhar com o projeto usando Docker.

---

## 🚀 Início Rápido (4 passos)

```powershell
# 0. Apontar para o compose principal (db = supabase/postgres; exige Projeto/supabase/.env)
$env:COMPOSE_FILE = "../docker-compose.yml"   # bash: export COMPOSE_FILE=../docker-compose.yml

# 1. Subir os containers (banco + backend)
docker compose up -d --build

# 2. Aguardar inicialização (15 segundos)
Start-Sleep -Seconds 15

# 3. Popular banco via API (senhas criptografadas)
.\populate-data.ps1

```

**Para parar:**

```powershell
docker compose down
```

---

## � Comandos Principais

### Subir o Ambiente

```bash
# Subir banco de dados e backend
docker compose up -d

# Verificar status dos serviços
docker compose ps
```

**Serviços disponíveis:**

- `db` - PostgreSQL 17 (imagem supabase/postgres, porta 5433)
  
- `backend` - API Spring Boot (porta 8081)

### Parar o Ambiente

```bash
# Parar sem remover dados
docker compose down

# Parar e remover TUDO (incluindo dados do banco)
docker compose down -v
```

### Reconstruir Imagens

```bash
# Rebuild completo (use após mudanças no código)
docker compose build --no-cache

# Rebuild e subir
docker compose up -d --build
```

---

## 🧪 Testes

### Executar Testes (Recomendado)

```bash
./run-tests.sh
```

**O que este script faz:**

- ✅ Executa todos os testes dentro do Docker
- ✅ Não baixa dependências na sua máquina
- ✅ Exibe resultados formatados com estatísticas
- ✅ Mostra tempo de execução

**Saída esperada:**

```
==================================================
   🧪 EXECUTANDO TESTES NO DOCKER
==================================================

  ✅ EventTest: 1 testes (0.241s)
  ✅ UserTest: 15 testes (1.153s)
  ...

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 TOTAL:
   • Testes: 23
   • ✅ Sucesso: 23
   • ⏱️  Tempo: 7.953s
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

✅ RESULTADO: TODOS OS TESTES PASSARAM!
==================================================
```

### Executar Testes Manualmente

```bash
# Todos os testes
docker run --rm --network gerenciador-net --env-file .env \
  -v "$PWD":/workspace -w /workspace \
  maven:3.9.7-eclipse-temurin-21-jammy mvn -q test

# Apenas uma classe específica
docker run --rm --network gerenciador-net --env-file .env \
  -v "$PWD":/workspace -w /workspace \
  maven:3.9.7-eclipse-temurin-21-jammy \
  mvn -q -Dtest=UserTest test
```

### Exportar resultado dos testes (arquivo texto)

  ```bash
  # Executa os testes no Docker e gera um arquivo formatado (tests-report-<timestamp>.txt)
  ./export-tests.sh

  # Apenas exporta os últimos resultados já existentes em target/surefire-reports
  ./export-tests.sh --no-run

  # Definir um nome específico para o arquivo
  ./export-tests.sh --output testes-hoje.txt
  ```

  O relatório contém:

- Listagem por classe com tempo e status
- Resumo total (testes, sucesso, tempo total)
- Caminho para relatórios completos do Surefire

---

## 🗄️ Validação do Banco de Dados

### Verificar Estrutura das Tabelas

```bash
# Ver estrutura da tabela users
docker compose exec db psql -h localhost -U admin -d meu_banco -c "\d+ users"

# Ver todas as tabelas
docker compose exec db psql -h localhost -U admin -d meu_banco -c "\dt"

# Acessar console do PostgreSQL
docker compose exec db psql -h localhost -U admin -d meu_banco
```

### Executar Testes do Banco

```bash
# Rodar suite de testes SQL
docker compose exec db psql -h localhost -U admin -d meu_banco \
  -f /app-init/99-tests.sql
```

**Saída esperada:** `NOTICE: Testes mínimos concluídos com sucesso`

### Popular Banco com Dados de Exemplo

#### ✅ Método Recomendado: Via API (PowerShell)

**Vantagens:**

- ✅ Senhas são **criptografadas automaticamente** via BCrypt
- ✅ Passa pelas validações do backend (UserService, EventService)
- ✅ Funciona nativamente no Windows PowerShell
- ✅ Carteiras criadas automaticamente via trigger do banco

```powershell
# PowerShell (Windows) - popular via API
.\populate-data.ps1
```

**O script faz:**

- Cria 5 usuários com senhas criptografadas (BCrypt)
- Cria 5 eventos (3 presenciais, 2 EAD)
- Cria 6 inscrições em eventos
- Exibe resumo detalhado com avisos e erros

**Pré-requisitos:**

- Backend rodando: `docker compose up -d`
- Porta 8081 acessível

---

#### ⚠️ Método Alternativo: SQL Direto (sem criptografia)

**Atenção:** Este método insere dados diretamente no banco **SEM** criptografar senhas. Use apenas para testes básicos de estrutura.

```bash
# Unix style
docker compose exec -T db psql -h localhost -U admin -d meu_banco < db/seed-data.sql

# PowerShell nativo: pipe do arquivo para o psql dentro do container
Get-Content db/seed-data.sql -Raw | docker compose exec -T db psql -h localhost -U admin -d meu_banco
```

**O seed (db/seed-data.sql) faz:**

- ✅ Insere 5 usuários (⚠️ **senhas em texto plano**)
- ✅ Insere 5 eventos (presenciais e EAD)
- ✅ Cria carteiras automaticamente (via trigger)
- ✅ Insere 6 inscrições em eventos
- ✅ NÃO remove dados existentes (usa `ON CONFLICT DO NOTHING`)
- ✅ Exibe resumo com total de registros

**Usuários criados:**

- João Silva (criador de eventos)
- Maria Santos
- Pedro Oliveira
- Ana Costa
- Carlos Souza (admin)

**Eventos criados:**

- Workshop de Java (presencial, 100 vagas)
- Conferência de DevOps (presencial, 200 vagas)
- Hackathon 2025 (presencial, 50 vagas)
- Meetup de Spring Boot (EAD, 500 vagas)
- Curso de Docker (presencial, 30 vagas)

### Inspecionar os dados do banco

Use o **Supabase Studio** (`http://localhost:8000`, login `DASHBOARD_USERNAME`/`DASHBOARD_PASSWORD` de
`Projeto/supabase/.env`): o Table Editor mostra as tabelas do schema `public`. Para SQL avulso, use as
consultas abaixo. (O antigo `export-data.sh` foi removido: estava quebrado desde a #5 e o Studio cobre o uso.)

### Consultas Úteis

```bash
# Contar registros em uma tabela
docker compose exec db psql -h localhost -U admin -d meu_banco \
  -c "SELECT COUNT(*) FROM identity.users;"

# Ver todos os usuários
docker compose exec db psql -h localhost -U admin -d meu_banco \
  -c "SELECT user_id, user_name, email FROM identity.users;"

# Limpar todas as tabelas (CUIDADO!). Pare antes backend, catalog, reservation e identity (relays/consumers). Uma ÚNICA instrução:
# catalog.event e reservation.eventcap andam juntos (id de evento reciclado não herda eventcap/reservas), e os event stores
# vão junto (senão o EventCreated/SlotReserved de um id reciclado bate no UNIQUE do stream antigo). Depois mova os consumer
# groups para o fim dos tópicos (ver o fim do run-tests.sh), senão a reconciliação recria eventcap de evento apagado. O RESTART IDENTITY
# recicla o user_id (identity.users): para nenhum token antigo valer como o usuário novo que ganhar o mesmo uid, apague também a chave
# do identity (docker run --rm -v gerenciador-eventos_identity-keys:/k eclipse-temurin:25-jre-jammy rm -f /k/jwt-signing-key.json) com o
# identity parado e suba identity e backend de novo (o backend guarda o JWKS em cache por até 24 h; reiniciado, ele busca o novo).
docker compose exec db psql -h localhost -U admin -d meu_banco \
  -c "TRUNCATE identity.users, identity.mywallet, identity.pending_verifications, identity.event_store, identity.event_outbox, identity.event_snapshot, identity.projection_checkpoint, event_store, event_outbox, event_snapshot, projection_checkpoint, catalog.event, catalog.eventpresenter, catalog.event_store, catalog.event_outbox, catalog.event_snapshot, catalog.projection_checkpoint, reservation.eventcap, reservation.walletevent, reservation.event_availability, reservation.idempotency_key, reservation.event_store, reservation.event_outbox, reservation.event_snapshot, reservation.projection_checkpoint RESTART IDENTITY CASCADE;"
```

---

## 📊 Logs e Monitoramento

```bash
# Ver logs em tempo real
docker compose logs -f

# Logs apenas do banco
docker compose logs -f db

# Logs apenas do backend
docker compose logs -f backend

# Ver últimas 50 linhas
docker compose logs --tail=50 db
```

---

## 🔧 Solução de Problemas

### ❌ Erro: Testes não conectam ao banco

**Problema:** `Connection refused` ou timeout

**Solução:**

```bash
# 1. Verificar se o banco está rodando
docker compose ps

# 2. Se não estiver, subir novamente
docker compose up -d

# 3. Aguardar inicialização (10-15 segundos)
sleep 15

# 4. Rodar testes
./run-tests.sh
```

### ❌ Erro: Tabelas não existem

**Problema:** `ERROR: relation "users" does not exist`

**Causa:** Scripts de inicialização não foram executados

**Solução:**

```bash
# Recriar banco completamente
docker compose down -v
docker compose up -d
sleep 15
./run-tests.sh
```

### ❌ Erro: Porta já está em uso

**Problema:** `port is already allocated`

**Solução:**

```bash
# Encontrar processo usando a porta
sudo lsof -i :5433  # ou :8081

# Parar containers conflitantes
docker compose down

# Ou mudar porta no docker-compose.yml
```

### 🔄 Resetar Ambiente Completamente

```bash
# Remover tudo e reconstruir
docker compose down -v
docker compose build --no-cache
docker compose up -d
sleep 15
./run-tests.sh
```

---

## 🎯 Workflows Comuns

### Desenvolvimento Diário

```bash
# Manhã: Subir ambiente
docker compose up -d

# Durante o dia: Testar mudanças
./run-tests.sh

# Noite: Parar ambiente
docker compose down
```

### Após Mudanças no Código

```bash
# Rebuild e testar
docker compose build
docker compose up -d
./run-tests.sh
```

### Após Mudanças no Banco

```bash
# Recriar banco e testar
docker compose down -v
docker compose up -d
sleep 15
./run-tests.sh
```

### Antes de um Commit

```bash
# Garantir que tudo funciona
docker compose down -v
docker compose build --no-cache
docker compose up -d
sleep 15
./run-tests.sh
```

---

## 📝 Notas Importantes

- **Volume Persistente:** O banco mantém dados entre reinicializações. Use `-v` para limpar.
- **Scripts de Init:** Executam apenas na primeira criação do volume.
- **Network:** `gerenciador-net` conecta todos os containers.
- **Variáveis de Ambiente:** Definidas no arquivo `.env` na raiz do projeto.

---

## 🆘 Ajuda Adicional

Se os problemas persistirem:

1. Verifique os logs: `docker compose logs`
2. Verifique o arquivo `.env` existe e está correto
3. Verifique se as portas 5433 e 8081 estão livres
4. Tente um reset completo (seção "Resetar Ambiente")

**Estrutura esperada do .env:**

```env
SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/meu_banco
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=senha123
```
