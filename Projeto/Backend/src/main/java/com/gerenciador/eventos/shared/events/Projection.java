package com.gerenciador.eventos.shared.events;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Uma projeção construída a partir do {@code event_store}: aplica um evento nas tabelas dela. Serve ao consumer
 * idempotente (tabelas vivas, schema {@code public}) e ao replay (cópias no schema-sombra), com o MESMO código.
 *
 * <p>Contrato: {@link #apply} só grava na {@code conn} recebida (a transação é de quem chama), qualifica as tabelas
 * com o {@code schema} recebido e é determinístico (não chama {@code now()}; os valores vêm no evento).</p>
 */
public interface Projection {

    /** Nome estável da projeção: chave em {@code projection_checkpoint.projecao}. */
    String name();

    /** Tabelas vivas (em {@code public}) que o replay copia como sombra e compara com a viva. */
    List<String> tables();

    /** Aplica o evento nas tabelas de {@code schema}. */
    void apply(Connection conn, EventEnvelope event, String schema) throws SQLException;
}
