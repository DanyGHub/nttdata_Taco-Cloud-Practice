package tacos.announcements.dto;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.announcements.AnnouncementSeverity;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpsAnnouncementAdminDto {

  private String id;
  private String message;
  private AnnouncementSeverity severity;
  private Instant createdAt;
  private Instant expiresAt;
  private String createdBy;
  private boolean active;
  private Long version;

}
