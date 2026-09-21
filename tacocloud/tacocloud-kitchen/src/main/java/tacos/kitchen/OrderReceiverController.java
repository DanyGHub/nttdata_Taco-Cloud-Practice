package tacos.kitchen;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import lombok.RequiredArgsConstructor;
import tacos.messaging.OrderEvent;

@Profile({"jms-template", "rabbitmq-template"})
@Controller
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderReceiverController {

  private final OrderReceiver orderReceiver;

  @GetMapping("/receive")
  public String receiveOrder(Model model) {
    OrderEvent event = orderReceiver.receiveOrder();
    if (event != null && event.getPayload() != null) {
      model.addAttribute("order", event.getPayload());
      return "receiveOrder";
    }
    return "noOrder";
  }

}
