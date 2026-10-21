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

\i /app-init/01-functions.sql
\i /app-init/02-users.sql
\i /app-init/03-event.sql
\i /app-init/05-eventpresenter.sql
\i /app-init/06-mywallet.sql
\i /app-init/08-pending-verifications.sql
\i /app-init/11-event-store.sql
\i /app-init/12-notification.sql
\i /app-init/13-reservation.sql
\i /app-init/99-tests.sql

RESET ROLE;
