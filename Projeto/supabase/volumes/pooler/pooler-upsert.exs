# Variante LOCAL do pooler.exs do upstream (issue #19; o pooler.exs vendorizado não é editado e
# deixa de ser montado). Diferença única: o tenant é criado se não existe E ATUALIZADO se já
# existe. O upstream só faz create_tenant atrás de `if !get_tenant_by_external_id`, e o tenant
# persiste no banco _supabase (volume do db): mudar POOLER_DEFAULT_POOL_SIZE/POOLER_MAX_CLIENT_CONN
# e subir de novo não mudaria nada. Aqui o tamanho do pool e o limite de clientes valem a cada
# partida do container. Este script roda antes do `server` (nenhum cliente conectado), e o
# update_tenant (Supavisor 2.9.13) já limpa o cache e encerra os pools do tenant, então o novo
# tamanho vale para as conexões seguintes.
{:ok, _} = Application.ensure_all_started(:supavisor)

{:ok, version} =
  case Supavisor.Repo.query!("select version()") do
    %{rows: [[ver]]} -> Supavisor.Helpers.parse_pg_version(ver)
    _ -> nil
  end

params = %{
  "external_id" => System.get_env("POOLER_TENANT_ID"),
  "db_host" => System.get_env("POSTGRES_HOST") || "db",
  "db_port" => System.get_env("POSTGRES_PORT"),
  "db_database" => System.get_env("POSTGRES_DB"),
  "require_user" => false,
  "auth_query" => "SELECT * FROM pgbouncer.get_auth($1)",
  "default_max_clients" => System.get_env("POOLER_MAX_CLIENT_CONN"),
  "default_pool_size" => System.get_env("POOLER_DEFAULT_POOL_SIZE"),
  "default_parameter_status" => %{"server_version" => version},
  "users" => [%{
    "db_user" => "pgbouncer",
    "db_password" => System.get_env("POSTGRES_PASSWORD"),
    "mode_type" => System.get_env("POOLER_POOL_MODE"),
    "pool_size" => System.get_env("POOLER_DEFAULT_POOL_SIZE"),
    "is_manager" => true
  }]
}

case Supavisor.Tenants.get_tenant_by_external_id(params["external_id"]) do
  nil ->
    {:ok, _} = Supavisor.Tenants.create_tenant(params)

  tenant ->
    # Primeiro o usuário gerenciador (pgbouncer): update_tenant é quem invalida o cache.
    for %{is_manager: true} = user <- tenant.users do
      {:ok, _} =
        Supavisor.Tenants.update_user(user, %{
          "db_user_alias" => user.db_user_alias,
          "pool_size" => params["default_pool_size"],
          "mode_type" => params["users"] |> hd() |> Map.fetch!("mode_type")
        })
    end

    {:ok, _} =
      Supavisor.Tenants.update_tenant(tenant, %{
        "default_pool_size" => params["default_pool_size"],
        "default_max_clients" => params["default_max_clients"]
      })
end
