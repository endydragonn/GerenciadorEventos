package com.gerenciador.eventos.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Serviço {@code identity} (issue #36, Fase 7: quarta extração): dono de {@code users}, {@code mywallet} e
 * {@code pending_verifications} (schema {@code identity}, CRUD, não event sourcing), do login (BCrypt), do cadastro em duas fases,
 * do reset de senha com MFA, da troca de senha, do reCAPTCHA e da EMISSÃO do JWT (RS256, chave persistida, JWKS em
 * {@code /.well-known/jwks.json}). Também guarda o event store do serviço: o stream {@code identity.notification} (pedido de
 * e-mail, contrato congelado na #32) e o {@code identity.user} (ciclo de vida, payload só com ids). Expõe a API interna HTTP
 * ({@code /internal/**}) que o gateway do monólito chama pelo {@code IdentityClient}.
 *
 * <p>O component scan cobre este pacote e a lib {@code shared} (conexão, event store, relay; o {@code EventStore} aponta para o
 * schema {@code identity} por {@code events.schema}). O {@code identityclient} fica FORA do scan de propósito: o serviço não
 * monta um cliente de si mesmo. {@code @EnableScheduling} liga o relay do outbox e a retenção dos pedidos de e-mail.</p>
 */
@SpringBootApplication(scanBasePackages = {"com.gerenciador.eventos.identity", "com.gerenciador.eventos.shared"})
@EnableScheduling
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
