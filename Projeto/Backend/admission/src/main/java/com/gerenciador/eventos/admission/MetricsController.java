package com.gerenciador.eventos.admission;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /internal/metrics/hot} (issue #197, D5): o RPM do evento mais quente da janela atual ({@code adm:hot}, escrito pelo script do contador),
 * que o KEDA lê pelo scaler {@code metrics-api} para escalar o reservation (o KEDA não tem scaler que leia uma chave simples do Redis). Autenticado
 * pelo token SÓ de métrica ({@link InternalTokenFilter}). Sem a chave (nenhuma tentativa na janela) é {@code 200} com {@code 0}, NUNCA 404: o KEDA,
 * em erro, congela as réplicas onde estão, o que impediria a descida. Redis fora propaga {@code DataAccessException} (503): o KEDA congela, em vez
 * de ler 0 e derrubar réplicas.
 */
@RestController
@RequestMapping("/internal/metrics")
public class MetricsController {

    private final StringRedisTemplate redis;

    public MetricsController(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @GetMapping("/hot")
    public RpmQuente hot() {
        String valor = redis.opsForValue().get(AdmissionStore.CHAVE_QUENTE);
        long rpm = 0;
        if (valor != null) {
            try {
                rpm = Long.parseLong(valor.trim());
            } catch (NumberFormatException ex) {
                rpm = 0;
            }
        }
        return new RpmQuente(rpm);
    }

    /** Corpo de {@code GET /internal/metrics/hot}; o KEDA lê {@code rpm} ({@code valueLocation}). */
    public record RpmQuente(long rpm) { }
}
