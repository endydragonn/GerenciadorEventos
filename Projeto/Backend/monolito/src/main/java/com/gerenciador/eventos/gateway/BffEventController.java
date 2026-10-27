package com.gerenciador.eventos.gateway;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

import com.gerenciador.eventos.catalogclient.api.CatalogUnavailableException;
import com.gerenciador.eventos.catalogclient.api.Event;
import com.gerenciador.eventos.catalogclient.api.EventApi;
import com.gerenciador.eventos.identity.api.HostAuthorizationApi;
import com.gerenciador.eventos.identityclient.api.UserApi;

/**
 * Camada BFF única para eventos: CRUD, validate, search e upload/serve de imagem.
 * Antes desta issue (#78) esta lógica vivia em {@code bff.BffApplication.EventBffController}
 * (a que o Flutter efetivamente chamava) e era duplicada em {@code Controller.EventController}
 * sob {@code /api/events} (sem chamador no frontend). Comportamento inalterado.
 *
 * <p>PR #79 (issue #78): criar/editar/excluir evento agora exige que o usuário autenticado seja host
 * ({@link HostAuthorizationApi#ensureUserIsHost()}) — antes qualquer usuário logado podia.
 * Leitura (get/search/list/validate) e a imagem (upload/get) não ganham esse gate.</p>
 *
 * <p>Issue #35 (fatia 1): o {@code creator_id} é amarrado ao token. No POST ele é o usuário do token (o do corpo é
 * ignorado); no PUT vale o gravado; PUT, DELETE e upload de imagem exigem o dono ({@code creator_id} do evento == usuário do
 * token), senão 403, e id inexistente dá 404 (o DELETE dava 204). <b>Sem exceção de admin</b>: {@code users.admin} é
 * gravável pelo próprio usuário. Toda resposta de {@link Event} ganha {@code creator_name} ({@link CreatorNames}); as listas
 * não trazem {@code image_data} (continua no GET por id e em {@code /image}).</p>
 *
 * <p>Fatia 3: o {@link EventApi} é o {@code CatalogClient} (HTTP para o serviço catalog). Catalog fora => 503 genérico
 * ({@link CatalogIndisponivelHandler}); os {@code catch (RuntimeException)} abaixo relançam a {@link CatalogUnavailableException}
 * antes de virar 404/500. Criar com o catalog fora/lento => 503 "resultado incerto" (o evento pode ter sido gravado).</p>
 */
@RestController
@RequestMapping("/bff/events")
public class BffEventController {

    private static final Logger log = LoggerFactory.getLogger(BffEventController.class);

    private final EventApi eventService;
    private final HostAuthorizationApi authorizationUtil;
    private final UserApi userApi;
    private final CreatorNames creatorNames;

    public BffEventController(EventApi eventService, HostAuthorizationApi authorizationUtil, UserApi userApi,
            CreatorNames creatorNames) {
        this.eventService = eventService;
        this.authorizationUtil = authorizationUtil;
        this.userApi = userApi;
        this.creatorNames = creatorNames;
    }

    @PostMapping
    public ResponseEntity<?> createEvent(@RequestBody Event event) {
        try {
            authorizationUtil.ensureUserIsHost();
            // o criador é o usuário do token (existe por construção); o creator_id do corpo é ignorado
            event.setCreator_id(authorizationUtil.currentUserId());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
        List<String> basicErrors = validateEventBasic(event);
        if (!basicErrors.isEmpty()) {
            return ResponseEntity.badRequest().body(String.join("; ", basicErrors));
        }
        try {
            Event saved = eventService.createEvent(event);
            return ResponseEntity.status(HttpStatus.CREATED).body(creatorNames.preencher(saved));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (CatalogUnavailableException ex) { // timeout pode ser depois do commit: o evento talvez exista
            return CatalogIndisponivelHandler.resposta(ex, CatalogIndisponivelHandler.CRIACAO_INCERTA);
        } catch (RuntimeException ex) {
            log.error("Erro ao criar evento", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErroInterno.MENSAGEM);
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validateEvent(@RequestBody Event event) {
        List<String> errors = validateEventBasic(event);
        String serviceErrors = eventService.getValidationErrors(event);
        // o criador é do identity: conferido aqui (o catalog só vê o id), com a mesma mensagem de antes
        if (event.getCreator_id() != null && !userApi.exists(event.getCreator_id())) {
            serviceErrors = (serviceErrors + " creator_id não existe em users.").trim();
        }
        if (!serviceErrors.isBlank()) {
            errors.add(serviceErrors);
        }
        if (errors.isEmpty()) {
            return ResponseEntity.ok("Evento válido");
        }
        return ResponseEntity.badRequest().body(String.join("; ", errors));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getEventById(@PathVariable Long id) {
        try {
            Event event = eventService.findById(id);
            return ResponseEntity.ok(creatorNames.preencher(event));
        } catch (CatalogUnavailableException ex) {
            throw ex; // 503 genérico (CatalogIndisponivelHandler), nunca o 404/500 abaixo
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateEvent(@PathVariable Long id, @RequestBody Event event) {
        Long usuario;
        try {
            authorizationUtil.ensureUserIsHost();
            usuario = authorizationUtil.currentUserId();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
        if (event.getEvent_id() == null) {
            event.setEvent_id(id);
        } else if (!id.equals(event.getEvent_id())) {
            return ResponseEntity.badRequest().body("ID do path difere do body");
        }
        try {
            Long criador = eventService.findCreatorId(id); // leve: só o creator_id, sem blob nem hidratação
            ResponseEntity<?> negado = negarSeNaoDono(criador, usuario);
            if (negado != null) {
                return negado;
            }
            event.setCreator_id(criador); // o creator_id do corpo é ignorado: vale o gravado
            List<String> basicErrors = validateEventBasic(event);
            if (!basicErrors.isEmpty()) {
                return ResponseEntity.badRequest().body(String.join("; ", basicErrors));
            }
            Event updated = eventService.updateEvent(event);
            return ResponseEntity.ok(creatorNames.preencher(updated));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (CatalogUnavailableException ex) {
            throw ex; // 503 genérico (CatalogIndisponivelHandler), nunca o 404/500 abaixo
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("Evento não encontrado")) {
                return ResponseEntity.notFound().build();
            }
            // Não ecoa ex.getMessage() no corpo do 500 (pode vazar detalhe interno, ex.:
            // mensagem de erro SQL) -- achado da review automática do Gemini na PR #88.
            // Log completo fica só no servidor; o chamador recebe mensagem genérica.
            log.error("Erro ao atualizar evento id={}", id, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErroInterno.MENSAGEM);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteEvent(@PathVariable Long id) {
        Long usuario;
        try {
            authorizationUtil.ensureUserIsHost();
            usuario = authorizationUtil.currentUserId();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
        try {
            ResponseEntity<?> negado = negarSeNaoDono(eventService.findCreatorId(id), usuario); // inexistente: 404 (era 204)
            if (negado != null) {
                return negado;
            }
            eventService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (CatalogUnavailableException ex) {
            throw ex; // 503 genérico (CatalogIndisponivelHandler), nunca o 404/500 abaixo
        } catch (RuntimeException ex) {
            log.error("Erro ao excluir evento id={}", id, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErroInterno.MENSAGEM);
        }
    }

    @GetMapping("/search")
    public ResponseEntity<List<Event>> searchEvents(@RequestParam String term) {
        return ResponseEntity.ok(creatorNames.preencher(eventService.searchEvents(term)));
    }

    @GetMapping
    public ResponseEntity<List<Event>> getEventsByCreator(@RequestParam(required = false) Long creator_id) {
        if (creator_id == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            List<Event> events = eventService.findByCreatorId(creator_id);
            return ResponseEntity.ok(creatorNames.preencher(events));
        } catch (CatalogUnavailableException ex) {
            throw ex; // 503 genérico (CatalogIndisponivelHandler), nunca o 404/500 abaixo
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @PostMapping(value = "/{id}/image", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadEventImage(
            @PathVariable Long id,
            @RequestParam("image") org.springframework.web.multipart.MultipartFile file) {
        try {
            // Só o dono troca a imagem (não exige host): inexistente 404, outro usuário 403
            ResponseEntity<?> negado = negarSeNaoDono(eventService.findCreatorId(id), authorizationUtil.currentUserId());
            if (negado != null) {
                return negado;
            }

            // Validar tamanho do arquivo (max 5MB)
            if (file.getSize() > 5 * 1024 * 1024) {
                return ResponseEntity.badRequest().body("Imagem muito grande. Tamanho máximo: 5MB");
            }

            // Validar tipo do arquivo
            String contentType = file.getContentType();
            if (contentType == null) {
                return ResponseEntity.badRequest().body("Tipo de arquivo não identificado");
            }
            if (!contentType.startsWith("image/")) {
                return ResponseEntity.badRequest().body("Arquivo deve ser uma imagem");
            }

            // Ler e converter para JPG
            byte[] imageBytes = convertToJpg(file.getBytes());

            eventService.saveImage(id, imageBytes); // método próprio, fora do updateEvent (issue #35)

            return ResponseEntity.ok("Imagem carregada com sucesso");
        } catch (CatalogUnavailableException ex) {
            throw ex; // 503 genérico (CatalogIndisponivelHandler), nunca o 404/500 abaixo
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        } catch (Exception ex) {
            log.error("Erro ao carregar imagem do evento id={}", id, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErroInterno.MENSAGEM);
        }
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> getEventImage(@PathVariable Long id) {
        try {
            byte[] imageData = eventService.findImage(id); // só o blob, sem hidratar o evento

            if (imageData == null || imageData.length == 0) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .header("Content-Type", "image/jpeg")
                    .header("Cache-Control", "max-age=3600")
                    .body(imageData);
        } catch (CatalogUnavailableException ex) {
            throw ex; // 503 genérico (CatalogIndisponivelHandler), nunca o 404/500 abaixo
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 404 se o evento não existe ({@code criador} nulo), 403 se o usuário do token não é o criador; {@code null} = é o dono, pode
     * seguir. O criador vem de {@link EventApi#findCreatorId} (só a coluna; o {@code findById} trazia o blob e hidratava o evento).
     */
    private static ResponseEntity<?> negarSeNaoDono(Long criador, Long usuario) {
        if (criador == null) {
            return ResponseEntity.notFound().build();
        }
        if (!criador.equals(usuario)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Apenas o criador do evento pode alterá-lo");
        }
        return null;
    }

    private byte[] convertToJpg(byte[] inputBytes) throws Exception {
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(inputBytes);
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(bais);

        if (image == null) {
            throw new IllegalArgumentException("Não foi possível ler a imagem");
        }

        // Converter para RGB se necessário (remover alpha channel)
        java.awt.image.BufferedImage rgbImage = new java.awt.image.BufferedImage(
                image.getWidth(), image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        rgbImage.createGraphics().drawImage(image, 0, 0, java.awt.Color.WHITE, null);

        // Escrever como JPG com compressão
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageWriteParam jpgWriteParam = javax.imageio.ImageIO
                .getImageWritersByFormatName("jpg").next().getDefaultWriteParam();
        jpgWriteParam.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        jpgWriteParam.setCompressionQuality(0.85f);

        javax.imageio.ImageWriter writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpg").next();
        writer.setOutput(new javax.imageio.stream.MemoryCacheImageOutputStream(baos));
        writer.write(null, new javax.imageio.IIOImage(rgbImage, null, null), jpgWriteParam);
        writer.dispose();

        return baos.toByteArray();
    }

    private List<String> validateEventBasic(Event e) {
        List<String> errors = new ArrayList<>();
        if (e.getEvent_name() == null || e.getEvent_name().isBlank()) {
            errors.add("Nome do evento não pode ser vazio");
        }
        if (e.getIs_EAD() == null) {
            errors.add("Campo is_EAD deve ser true/false");
        }
        if (Boolean.FALSE.equals(e.getIs_EAD())) {
            if (e.getAddress() == null || e.getAddress().isBlank()) {
                errors.add("Endereço é obrigatório para evento presencial");
            }
        }
        if (e.getEvent_date() == null) {
            errors.add("Data do evento é obrigatória");
        }
        if (e.getBuy_time_limit() != null && e.getEvent_date() != null
                && e.getBuy_time_limit().isAfter(e.getEvent_date())) {
            errors.add("Data limite de compra não pode ser após a data do evento");
        }
        if (e.getLot_quantity() == null || (e.getLot_quantity() < 1 && e.getLot_quantity() != -1)) {
            errors.add("Capacidade (lot_quantity) é obrigatória: >= 1, ou -1 para ilimitado");
        }
        if (e.getPresenters() == null) {
            errors.add("Lista de apresentadores não pode ser nula (pode ser vazia)");
        }
        return errors;
    }
}
