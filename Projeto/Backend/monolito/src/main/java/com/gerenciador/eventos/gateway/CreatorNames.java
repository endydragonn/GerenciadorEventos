package com.gerenciador.eventos.gateway;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gerenciador.eventos.catalog.api.Event;
import com.gerenciador.eventos.identity.api.UserApi;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

/**
 * Composição de {@code creator_name} nas respostas de evento (issue #35): o nome é do {@code identity}, o evento só guarda
 * {@code creator_id}. Uma consulta em lote ({@link UserApi#findNames}) para os ids que faltam no cache.
 *
 * <p>Cache Caffeine em memória: TTL de 45 s com jitter de ±20% por entrada ({@link Expiry} na criação, para as entradas
 * de uma mesma listagem não vencerem juntas), até ~10k entradas, <b>sem cache negativo</b> (id sem nome — conta desativada
 * ou inexistente — não entra no cache e é reconsultado na próxima vez). Um nome trocado ou uma conta desativada aparece na
 * resposta em até ~54 s.</p>
 *
 * <p>Falha do {@code findNames} (banco/identity fora) NÃO falha a resposta: o nome fica nulo, sem cache, e a próxima resposta
 * tenta de novo. O nome é decoração somente-leitura; derrubar por causa dele o GET (que viraria 404) ou a resposta de um POST/PUT
 * já comitado (que viraria 500 com o evento gravado) seria pior.</p>
 */
@Component
public class CreatorNames {

    private static final Logger log = LoggerFactory.getLogger(CreatorNames.class);

    static final Duration TTL = Duration.ofSeconds(45);
    static final int MAX_ENTRADAS = 10_000;

    private final UserApi userApi;
    private final Cache<Long, String> cache = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRADAS)
            .expireAfter(Expiry.creating((Long id, String nome) -> ttlComJitter()))
            .build();

    public CreatorNames(UserApi userApi) {
        this.userApi = userApi;
    }

    /** TTL sorteado em [36 s, 54 s] (45 s ±20%). */
    static Duration ttlComJitter() {
        long ms = TTL.toMillis();
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(ms * 8 / 10, ms * 12 / 10 + 1));
    }

    public Event preencher(Event evento) {
        if (evento != null) {
            preencher(List.of(evento));
        }
        return evento;
    }

    public List<Event> preencher(List<Event> eventos) {
        if (eventos == null || eventos.isEmpty()) {
            return eventos;
        }
        Set<Long> ids = eventos.stream().map(Event::getCreator_id).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> nomes = new HashMap<>(cache.getAllPresent(ids));
        Set<Long> faltam = new HashSet<>(ids);
        faltam.removeAll(nomes.keySet());
        if (!faltam.isEmpty()) {
            try {
                Map<Long, String> lidos = userApi.findNames(faltam); // só contas ativas; ausente = nome nulo, não cacheado
                cache.putAll(lidos);
                nomes.putAll(lidos);
            } catch (RuntimeException ex) {
                // O nome é decoração: falha do identity não vira 404 no GET nem 500 depois do commit do POST/PUT. Fica nulo
                // (como o de conta desativada) e nada entra no cache: a próxima resposta tenta de novo.
                log.warn("[CreatorNames] não consegui compor creator_name de {} criador(es); respondendo sem nome: {}",
                        faltam.size(), ex.toString());
            }
        }
        eventos.forEach(e -> e.setCreator_name(nomes.get(e.getCreator_id())));
        return eventos;
    }
}
