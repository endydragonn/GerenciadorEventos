package com.gerenciador.eventos.catalog;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gerenciador.eventos.catalogclient.api.Event;
import com.gerenciador.eventos.catalogclient.api.EventApi;
import com.gerenciador.eventos.catalogclient.api.EventoNaoEncontradoException;

/**
 * API interna do catalog (issue #35): espelha o {@link EventApi} 1:1, um endpoint por método, chamada só pelo gateway (pelo
 * {@code CatalogClient}, com {@code X-Internal-Token}). O {@code creator_id} vem no corpo e é confiado: quem o amarra ao token
 * e confere o dono é o gateway. Status de erro em {@link InternalApiExceptionHandler}.
 */
@RestController
@RequestMapping("/internal/events")
public class EventController {

    private final EventApi events;

    public EventController(EventApi events) {
        this.events = events;
    }

    @PostMapping
    public ResponseEntity<Event> criar(@RequestBody Event e) {
        return ResponseEntity.status(HttpStatus.CREATED).body(events.createEvent(e));
    }

    @PutMapping("/{id}")
    public Event atualizar(@PathVariable Long id, @RequestBody Event e) {
        if (e.getEvent_id() == null) {
            e.setEvent_id(id);
        } else if (!id.equals(e.getEvent_id())) {
            throw new IllegalArgumentException("ID do path difere do body");
        }
        return events.updateEvent(e);
    }

    @GetMapping("/{id}")
    public Event buscar(@PathVariable Long id) {
        return events.findById(id);
    }

    /** Inexistente é no-op (204), como o {@code EventApi.delete}; o 404 do {@code DELETE /bff/events/{id}} é do gateway. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> apagar(@PathVariable Long id) {
        events.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Erros de validação (texto; vazio = válido). */
    @PostMapping("/validate")
    public ResponseEntity<String> validar(@RequestBody Event e) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(events.getValidationErrors(e));
    }

    @GetMapping("/search")
    public List<Event> buscarPorNome(@RequestParam(required = false) String term) {
        return events.searchEvents(term);
    }

    @GetMapping
    public List<Event> doCriador(@RequestParam Long creatorId) {
        return events.findByCreatorId(creatorId);
    }

    /** {@code SELECT 1} (caminho quente da reserva): 200 com {@code true}/{@code false}, nunca 404. */
    @GetMapping("/{id}/exists")
    public boolean existe(@PathVariable Long id) {
        return events.exists(id);
    }

    /** Só o {@code creator_id} (checagem de dono do gateway); 404 se o evento não existe. */
    @GetMapping("/{id}/creator")
    public Long criador(@PathVariable Long id) {
        Long criador = events.findCreatorId(id);
        if (criador == null) {
            throw new EventoNaoEncontradoException(id);
        }
        return criador;
    }

    /** Bytes da imagem; 404 se o evento não existe ou não tem imagem. */
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> imagem(@PathVariable Long id) {
        byte[] imagem = events.findImage(id);
        if (imagem == null || imagem.length == 0) {
            throw new EventoNaoEncontradoException("Evento não encontrado ou sem imagem: id=" + id);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(imagem);
    }

    /** Grava a imagem (bytes). Corpo vazio é recusado (400): apagar a imagem não faz parte do contrato. */
    @PostMapping(value = "/{id}/image", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> gravarImagem(@PathVariable Long id, @RequestBody byte[] imagem) {
        events.saveImage(id, imagem);
        return ResponseEntity.noContent().build();
    }
}
