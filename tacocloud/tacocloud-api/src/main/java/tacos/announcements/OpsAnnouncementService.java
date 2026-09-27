package tacos.announcements;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.announcements.dto.OpsAnnouncementAdminDto;
import tacos.announcements.dto.OpsAnnouncementDto;
import tacos.announcements.dto.OpsAnnouncementRequest;

/**
 * Servicio centralizado para gestión de anuncios operativos seguros.
 */
@Service
public class OpsAnnouncementService {

  private static final Logger log = LoggerFactory.getLogger(OpsAnnouncementService.class);

  public static final int MAX_ACTIVE_ANNOUNCEMENTS = 10;
  public static final int MAX_MESSAGE_LENGTH = 255;
  public static final int MIN_MESSAGE_LENGTH = 3;
  public static final int MAX_DURATION_DAYS = 30;

  private final OpsAnnouncementRepository repo;
  private final ReactiveMongoTemplate mongoTemplate;
  private Clock clock;

  @Autowired
  public OpsAnnouncementService(
      OpsAnnouncementRepository repo,
      ReactiveMongoTemplate mongoTemplate,
      @Autowired(required = false) Clock clock) {
    this.repo = repo;
    this.mongoTemplate = mongoTemplate;
    this.clock = clock != null ? clock : Clock.systemUTC();
  }

  public void setClock(Clock clock) {
    this.clock = clock != null ? clock : Clock.systemUTC();
  }

  public Clock getClock() {
    return clock;
  }

  /**
   * Consulta pública sanitizada: Sólo anuncios activos y no expirados, sin autor ni metadatos sensibles.
   */
  public Flux<OpsAnnouncementDto> getActiveAnnouncements() {
    Instant now = clock.instant();
    return repo.findByActiveTrueAndExpiresAtAfterOrderByCreatedAtDesc(now)
        .map(this::toPublicDto);
  }

  /**
   * Consulta administrativa: Todos los anuncios (activos e inactivos) con auditoría.
   */
  public Flux<OpsAnnouncementAdminDto> getAllAnnouncements() {
    return repo.findAll()
        .map(this::toAdminDto);
  }

  /**
   * Obtiene un anuncio por su ID estable.
   */
  public Mono<OpsAnnouncementAdminDto> getAnnouncementById(String id) {
    if (id == null || id.trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID cannot be empty"));
    }
    return repo.findById(id)
        .map(this::toAdminDto)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Announcement not found: " + id)));
  }

  /**
   * Crea un nuevo anuncio operativo aplicando todas las reglas de negocio y seguridad.
   */
  public Mono<OpsAnnouncementAdminDto> createAnnouncement(OpsAnnouncementRequest request, String createdBy) {
    if (request == null) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body cannot be null"));
    }

    // 1. Validación estricta de texto (longitud y caracteres de control)
    validateTextMessage(request.getMessage());

    Instant now = clock.instant();

    // 2. Validación y cálculo de expiración
    Instant expiresAt = calculateAndValidateExpiration(request, now);

    // 3. Verificación de cuota máxima de anuncios activos simultáneos
    return repo.countByActiveTrueAndExpiresAtAfter(now)
        .flatMap(activeCount -> {
          if (activeCount >= MAX_ACTIVE_ANNOUNCEMENTS) {
            return Mono.error(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Maximum number of active announcements (" + MAX_ACTIVE_ANNOUNCEMENTS + ") reached. Deactivate or delete older announcements first."));
          }

          String safeAuthor = sanitizeAuthor(createdBy);
          AnnouncementSeverity severity = request.getSeverity() != null ? request.getSeverity() : AnnouncementSeverity.INFO;

          OpsAnnouncement announcement = OpsAnnouncement.builder()
              .id(UUID.randomUUID().toString())
              .message(request.getMessage().trim())
              .severity(severity)
              .createdAt(now)
              .expiresAt(expiresAt)
              .createdBy(safeAuthor)
              .active(true)
              .build();

          log.info("Creating operational announcement id={} severity={} createdBy={} expiresAt={}",
              announcement.getId(), announcement.getSeverity(), announcement.getCreatedBy(), announcement.getExpiresAt());

          return repo.save(announcement).map(this::toAdminDto);
        });
  }

  /**
   * Elimina un anuncio por su ID único y estable (reemplaza borrado por índice).
   */
  public Mono<Void> deleteAnnouncement(String id) {
    if (id == null || id.trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID cannot be empty"));
    }
    return repo.findById(id)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Announcement not found: " + id)))
        .flatMap(existing -> {
          log.info("Deleting operational announcement id={}", id);
          return repo.delete(existing);
        });
  }

  /**
   * Desactiva un anuncio manualmente sin borrarlo de la auditoría.
   */
  public Mono<OpsAnnouncementAdminDto> deactivateAnnouncement(String id) {
    if (id == null || id.trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID cannot be empty"));
    }
    return repo.findById(id)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Announcement not found: " + id)))
        .flatMap(existing -> {
          existing.setActive(false);
          log.info("Deactivating operational announcement id={}", id);
          return repo.save(existing);
        })
        .map(this::toAdminDto);
  }

  /**
   * Limpia anuncios cuya fecha de expiración ya ha pasado.
   */
  public Mono<Long> purgeExpired() {
    Instant now = clock.instant();
    Query query = Query.query(Criteria.where("expiresAt").lte(now));
    return mongoTemplate.remove(query, OpsAnnouncement.class)
        .map(deleteResult -> {
          long count = deleteResult.getDeletedCount();
          log.info("Purged {} expired operational announcements at {}", count, now);
          return count;
        });
  }

  // --- Métodos Privados de Validación y Mapeo ---

  private void validateTextMessage(String text) {
    if (text == null || text.trim().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Announcement message cannot be blank");
    }
    String trimmed = text.trim();
    if (trimmed.length() < MIN_MESSAGE_LENGTH || trimmed.length() > MAX_MESSAGE_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Message length must be between " + MIN_MESSAGE_LENGTH + " and " + MAX_MESSAGE_LENGTH + " characters");
    }

    // Validar contra caracteres de control para evitar log injection, escape sequences y UI exploits
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (Character.isISOControl(c) || c < 32 || (c >= 127 && c <= 159)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Message contains illegal control character (code: " + (int) c + ")");
      }
    }
  }

  private Instant calculateAndValidateExpiration(OpsAnnouncementRequest request, Instant now) {
    Instant maxAllowed = now.plus(MAX_DURATION_DAYS, ChronoUnit.DAYS);

    if (request.getExpiresAt() != null) {
      if (!request.getExpiresAt().isAfter(now)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expiration time must be in the future");
      }
      if (request.getExpiresAt().isAfter(maxAllowed)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Expiration cannot exceed " + MAX_DURATION_DAYS + " days from now");
      }
      return request.getExpiresAt();
    }

    if (request.getDurationMinutes() != null) {
      long minutes = request.getDurationMinutes();
      if (minutes <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duration must be greater than 0 minutes");
      }
      long maxMinutes = MAX_DURATION_DAYS * 24L * 60L;
      if (minutes > maxMinutes) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Duration cannot exceed " + MAX_DURATION_DAYS + " days (" + maxMinutes + " minutes)");
      }
      return now.plus(minutes, ChronoUnit.MINUTES);
    }

    // Default: 24 horas
    return now.plus(24, ChronoUnit.HOURS);
  }

  private String sanitizeAuthor(String author) {
    if (author == null || author.trim().isEmpty()) {
      return "ADMIN";
    }
    return author.replaceAll("[^a-zA-Z0-9_.-]", "_");
  }

  private OpsAnnouncementDto toPublicDto(OpsAnnouncement a) {
    return OpsAnnouncementDto.builder()
        .id(a.getId())
        .message(a.getMessage())
        .severity(a.getSeverity())
        .createdAt(a.getCreatedAt())
        .expiresAt(a.getExpiresAt())
        .build();
  }

  private OpsAnnouncementAdminDto toAdminDto(OpsAnnouncement a) {
    return OpsAnnouncementAdminDto.builder()
        .id(a.getId())
        .message(a.getMessage())
        .severity(a.getSeverity())
        .createdAt(a.getCreatedAt())
        .expiresAt(a.getExpiresAt())
        .createdBy(a.getCreatedBy())
        .active(a.isActive())
        .version(a.getVersion())
        .build();
  }

}
