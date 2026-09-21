package tacos.outbox;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class OutboxService {

  private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

  private final OutboxEventRepository outboxRepo;
  private final ReactiveMongoTemplate mongoTemplate;

  @Autowired
  public OutboxService(OutboxEventRepository outboxRepo, ReactiveMongoTemplate mongoTemplate) {
    this.outboxRepo = outboxRepo;
    this.mongoTemplate = mongoTemplate;
  }

  public Mono<OutboxEvent> save(OutboxEvent event) {
    return outboxRepo.save(event);
  }

  /**
   * Finds events ready for publishing:
   * 1. Status is NEW
   * 2. Status is FAILED with nextAttemptAt <= now and attempts < maxAttempts
   * 3. Status is PUBLISHING with expired lease (lockedUntil < now)
   */
  public Flux<OutboxEvent> findCandidatesForPublishing(Date now, int limit) {
    Criteria isNew = Criteria.where("status").is(OutboxStatus.NEW);

    Criteria isRetryableFailed = new Criteria().andOperator(
        Criteria.where("status").is(OutboxStatus.FAILED),
        new Criteria().orOperator(
            Criteria.where("nextAttemptAt").lte(now),
            Criteria.where("nextAttemptAt").exists(false)
        ),
        Criteria.where("$expr").is(
            org.bson.Document.parse("{\"$lt\": [\"$attempts\", \"$maxAttempts\"]}")
        )
    );

    Criteria isExpiredLock = new Criteria().andOperator(
        Criteria.where("status").is(OutboxStatus.PUBLISHING),
        Criteria.where("lockedUntil").lt(now)
    );

    // Combine candidate criteria
    Criteria candidates = new Criteria().orOperator(isNew, isExpiredLock, Criteria.where("status").is(OutboxStatus.FAILED));

    Query query = new Query(candidates)
        .with(Sort.by(Sort.Direction.ASC, "createdAt"))
        .limit(limit);

    return mongoTemplate.find(query, OutboxEvent.class)
        .filter(event -> {
          if (event.getStatus() == OutboxStatus.NEW) {
            return true;
          }
          if (event.getStatus() == OutboxStatus.PUBLISHING) {
            return event.getLockedUntil() != null && event.getLockedUntil().before(now);
          }
          if (event.getStatus() == OutboxStatus.FAILED) {
            boolean underMax = event.getAttempts() < event.getMaxAttempts();
            boolean timeReady = event.getNextAttemptAt() == null || !event.getNextAttemptAt().after(now);
            return underMax && timeReady;
          }
          return false;
        });
  }

  /**
   * Atomically claims an outbox event to prevent concurrent publishers from processing the same event.
   * Uses findAndModify conditioned on state and expiration lease.
   */
  public Mono<OutboxEvent> atomicClaim(String eventId, String instanceId, long lockDurationMs, Date now) {
    Date lockUntil = new Date(now.getTime() + lockDurationMs);

    Criteria matchId = Criteria.where("_id").is(eventId);

    Criteria canClaimCondition = new Criteria().orOperator(
        Criteria.where("status").is(OutboxStatus.NEW),
        new Criteria().andOperator(
            Criteria.where("status").is(OutboxStatus.PUBLISHING),
            Criteria.where("lockedUntil").lt(now)
        ),
        new Criteria().andOperator(
            Criteria.where("status").is(OutboxStatus.FAILED),
            new Criteria().orOperator(
                Criteria.where("nextAttemptAt").lte(now),
                Criteria.where("nextAttemptAt").exists(false)
            )
        )
    );

    Query query = new Query(new Criteria().andOperator(matchId, canClaimCondition));

    Update update = new Update()
        .set("status", OutboxStatus.PUBLISHING)
        .set("lockedBy", instanceId)
        .set("lockedUntil", lockUntil)
        .inc("attempts", 1)
        .set("updatedAt", now);

    FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);

    return mongoTemplate.findAndModify(query, update, options, OutboxEvent.class)
        .doOnNext(claimed -> log.debug("Instance {} claimed outbox event {} (attempt {})",
            instanceId, claimed.getId(), claimed.getAttempts()));
  }

  /**
   * Marks an outbox event as successfully published and clears the lock.
   */
  public Mono<OutboxEvent> markPublished(String eventId, Date now) {
    Query query = Query.query(Criteria.where("_id").is(eventId));
    Update update = new Update()
        .set("status", OutboxStatus.PUBLISHED)
        .unset("lockedBy")
        .unset("lockedUntil")
        .unset("lastError")
        .set("updatedAt", now);

    FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);
    return mongoTemplate.findAndModify(query, update, options, OutboxEvent.class);
  }

  /**
   * Marks an outbox event as FAILED with exponential backoff for next retry.
   * If attempts reach maxAttempts, the event remains visible in FAILED state.
   */
  public Mono<OutboxEvent> markFailed(
      String eventId,
      Throwable error,
      int currentAttempts,
      int maxAttempts,
      long backoffMultiplierMs,
      Date now) {

    long backoffMs = (long) (Math.pow(2, Math.max(0, currentAttempts - 1)) * backoffMultiplierMs);
    Date nextAttempt = new Date(now.getTime() + backoffMs);

    Query query = Query.query(Criteria.where("_id").is(eventId));
    Update update = new Update()
        .set("status", OutboxStatus.FAILED)
        .set("lastError", error != null ? error.getMessage() : "Unknown error")
        .unset("lockedBy")
        .unset("lockedUntil")
        .set("nextAttemptAt", nextAttempt)
        .set("updatedAt", now);

    FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);
    return mongoTemplate.findAndModify(query, update, options, OutboxEvent.class)
        .doOnNext(failed -> {
          if (currentAttempts >= maxAttempts) {
            log.warn("Outbox event {} permanently FAILED after {} attempts: {}",
                eventId, currentAttempts, failed.getLastError());
          } else {
            log.info("Outbox event {} scheduled for retry in {} ms (attempt {}/{}): {}",
                eventId, backoffMs, currentAttempts, maxAttempts, failed.getLastError());
          }
        });
  }

}
