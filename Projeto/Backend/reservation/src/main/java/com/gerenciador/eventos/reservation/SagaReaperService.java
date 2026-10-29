package com.gerenciador.eventos.reservation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.gerenciador.eventos.reservation.IdempotencyKeyRepository.Orfa;
import com.gerenciador.eventos.shared.DatabaseConnection;
import com.gerenciador.eventos.stockclient.StockClient;

/**
 * Reaper das sagas órfãs da reserva (issue #47): resolve a saga que o dono não terminou (processo morto entre o hold e a tx2, dono
 * mais lento que {@code ORPHAN_MS}) e a que ficou {@code compensating} porque o {@code release} não chegou ao stock.
 *
 * <p>Cada tick, em lotes de {@code reservation.saga.reaper.batch}:</p>
 * <ol>
 *   <li>uma transação seleciona as {@code reserving} paradas há mais de {@code reservation.saga.orphan-ms} OU qualquer
 *       {@code compensating}, as mais antigas primeiro, com {@code FOR UPDATE SKIP LOCKED} (vale para N réplicas, como a expiração);</li>
 *   <li>na mesma transação passa as {@code reserving} para {@code compensating} (por PK + {@code hold_id}) e comita: é isso que faz a
 *       tx2 atrasada do dono perder o CAS {@code reserving -> reserved};</li>
 *   <li>fora da transação, {@code release(holdId)} no stock com o evento e o usuário da linha; com 200 a linha vai a
 *       {@code compensated} ({@code resolved_by = reaper}); com falha fica {@code compensating} e entra no próximo tick.</li>
 * </ol>
 * Regra de ouro: nunca libera o hold de uma saga {@code reserved} (a seleção não a devolve e o CAS impede a corrida). Também não decide
 * nada pelo lado do stock nem pelo lag do tópico. Liga/desliga por {@code reservation.saga.reaper.enabled} (ligado por padrão).
 */
@Service
@ConditionalOnProperty(name = "reservation.saga.reaper.enabled", havingValue = "true", matchIfMissing = true)
public class SagaReaperService {

    private static final Logger log = LoggerFactory.getLogger(SagaReaperService.class);

    @Autowired
    private IdempotencyKeyRepository keys;
    @Autowired
    private DatabaseConnection databaseConnection;
    @Autowired
    private StockClient stockClient;

    /** Uma saga {@code reserving} é órfã depois disto sem mexer na linha (pool 5 s + resposta 10 s do cliente do stock + margem). */
    @Value("${reservation.saga.orphan-ms:30000}")
    private long orfaMs;

    @Value("${reservation.saga.reaper.batch:100}")
    private int lote;

    @Scheduled(fixedDelayString = "${reservation.saga.reaper.scan-ms:5000}", initialDelayString = "${reservation.saga.reaper.scan-ms:5000}")
    public void varrer() {
        try {
            executar();
        } catch (SQLException | RuntimeException ex) {
            log.warn("[SagaReaper] falha na varredura: {}", ex.toString());
        }
    }

    /**
     * Um tick (vários lotes enquanto vierem cheios e resolverem algo). Público para os testes chamarem sem esperar o timer.
     * @return quantas sagas este tick levou a {@code compensated}
     */
    public int executar() throws SQLException {
        int limite = Math.max(1, lote);
        int resolvidas = 0;
        int vistas = 0;
        int sobraram = 0;
        long maiorIdadeMs = 0;
        while (true) {
            List<Orfa> orfas = selecionarEAssumir(limite);
            int deste = 0;
            for (Orfa o : orfas) {
                maiorIdadeMs = Math.max(maiorIdadeMs, o.idadeMs());
                if (liberar(o)) {
                    deste++;
                } else {
                    sobraram++;
                }
            }
            vistas += orfas.size();
            resolvidas += deste;
            // um lote que não resolveu nada (stock fora) reselecionaria as mesmas linhas para sempre: o próximo tick tenta de novo
            if (orfas.size() < limite || deste == 0) {
                break;
            }
        }
        if (vistas > 0) {
            log.info("[SagaReaper] {} saga(s) órfã(s) vista(s), {} resolvida(s) (compensated), maior idade {} ms", vistas, resolvidas, maiorIdadeMs);
        }
        if (sobraram > 0) {
            log.warn("[SagaReaper] {} saga(s) seguem compensating (release no stock falhou ou outro reaper a resolveu); o próximo tick tenta de novo",
                    sobraram);
        }
        return resolvidas;
    }

    /** Transação 1: seleciona (SKIP LOCKED) e passa as {@code reserving} para {@code compensating}; comita antes de qualquer chamada ao stock. */
    private List<Orfa> selecionarEAssumir(int limite) throws SQLException {
        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<Orfa> orfas = keys.selecionarOrfas(conn, orfaMs, limite);
                for (Orfa o : orfas) {
                    if (IdempotencyKeyRepository.RESERVING.equals(o.status())) {
                        keys.paraCompensando(conn, o.chave(), o.userId(), o.holdId());
                    }
                }
                conn.commit();
                return orfas;
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** {@code release} no stock e {@code compensating -> compensated}. @return {@code true} se ESTE reaper fechou a saga */
    private boolean liberar(Orfa o) {
        try {
            stockClient.release(o.holdId(), o.eventId(), o.userId());
        } catch (RuntimeException ex) {
            log.warn("[SagaReaper] release do hold {} falhou ({}): a saga fica compensating", o.holdId(), ex.toString());
            return false;
        }
        return keys.compensada(o.chave(), o.userId(), o.holdId(), "reaper");
    }
}
