package com.gerenciador.eventos.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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
 * Autentica as chamadas internas {@code gateway -> identity} (issue #36, mesmo desenho do reservation e do catalog): TODA
 * requisição ao serviço exige o header {@value #HEADER} com o token de serviço compartilhado ({@code INTERNAL_API_TOKEN}, o mesmo
 * valor do lado do cliente {@code identity-client}); sem ele, ou com valor errado, responde 401 (sem o marcador de credenciais
 * inválidas, para o cliente tratar como falha de configuração e não como "senha errada") sem tocar no controller.
 *
 * <p><b>Única exceção: o JWKS.</b> {@code GET /.well-known/jwks.json} é isento, e SÓ a URI exata (comparação de igualdade do
 * {@code getRequestURI()} cru, sem prefixo nem normalização): {@code /.well-known/jwks.json;x} ou {@code /.well-known/%6awks.json}
 * não casam e caem no filtro (401). A chave pública não é segredo, e o endpoint nem é alcançável de fora da rede. Fora essa URI o filtro
 * NÃO filtra por caminho: um teste do {@code getRequestURI()} cru deixaria passar sem token {@code /internal;x=1/...} e
 * {@code /%69nternal/...}, que o Spring MVC normaliza (decodifica e tira o {@code ;}) para a mesma rota. O
 * {@code OncePerRequestFilter} já não roda de novo no dispatch de ERROR.</p>
 *
 * <p>O token NÃO tem valor default (nem no código, nem no compose): o serviço FALHA NO BOOT se a variável faltar. A comparação é em
 * tempo constante ({@link MessageDigest#isEqual}). O healthcheck do container é TCP e não passa por aqui.</p>
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    /** A única rota sem token: o JWKS (só a parte pública da chave de assinatura). */
    public static final String JWKS_PATH = "/.well-known/jwks.json";

    private final byte[] token;

    public InternalTokenFilter(@Value("${internal.api.token:}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_TOKEN não configurado: o serviço identity não sobe sem o token de serviço "
                    + "que autentica as chamadas do gateway. Gere um valor aleatório (`openssl rand -hex 32`), ponha "
                    + "INTERNAL_API_TOKEN=<valor> no Projeto/.env e use o MESMO valor no backend (o compose repassa).");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("GET".equals(request.getMethod()) && JWKS_PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String enviado = request.getHeader(HEADER);
        if (enviado == null || !MessageDigest.isEqual(token, enviado.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8");
            response.getWriter().write("Token interno ausente ou inválido");
            return;
        }
        chain.doFilter(request, response);
    }
}
