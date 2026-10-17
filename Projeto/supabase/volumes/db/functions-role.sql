-- Adição local (NÃO vem do upstream supabase/supabase).
--
-- O roles.sql vendorizado faz ALTER USER supabase_functions_admin, mas essa role é criada
-- pelo webhooks.sql do upstream (pg_net + schema supabase_functions), que não foi trazido
-- porque nada disso sobe aqui. Sem a role, o roles.sql aborta o init do db (issue #18).
-- Este arquivo cria só a role, com a mesma definição do upstream, e roda antes do roles.sql.
DO
$$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'supabase_functions_admin') THEN
    CREATE USER supabase_functions_admin NOINHERIT CREATEROLE LOGIN NOREPLICATION;
  END IF;
END
$$;
