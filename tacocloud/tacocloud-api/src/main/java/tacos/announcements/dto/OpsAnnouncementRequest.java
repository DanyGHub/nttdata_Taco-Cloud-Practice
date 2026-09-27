package tacos.announcements.dto;

import java.time.Instant;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.announcements.AnnouncementSeverity;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpsAnnouncementRequest {

  @NotBlank(message = "Announcement message cannot be blank")
  @Size(min = 3, max = 255, message = "Message must be between 3 and 255 characters")
  private String message;

  @Builder.Default
  private AnnouncementSeverity severity = AnnouncementSeverity.INFO;

  private Long durationMinutes;

  private Instant expiresAt;

}
