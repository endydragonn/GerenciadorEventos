package com.gerenciador.eventos.bff;

import java.util.ArrayList;
import java.util.List;

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

import com.gerenciador.eventos.POJO.Event;
import com.gerenciador.eventos.Service.EventService;
import com.gerenciador.eventos.security.AuthorizationUtil;

/**
 * Camada BFF única para eventos: CRUD, validate, search e upload/serve de imagem.
 * Antes desta issue (#78) esta lógica vivia em {@code bff.BffApplication.EventBffController}
 * (a que o Flutter efetivamente chamava) e era duplicada em {@code Controller.EventController}
 * sob {@code /api/events} (sem chamador no frontend). Comportamento inalterado.
 *
 * <p>Issue #79: criar/editar/excluir evento agora exige que o usuário autenticado seja host
 * ({@link AuthorizationUtil#ensureUserIsHost()}) — antes qualquer usuário logado podia.
 * Leitura (get/search/list/validate) e a imagem (upload/get) não ganham esse gate.</p>
 */
@RestController
@RequestMapping("/bff/events")
public class BffEventController {

    private final EventService eventService;
    private final AuthorizationUtil authorizationUtil;

    public BffEventController(EventService eventService, AuthorizationUtil authorizationUtil) {
        this.eventService = eventService;
        this.authorizationUtil = authorizationUtil;
    }

    @PostMapping
    public ResponseEntity<?> createEvent(@RequestBody Event event) {
        try {
            authorizationUtil.ensureUserIsHost();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
        List<String> basicErrors = validateEventBasic(event);
        if (!basicErrors.isEmpty()) {
            return ResponseEntity.badRequest().body(String.join("; ", basicErrors));
        }
        try {
            Event saved = eventService.createEvent(event);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getMessage());
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validateEvent(@RequestBody Event event) {
        List<String> errors = validateEventBasic(event);
        String serviceErrors = eventService.getValidationErrors(event);
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
            return ResponseEntity.ok(event);
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateEvent(@PathVariable Long id, @RequestBody Event event) {
        try {
            authorizationUtil.ensureUserIsHost();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
        if (event.getEvent_id() == null) {
            event.setEvent_id(id);
        } else if (!id.equals(event.getEvent_id())) {
            return ResponseEntity.badRequest().body("ID do path difere do body");
        }
        List<String> basicErrors = validateEventBasic(event);
        if (!basicErrors.isEmpty()) {
            return ResponseEntity.badRequest().body(String.join("; ", basicErrors));
        }
        try {
            Event updated = eventService.updateEvent(event);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteEvent(@PathVariable Long id) {
        try {
            authorizationUtil.ensureUserIsHost();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
        try {
            eventService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getMessage());
        }
    }

    @GetMapping("/search")
    public ResponseEntity<List<Event>> searchEvents(@RequestParam String term) {
        return ResponseEntity.ok(eventService.searchEvents(term));
    }

    @GetMapping
    public ResponseEntity<List<Event>> getEventsByCreator(@RequestParam(required = false) Long creator_id) {
        if (creator_id == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            List<Event> events = eventService.findByCreatorId(creator_id);
            return ResponseEntity.ok(events);
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @PostMapping(value = "/{id}/image", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadEventImage(
            @PathVariable Long id,
            @RequestParam("image") org.springframework.web.multipart.MultipartFile file) {
        try {
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

            // Buscar evento e atualizar imagem
            Event event = eventService.findById(id);
            event.setImage_data(imageBytes);
            eventService.updateEvent(event);

            return ResponseEntity.ok("Imagem carregada com sucesso");
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao carregar imagem: " + ex.getMessage());
        }
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> getEventImage(@PathVariable Long id) {
        try {
            Event event = eventService.findById(id);
            byte[] imageData = event.getImage_data();

            if (imageData == null || imageData.length == 0) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .header("Content-Type", "image/jpeg")
                    .header("Cache-Control", "max-age=3600")
                    .body(imageData);
        } catch (RuntimeException ex) {
            return ResponseEntity.notFound().build();
        }
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
