package com.gerenciador.eventos.identityclient;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.web.client.RestClientException;

import com.gerenciador.eventos.identityclient.api.IdentityNaoEncontradoException;
import com.gerenciador.eventos.identityclient.api.IdentityUnavailableException;

/** Tradução de erros HTTP e utilitários do {@link IdentityClient} (separado dele só pelo teto de linhas por arquivo). */
final class IdentityErros {

    private IdentityErros() {
    }

    /** 401 com o marcador de credenciais; interna: login/verify-MFA a tratam, qualquer outra rota a vê como indisponível. */
    static final class CredenciaisInvalidas extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CredenciaisInvalidas(String mensagem) {
            super(mensagem);
        }
    }

    /** Falha de rede, timeout ou resposta ilegível (o RestClient as lança como {@link RestClientException}) => indisponível. */
    static <T> T tentar(Supplier<T> chamada) {
        try {
            return chamada.get();
        } catch (RestClientException ex) {
            throw new IdentityUnavailableException("Serviço identity indisponível: " + ex.getMessage(), ex);
        }
    }

    /** {@link #tentar} para as rotas sem credencial: um 401 marcado ali é anomalia, não "senha errada". */
    static <T> T chamar(Supplier<T> chamada) {
        try {
            return tentar(chamada);
        } catch (CredenciaisInvalidas ex) {
            throw new IdentityUnavailableException("Serviço identity respondeu 401 de credenciais numa rota sem credencial", ex);
        }
    }

    /** Corpo vazio onde o contrato promete um corpo = resposta ilegível = indisponível (nunca {@code null}/{@code false}). */
    static <T> T corpo(T valor) {
        if (valor == null) {
            throw new IdentityUnavailableException("Serviço identity respondeu sem o corpo esperado", null);
        }
        return valor;
    }

    static void validarUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId inválido");
        }
    }

    /** Pares chave/valor (aceita {@code null}, que o {@code Map.of} recusa; o serviço valida os obrigatórios). */
    static Map<String, String> mapa(String... paresChaveValor) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < paresChaveValor.length; i += 2) {
            m.put(paresChaveValor[i], paresChaveValor[i + 1]);
        }
        return m;
    }

    /** Os booleanos de marcador: {@code naoEncontrado} = 404 do identity; {@code credenciais} = 401 de login/MFA. */
    static RuntimeException erro(int status, boolean naoEncontrado, boolean credenciais, String mensagem) {
        return switch (status) {
            case 400 -> new IllegalArgumentException(mensagem);
            case 401 -> credenciais ? new CredenciaisInvalidas(mensagem)
                    : new IdentityUnavailableException("Serviço identity respondeu 401 sem o marcador de credenciais "
                            + "(token interno errado?)", null);
            case 404 -> naoEncontrado ? new IdentityNaoEncontradoException(mensagem)
                    : new IdentityUnavailableException("Serviço identity respondeu 404 sem o marcador de recurso inexistente",
                            null);
            default -> new IdentityUnavailableException("Serviço identity respondeu HTTP " + status + ": " + mensagem, null);
        };
    }
}
