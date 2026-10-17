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

# 4. (Opcional) Exportar dados para arquivo
wsl ./export-data.sh
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
# Opção 1 (Recomendado): Popular e já exportar para arquivo formatado (Unix / WSL / Git Bash)
./export-data.sh --populate

# Opção 1 (PowerShell - via WSL ou Git Bash)
# Use WSL (recomendado no Windows) ou Git Bash para executar o script sem alterações:
wsl ./export-data.sh --populate
# ou, se tiver Git Bash instalado
bash ./export-data.sh --populate

# Opção 2: Apenas popular (sem exportar) - Unix style
docker compose exec -T db psql -h localhost -U admin -d meu_banco < db/seed-data.sql

# Opção 2 (PowerShell nativo): pipe do arquivo para o psql dentro do container
Get-Content db/seed-data.sql -Raw | docker compose exec -T db psql -h localhost -U admin -d meu_banco

# (Opcional) Exportar depois (o script gera um arquivo com timestamp)
./export-data.sh

# Exportar em PowerShell (se preferir abrir o arquivo no Windows):
# - usando WSL/Git Bash (gera o arquivo no diretório do projeto)
wsl ./export-data.sh
# - ou executar o script via Git Bash
bash ./export-data.sh
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

### Exportar Dados do Banco para Arquivo

O script `export-data.sh` gera um relatório completo do banco em formato texto, **incluindo as senhas criptografadas (hash BCrypt)**.

```bash
# Unix / WSL / Git Bash
./export-data.sh

# PowerShell (via WSL - recomendado)
wsl ./export-data.sh

# PowerShell (via Git Bash)
bash ./export-data.sh
```

**O export inclui:**

- 👥 Usuários (com **senha hash BCrypt completa**)
- 🎫 Eventos
- 💰 Carteiras
- 📝 Inscrições
- 📊 Estatísticas
- 🏆 Eventos mais populares

**Arquivo gerado:** `database-export-<timestamp>.txt`

**Visualizar o arquivo:**

```powershell
# PowerShell - ver arquivo mais recente
Get-Content (Get-ChildItem database-export-*.txt | Sort-Object LastWriteTime -Descending | Select-Object -First 1).Name

# Abrir no VS Code
code (Get-ChildItem database-export-*.txt | Sort-Object LastWriteTime -Descending | Select-Object -First 1).Name
```

### Consultas Úteis

```bash
# Contar registros em uma tabela
docker compose exec db psql -h localhost -U admin -d meu_banco \
  -c "SELECT COUNT(*) FROM users;"

# Ver todos os usuários
docker compose exec db psql -h localhost -U admin -d meu_banco \
  -c "SELECT user_id, user_name, email FROM users;"

# Limpar todas as tabelas (CUIDADO!)
docker compose exec db psql -h localhost -U admin -d meu_banco \
  -c "TRUNCATE users, event, mywallet, walletevent RESTART IDENTITY CASCADE;"
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
