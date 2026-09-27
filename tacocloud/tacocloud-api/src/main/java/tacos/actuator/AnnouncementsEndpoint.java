package tacos.actuator;

import java.util.Collections;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.announcements.AnnouncementSeverity;
import tacos.announcements.OpsAnnouncementService;
import tacos.announcements.dto.OpsAnnouncementAdminDto;
import tacos.announcements.dto.OpsAnnouncementDto;
import tacos.announcements.dto.OpsAnnouncementRequest;

/**
 * Endpoint Actuator seguro para anuncios operativos (/actuator/announcements).
 */
@Component
@Endpoint(id = "announcements", enableByDefault = true)
public class AnnouncementsEndpoint {

  private final OpsAnnouncementService service;

  @Autowired
  public AnnouncementsEndpoint(OpsAnnouncementService service) {
    this.service = service;
  }

  @ReadOperation
  public Flux<OpsAnnouncementDto> getAnnouncements() {
    return service.getActiveAnnouncements();
  }

  @WriteOperation
  public Mono<OpsAnnouncementAdminDto> addAnnouncement(
      String message,
      @Nullable String severity,
      @Nullable Long durationMinutes) {
    AnnouncementSeverity sev = AnnouncementSeverity.INFO;
    if (severity != null && !severity.trim().isEmpty()) {
      try {
        sev = AnnouncementSeverity.valueOf(severity.toUpperCase());
      } catch (IllegalArgumentException ignored) {
      }
    }

    OpsAnnouncementRequest req = OpsAnnouncementRequest.builder()
        .message(message)
        .severity(sev)
        .durationMinutes(durationMinutes != null ? durationMinutes : 1440L)
        .build();

    return service.createAnnouncement(req, "ACTUATOR");
  }

  @DeleteOperation
  public Mono<Map<String, Object>> deleteAnnouncement(@Selector String id) {
    return service.deleteAnnouncement(id)
        .thenReturn(Collections.singletonMap("deleted", (Object) id));
  }

}
