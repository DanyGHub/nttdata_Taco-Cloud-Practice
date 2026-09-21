package tacos.messaging;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class NoOpOrderMessagingService implements OrderMessagingService {

  @Override
  public void sendOrder(OrderEvent event) {
    log.info("[NOOP-MESSAGING] Published OrderEvent: eventId={}, type={}, correlationId={}",
        event != null ? event.getEventId() : "null",
        event != null ? event.getEventType() : "null",
        event != null ? event.getCorrelationId() : "null");
  }

}
