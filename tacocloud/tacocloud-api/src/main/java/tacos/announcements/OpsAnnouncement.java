package tacos.announcements;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "ops_announcements")
public class OpsAnnouncement {

  @Id
  private String id;

  private String message;

  @Builder.Default
  private AnnouncementSeverity severity = AnnouncementSeverity.INFO;

  private Instant createdAt;

  private Instant expiresAt;

  private String createdBy;

  @Builder.Default
  private boolean active = true;

  @Version
  private Long version;

}
