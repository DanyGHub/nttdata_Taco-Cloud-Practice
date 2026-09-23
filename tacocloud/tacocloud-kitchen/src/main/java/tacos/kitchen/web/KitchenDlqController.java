package tacos.kitchen.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tacos.kitchen.domain.ProcessedEvent;
import tacos.kitchen.domain.ProcessedStatus;
import tacos.kitchen.metrics.KitchenConsumerMetrics;
import tacos.kitchen.repository.ProcessedEventRepository;
import tacos.kitchen.workflow.OrderProcessingWorkflow;

@RestController
@RequestMapping("/api/kitchen")
@RequiredArgsConstructor
public class KitchenDlqController {

  private final OrderProcessingWorkflow workflow;
  private final ProcessedEventRepository processedEventRepository;
  private final KitchenConsumerMetrics metrics;

  @GetMapping("/dlq/stats")
  public ResponseEntity<Map<String, Object>> getStats() {
    Map<String, Object> response = new HashMap<>();
    response.put("metrics", metrics.getSummary());
    response.put("processedCount", processedEventRepository.countByStatus(ProcessedStatus.PROCESSED));
    response.put("failedCount", processedEventRepository.countByStatus(ProcessedStatus.FAILED));
    response.put("replayedCount", processedEventRepository.countByStatus(ProcessedStatus.REPLAYED));
    return ResponseEntity.ok(response);
  }

  @GetMapping("/processed-events")
  public ResponseEntity<List<ProcessedEvent>> getAllProcessedEvents() {
    return ResponseEntity.ok(processedEventRepository.findAll());
  }

  @GetMapping("/processed-events/{eventId}")
  public ResponseEntity<ProcessedEvent> getProcessedEvent(@PathVariable("eventId") String eventId) {
    return processedEventRepository.findByEventId(eventId)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @PostMapping("/dlq/replay/{eventId}")
  public ResponseEntity<Map<String, Object>> replayEvent(@PathVariable("eventId") String eventId) {
    boolean success = workflow.replayEvent(eventId);
    Map<String, Object> response = new HashMap<>();
    response.put("eventId", eventId);
    response.put("replayed", success);

    if (success) {
      response.put("message", "Event successfully replayed or idempotently verified without side-effect collision.");
      return ResponseEntity.ok(response);
    } else {
      response.put("message", "Event not found in processed events record.");
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }
  }

}
