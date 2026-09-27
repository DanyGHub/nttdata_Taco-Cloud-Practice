package tacos.announcements;

import java.util.Collections;
import java.util.Map;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.announcements.dto.OpsAnnouncementAdminDto;
import tacos.announcements.dto.OpsAnnouncementDto;
import tacos.announcements.dto.OpsAnnouncementRequest;

@RestController
public class OpsAnnouncementController {

  private final OpsAnnouncementService service;

  @Autowired
  public OpsAnnouncementController(OpsAnnouncementService service) {
    this.service = service;
  }

  // --- Endpoints Públicos (/api/v1/announcements, /api/announcements) ---

  @GetMapping({"/api/v1/announcements", "/api/announcements"})
  public Flux<OpsAnnouncementDto> getActiveAnnouncements() {
    return service.getActiveAnnouncements();
  }

  // --- Endpoints Administrativos (/api/v1/admin/announcements, /api/admin/announcements) ---

  @GetMapping({"/api/v1/admin/announcements", "/api/admin/announcements"})
  public Flux<OpsAnnouncementAdminDto> getAllAnnouncementsAdmin() {
    return service.getAllAnnouncements();
  }

  @GetMapping({"/api/v1/admin/announcements/{id}", "/api/admin/announcements/{id}"})
  public Mono<OpsAnnouncementAdminDto> getAnnouncementByIdAdmin(@PathVariable("id") String id) {
    return service.getAnnouncementById(id);
  }

  @PostMapping({"/api/v1/admin/announcements", "/api/admin/announcements"})
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<ResponseEntity<OpsAnnouncementAdminDto>> createAnnouncement(
      @Valid @RequestBody OpsAnnouncementRequest request,
      Authentication authentication) {
    String author = authentication != null ? authentication.getName() : "ADMIN";
    return service.createAnnouncement(request, author)
        .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created));
  }

  @DeleteMapping({"/api/v1/admin/announcements/{id}", "/api/admin/announcements/{id}"})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<ResponseEntity<Void>> deleteAnnouncement(@PathVariable("id") String id) {
    return service.deleteAnnouncement(id)
        .thenReturn(ResponseEntity.noContent().<Void>build());
  }

  @PatchMapping({"/api/v1/admin/announcements/{id}/deactivate", "/api/admin/announcements/{id}/deactivate"})
  public Mono<ResponseEntity<OpsAnnouncementAdminDto>> deactivateAnnouncement(@PathVariable("id") String id) {
    return service.deactivateAnnouncement(id)
        .map(ResponseEntity::ok);
  }

  @PostMapping({"/api/v1/admin/announcements/purge", "/api/admin/announcements/purge"})
  public Mono<ResponseEntity<Map<String, Object>>> purgeExpiredAnnouncements() {
    return service.purgeExpired()
        .map(count -> ResponseEntity.ok(Collections.singletonMap("purgedCount", count)));
  }

}
