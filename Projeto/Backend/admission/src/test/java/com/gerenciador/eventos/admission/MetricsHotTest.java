package com.gerenciador.eventos.admission;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code GET /internal/metrics/hot} (issue #197, D5): o RPM do evento mais quente da janela ({@code adm:hot}), que o KEDA lê (scaler
 * {@code metrics-api}) para escalar o reservation. Sem a chave (nenhuma tentativa na janela) é {@code 200} com {@code 0}, NUNCA 404 (o KEDA, em
 * erro, congela as réplicas onde estão: um 404 impediria a descida). Redis fora => 503 (o KEDA congela: melhor que ler 0 e derrubar réplicas).
 * Só o controller aqui, com o Redis simulado; o token de métrica é do {@link TokenDeMetricaFilterTest}.
 */
class MetricsHotTest {

    private ValueOperations<String, String> valores;
    private MockMvc mvc;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void montar() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        valores = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valores);
        mvc = MockMvcBuilders.standaloneSetup(new MetricsController(redis)).setControllerAdvice(new InternalApiExceptionHandler()).build();
    }

    @Test
    void devolveOHotDaChave() throws Exception {
        when(valores.get(AdmissionStore.CHAVE_QUENTE)).thenReturn("1234");

        mvc.perform(get("/internal/metrics/hot")).andExpect(status().isOk()).andExpect(jsonPath("$.rpm").value(1234));
    }

    @Test
    void semAChaveDevolve200ComZeroNuncaNotFound() throws Exception {
        when(valores.get(AdmissionStore.CHAVE_QUENTE)).thenReturn(null);

        mvc.perform(get("/internal/metrics/hot")).andExpect(status().isOk()).andExpect(content().json("{\"rpm\":0}"));
    }

    @Test
    void valorQueNaoEInteiroVale0() throws Exception {
        when(valores.get(AdmissionStore.CHAVE_QUENTE)).thenReturn("lixo");

        mvc.perform(get("/internal/metrics/hot")).andExpect(status().isOk()).andExpect(jsonPath("$.rpm").value(0));
    }

    @Test
    void redisForaVira503ParaOKedaCongelarAsReplicas() throws Exception {
        when(valores.get(AdmissionStore.CHAVE_QUENTE)).thenThrow(new QueryTimeoutException("travado"));

        mvc.perform(get("/internal/metrics/hot")).andExpect(status().isServiceUnavailable());
    }
}
