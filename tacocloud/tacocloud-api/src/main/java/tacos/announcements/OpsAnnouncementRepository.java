package tacos.announcements;

import java.time.Instant;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface OpsAnnouncementRepository extends ReactiveMongoRepository<OpsAnnouncement, String> {

  Flux<OpsAnnouncement> findByActiveTrue();

  Flux<OpsAnnouncement> findByActiveTrueAndExpiresAtAfterOrderByCreatedAtDesc(Instant now);

  Mono<Long> countByActiveTrueAndExpiresAtAfter(Instant now);

  Flux<OpsAnnouncement> findByExpiresAtBefore(Instant now);

}
