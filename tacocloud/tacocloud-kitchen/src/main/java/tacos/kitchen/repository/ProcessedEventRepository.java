package tacos.kitchen.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import tacos.kitchen.domain.ProcessedEvent;
import tacos.kitchen.domain.ProcessedStatus;

@Repository
public interface ProcessedEventRepository extends MongoRepository<ProcessedEvent, String> {

  Optional<ProcessedEvent> findByEventId(String eventId);

  boolean existsByEventId(String eventId);

  List<ProcessedEvent> findByStatus(ProcessedStatus status);

  long countByStatus(ProcessedStatus status);

}
