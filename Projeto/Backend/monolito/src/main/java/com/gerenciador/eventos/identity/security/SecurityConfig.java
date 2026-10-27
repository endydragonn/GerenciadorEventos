package com.gerenciador.eventos.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Superfície HTTP única sob /bff/** (issue #78). A partir do PR #79 (issue #78),
                // deny-by-default também dentro de /bff/**: só a lista explícita abaixo é
                // pública (o que o Flutter chama sem login); todo o resto exige
                // "Authorization: Bearer <jwt>" válido.
                //
                // CORS: o bean único é o shared/CorsConfig (issue #133, origens de CORS_ALLOWED_ORIGINS).
                //
                // Preflight CORS (OPTIONS) liberado explicitamente ANTES das regras por
                // verbo. Verificado empiricamente (HttpContractTest#corsPreflight_...) que o
                // CorsFilter que .cors(...) registra no filter chain já intercepta e responde
                // o preflight ANTES do AuthorizationFilter — hoje um OPTIONS de preflight já
                // não cai em .authenticated(), mesmo sem esta regra. Mantida do mesmo jeito
                // como defesa em profundidade e documentação explícita da intenção: se algum
                // dia o CORS for removido/reordenado, esta linha continua garantindo
                // que o preflight não seja barrado por auth. OPTIONS nunca carrega
                // credenciais/dados sensíveis, então liberar por verbo (não por path) é seguro.
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/bff/users/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/bff/users/request-verification").permitAll()
                .requestMatchers(HttpMethod.POST, "/bff/users/verify-email").permitAll()
                .requestMatchers(HttpMethod.POST, "/bff/users/resend-verification-code").permitAll()
                .requestMatchers(HttpMethod.POST, "/bff/users/verify-recaptcha").permitAll()
                .requestMatchers(HttpMethod.GET, "/bff/events", "/bff/events/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/bff/seed").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers("/bff/**").authenticated()
                .anyRequest().authenticated()
            )
            // Sem httpBasic()/formLogin() não há AuthenticationEntryPoint registrado por
            // outro configurer, e o fallback do Spring Security (Http403ForbiddenEntryPoint)
            // devolveria 403 em vez de 401 para requisição sem token numa rota protegida.
            // Fixa 401 (classe pronta do Spring, não é handler customizado de negócio).
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
