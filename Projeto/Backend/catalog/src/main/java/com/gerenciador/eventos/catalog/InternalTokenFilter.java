package com.gerenciador.eventos.catalog;

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
 * Autentica as chamadas internas {@code gateway -> catalog} (issue #35, mesmo desenho do reservation, #33): TODA requisição ao
 * serviço exige o header {@value #HEADER} com o token de serviço compartilhado ({@code INTERNAL_API_TOKEN}, o mesmo valor do lado do cliente
 * {@code catalog-client}); sem ele, ou com valor errado, responde 401 sem tocar no controller. O filtro NÃO filtra por caminho
 * (o serviço não tem rota pública; o healthcheck é TCP): um teste do {@code getRequestURI()} cru deixaria passar sem token
 * {@code /internal;x=1/...} e {@code /%69nternal/...}, que o Spring MVC normaliza (decodifica e tira o {@code ;}) para a mesma rota.
 * O {@code OncePerRequestFilter} já não roda de novo no dispatch de ERROR. A rede interna
 * ({@code gerenciador-net}, sem porta no host) já isola o serviço; o token é a segunda camada, para um container comprometido ou
 * uma porta publicada por engano não bastar para criar, editar ou apagar evento em nome de qualquer usuário (o catalog confia
 * no {@code creator_id} que o gateway manda: quem valida o JWT e o dono é o gateway).
 *
 * <p>O token NÃO tem valor default (nem no código, nem no compose): o serviço FALHA NO BOOT se a variável faltar. A comparação é em
 * tempo constante ({@link MessageDigest#isEqual}). Custo no caminho quente: a leitura de um header e essa comparação. O healthcheck
 * do container é TCP e não passa por aqui.</p>
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private final byte[] token;

    public InternalTokenFilter(@Value("${internal.api.token:}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_TOKEN não configurado: o serviço catalog não sobe sem o token de serviço "
                    + "que autentica as chamadas do gateway. Gere um valor aleatório (`openssl rand -hex 32`), ponha "
                    + "INTERNAL_API_TOKEN=<valor> no Projeto/.env e use o MESMO valor no backend (o compose repassa).");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
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
