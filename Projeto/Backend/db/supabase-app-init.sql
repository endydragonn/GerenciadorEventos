-- Entrada do schema do app no supabase/postgres (issue #18).
--
-- Montado em /docker-entrypoint-initdb.d/migrations/99-app.sql: o migrate.sh da imagem roda
-- migrations/*.sql em ordem lexical como supabase_admin, e o prefixo 99 garante que isto
-- rode DEPOIS de todas as migrations do próprio Supabase. O banco (POSTGRES_DB=meu_banco) já
-- nasce criado (o antigo 00-create-db.sql foi removido).
--
-- 1) cria a role do app (APP_DB_USER/APP_DB_PASSWORD vêm do ambiente do container, que o
--    compose interpola do Projeto/.env) como LOGIN, SEM superuser (menor privilégio), e a torna
--    dona do banco: no PG15+ o schema public pertence ao dono do banco (pg_database_owner), então
--    isso dá à role CREATE no public e no banco (o TestSchemasTest cria um schema temporário);
-- 2) assume essa role para as tabelas/funções nascerem com dono dela;
-- 3) inclui os init-scripts do app, montados fora de /docker-entrypoint-initdb.d em /app-init
--    (para a imagem não rodar cada um deles sozinha, com o dono errado).

\set app_user `echo "$APP_DB_USER"`
\set app_password `echo "$APP_DB_PASSWORD"`

SELECT (:'app_user' = '' OR :'app_password' = '') AS sem_credenciais \gset
\if :sem_credenciais
    DO $$ BEGIN RAISE EXCEPTION 'APP_DB_USER/APP_DB_PASSWORD vazios no ambiente do container db'; END $$;
\endif

SELECT NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app_user') AS criar_role \gset
\if :criar_role
    CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';
\endif

SELECT current_database() AS app_db \gset
ALTER DATABASE :"app_db" OWNER TO :"app_user";

SET ROLE :"app_user";

\i /app-init/11-event-store.sql
\i /app-init/12-notification.sql
\i /app-init/13-reservation.sql
-- catalog (#35): event/eventpresenter e o event store do serviço, no schema catalog (substituiu o 03-event e o 05-eventpresenter)
\i /app-init/14-catalog.sql
-- identity (#36): users/mywallet/pending_verifications + event store do serviço, no schema identity (substituiu o 01-functions,
-- 02-users, 06-mywallet e 08-pending-verifications: nenhum usuário mora mais em public)
\i /app-init/15-identity.sql
-- stock (#47): eventcap + ledger de holds da saga stock + wallet, no schema stock (coexiste com reservation.eventcap até a passada a2)
\i /app-init/16-stock.sql
\i /app-init/99-tests.sql

RESET ROLE;
