package com.gerenciador.eventos.TestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Schemas alvo do harness de teste (propriedade {@code test.db.schemas}, default {@code public}).
 * Os schemas extras do Supabase (auth, storage, extensions...) não entram na lista padrão.
 */
public final class TestSchemas {

    /** Default do harness: o monólito (public) e os schemas dos serviços extraídos (notification, #32; reservation, #33). Cada serviço extraído acrescenta o seu. */
    public static final String DEFAULT = "public,notification,reservation";

    public static final String PROPERTY = "${test.db.schemas:" + DEFAULT + "}";

    // Identificador não é parametrizável em SQL: só aceita o que dispensa aspas.
    private static final Pattern NOME_VALIDO = Pattern.compile("[a-z_][a-z0-9_]*");

    private TestSchemas() {
    }

    /** Lista separada por vírgula -> schemas validados. Falha rápido em nome inválido ou lista vazia. */
    public static List<String> parse(String csv) {
        List<String> schemas = new ArrayList<>();
        for (String s : csv.split(",")) {
            String nome = s.trim();
            if (nome.isEmpty()) {
                continue;
            }
            if (!NOME_VALIDO.matcher(nome).matches()) {
                throw new IllegalArgumentException("Schema inválido em test.db.schemas: '" + nome + "'");
            }
            schemas.add(nome);
        }
        if (schemas.isEmpty()) {
            throw new IllegalArgumentException("test.db.schemas não pode ser vazia");
        }
        return schemas;
    }

    /**
     * Tabelas ordinárias dos schemas, já qualificadas e com quote_ident (ex.: {@code public.users}).
     * Ignora tabelas que pertencem a extensões.
     */
    public static List<String> tabelas(Connection conn, List<String> schemas) throws Exception {
        String sql = "select format('%I.%I', n.nspname, c.relname) from pg_class c "
                + "join pg_namespace n on n.oid = c.relnamespace "
                + "where n.nspname = any(?) and c.relkind in ('r', 'p') "
                + "and not exists (select 1 from pg_depend d where d.classid = 'pg_class'::regclass and d.objid = c.oid and d.deptype = 'e') "
                + "order by 1";
        List<String> tabelas = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("text", schemas.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tabelas.add(rs.getString(1));
                }
            }
        }
        return tabelas;
    }

    /** {@code TRUNCATE} de todas as tabelas dos schemas, ou {@code null} se não houver nenhuma. */
    public static String truncateSql(Connection conn, List<String> schemas) throws Exception {
        List<String> tabelas = tabelas(conn, schemas);
        return tabelas.isEmpty() ? null
                : "TRUNCATE TABLE " + String.join(", ", tabelas) + " RESTART IDENTITY CASCADE";
    }
}
