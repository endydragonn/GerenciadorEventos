package com.gerenciador.eventos.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * O token só de métrica (issue #197, P3): o KEDA chama {@code GET /internal/metrics/hot} com ele, e o {@code INTERNAL_API_TOKEN} mestre
 * nunca sai do namespace do serviço. O filtro exige, nessa rota e SÓ nela, o token de métrica (e nunca o mestre); em toda outra rota, o mestre
 * (e nunca o de métrica). A rota é decidida pelo método e pelo {@code getRequestURI()} CRU, igual a {@code /internal/metrics/hot}: qualquer variação
 * (barra final, {@code ;x=1}, {@code %68ot}) cai no ramo do token mestre, que o chamador não tem (fail-closed). Sem o token de métrica configurado
 * ({@code ADMISSION_METRICS_TOKEN} ausente) o serviço sobe normalmente e a rota responde 401 a tudo.
 */
class TokenDeMetricaFilterTest {

    private static final String MESTRE = "token-mestre";
    private static final String METRICA = "token-de-metrica";
    private static final String HOT = "/internal/metrics/hot";

    /** Código da resposta do filtro: 401 = barrou; 200 = a cadeia foi chamada (o filtro não escreve status ao deixar passar). */
    private static int codigo(InternalTokenFilter filtro, String metodo, String uri, String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(metodo, uri);
        req.setRequestURI(uri);
        if (token != null) {
            req.addHeader(InternalTokenFilter.HEADER, token);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain cadeia = new MockFilterChain();
        filtro.doFilter(req, res, cadeia);
        boolean passou = cadeia.getRequest() != null;
        assertTrue(passou == (res.getStatus() != 401), "passou=" + passou + " status=" + res.getStatus());
        return res.getStatus();
    }

    @Test
    void naRotaDaMetricaSoOTokenDeMetricaAbre() throws Exception {
        InternalTokenFilter f = new InternalTokenFilter(MESTRE, METRICA);

        assertEquals(200, codigo(f, "GET", HOT, METRICA));
        assertEquals(401, codigo(f, "GET", HOT, MESTRE), "o token mestre NÃO abre a rota da métrica");
        assertEquals(401, codigo(f, "GET", HOT, null));
        assertEquals(401, codigo(f, "GET", HOT, ""));
        assertEquals(401, codigo(f, "GET", HOT, "errado"));
    }

    @Test
    void oTokenDeMetricaNaoAbreNenhumaOutraRota() throws Exception {
        InternalTokenFilter f = new InternalTokenFilter(MESTRE, METRICA);

        for (String uri : new String[] {"/internal/admissions", "/internal/admissions/events/1/rpm", "/internal/admissions/result",
                "/internal/metrics", "/internal/metrics/hot/x", "/actuator/health"}) {
            assertEquals(401, codigo(f, "GET", uri, METRICA), uri);
            assertEquals(401, codigo(f, "POST", uri, METRICA), uri);
        }
        assertEquals(401, codigo(f, "POST", HOT, METRICA), "só o GET é a rota da métrica");
        assertEquals(401, codigo(f, "PUT", HOT, METRICA));
        assertEquals(401, codigo(f, "HEAD", HOT, METRICA));
    }

    @Test
    void variacoesDoCaminhoNaoAceitamOTokenDeMetrica() throws Exception {
        InternalTokenFilter f = new InternalTokenFilter(MESTRE, METRICA);

        // o Spring MVC normaliza (tira o ;x=1, decodifica %68, resolve //): o filtro só confia na igualdade exata do URI cru
        for (String uri : new String[] {HOT + "/", HOT + ";x=1", "/internal/metrics/%68ot", "/internal;x=1/metrics/hot", "/%69nternal/metrics/hot",
                "/internal/metrics//hot", "/INTERNAL/metrics/hot", "/internal/metrics/./hot"}) {
            assertEquals(401, codigo(f, "GET", uri, METRICA), uri);
        }
    }

    @Test
    void oTokenMestreSegueAbrindoAsOutrasRotas() throws Exception {
        InternalTokenFilter f = new InternalTokenFilter(MESTRE, METRICA);

        assertEquals(200, codigo(f, "POST", "/internal/admissions", MESTRE));
        assertEquals(200, codigo(f, "GET", "/internal/admissions/events/1/rpm", MESTRE));
        assertEquals(401, codigo(f, "POST", "/internal/admissions", null));
        assertEquals(401, codigo(f, "POST", "/internal/admissions", "errado"));
    }

    @Test
    void semOTokenDeMetricaConfiguradoARotaResponde401ATudoEOServicoSobe() throws Exception {
        for (String metrica : new String[] {"", "   ", null}) {
            InternalTokenFilter f = new InternalTokenFilter(MESTRE, metrica);

            for (String enviado : new String[] {null, "", "   ", METRICA, MESTRE}) {
                assertEquals(401, codigo(f, "GET", HOT, enviado), "metrica=[" + metrica + "] enviado=[" + enviado + "]");
            }
            assertEquals(200, codigo(f, "POST", "/internal/admissions", MESTRE), "as demais rotas seguem pelo token mestre");
        }
    }

    @Test
    void oConstrutorDeUmTokenNaoConfiguraAMetrica() throws Exception {
        InternalTokenFilter f = new InternalTokenFilter(MESTRE);

        assertEquals(401, codigo(f, "GET", HOT, MESTRE));
        assertEquals(401, codigo(f, "GET", HOT, ""));
        assertEquals(200, codigo(f, "GET", "/internal/admissions/events/1/rpm", MESTRE));
    }
}
