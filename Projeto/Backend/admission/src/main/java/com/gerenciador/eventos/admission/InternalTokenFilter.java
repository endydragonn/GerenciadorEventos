package com.gerenciador.eventos.admission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Autentica as chamadas internas {@code gateway -> admission} (issue #41, como o reservation da #33): TODA requisição ao serviço exige o header
 * {@value #HEADER} com o token de serviço compartilhado ({@code INTERNAL_API_TOKEN}, o mesmo valor do lado do cliente
 * {@code admission-client}); sem ele, ou com valor errado, responde 401 sem tocar no controller. O filtro NÃO filtra por caminho
 * (o serviço não tem rota pública; o healthcheck é TCP): um teste do {@code getRequestURI()} cru deixaria passar sem token
 * {@code /internal;x=1/...} e {@code /%69nternal/...}, que o Spring MVC normaliza (decodifica e tira o {@code ;}) para a mesma rota.
 * O {@code OncePerRequestFilter} já não roda de novo no dispatch de ERROR. A rede interna
 * ({@code gerenciador-net}, sem porta no host) já isola o serviço; o token é a segunda camada, para um container comprometido ou
 * uma porta publicada por engano não bastar para falsear o sinal de carga do contador.
 *
 * <p><b>Token só de métrica (issue #197, P3).</b> {@code GET /internal/metrics/hot} (o KEDA, no cluster Kubernetes) NÃO usa o token mestre: exige, nessa
 * rota e só nela, o {@code ADMISSION_METRICS_TOKEN} (outro valor, aleatório, gerado pelo {@code cluster.sh} a cada {@code up}), e o token mestre
 * NÃO abre a rota EXATA; em toda outra rota vale só o mestre, e o de métrica não abre nada. A rota é a de método {@code GET} e {@code getRequestURI()} CRU igual a
 * {@value #ROTA_METRICA_HOT}: qualquer variação ({@code HEAD}, {@code /}, {@code ;x=1}, {@code %68ot}) cai no ramo do mestre (fail-closed para o token de métrica; como o Spring MVC mapeia essas variações para o mesmo {@code @GetMapping}, o mestre as abre: impacto nulo, o mestre já é superconjunto e a rota só lê o RPM). Sem
 * {@code ADMISSION_METRICS_TOKEN} (compose) a rota responde 401 a tudo e o serviço sobe normalmente.</p>
 *
 * <p>O token NÃO tem valor default (nem no código, nem no compose): o serviço FALHA NO BOOT se a variável faltar. A comparação é em
 * tempo constante ({@link MessageDigest#isEqual}). Custo no caminho quente: a leitura de um header e essa comparação. O healthcheck
 * do container é TCP e não passa por aqui.</p>
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";
    static final String ROTA_METRICA_HOT = "/internal/metrics/hot";

    private final byte[] token;
    private final byte[] tokenMetrica;

    public InternalTokenFilter(String token) {
        this(token, "");
    }

    @Autowired
    public InternalTokenFilter(@Value("${internal.api.token:}") String token, @Value("${admission.metrics.token:}") String tokenMetrica) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_TOKEN não configurado: o serviço admission não sobe sem o token de serviço "
                    + "que autentica as chamadas do gateway. Gere um valor aleatório (`openssl rand -hex 32`), ponha "
                    + "INTERNAL_API_TOKEN=<valor> no Projeto/.env e use o MESMO valor no backend (o compose repassa).");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.tokenMetrica = tokenMetrica == null || tokenMetrica.isBlank() ? new byte[0] : tokenMetrica.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String enviado = request.getHeader(HEADER);
        boolean rotaDaMetrica = "GET".equals(request.getMethod()) && ROTA_METRICA_HOT.equals(request.getRequestURI());
        byte[] esperado = rotaDaMetrica ? tokenMetrica : token;
        // token de métrica não configurado (esperado vazio) nunca casa, nem com header vazio
        if (enviado == null || esperado.length == 0 || !MessageDigest.isEqual(esperado, enviado.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8");
            response.getWriter().write("Token interno ausente ou inválido");
            return;
        }
        chain.doFilter(request, response);
    }
}
