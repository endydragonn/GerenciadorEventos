-- Criar banco de dados se não existir
-- Este script roda como superuser durante a inicialização

SELECT 'CREATE DATABASE meu_banco' 
WHERE NOT EXISTS (
    SELECT FROM pg_database 
    WHERE datname = 'meu_banco'
)\gexec
