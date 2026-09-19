package tacos.data;

import static org.assertj.core.api.Assertions.assertThat;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;


@DataMongoTest
@Import(PaymentDataMigrationService.class)
public class PaymentDataMigrationTest {

  @org.springframework.boot.autoconfigure.SpringBootApplication
  static class TestConfig {}

  @Autowired
  private ReactiveMongoTemplate mongoTemplate;

  @Autowired
  private PaymentDataMigrationService migrationService;

  @BeforeEach
  public void setUp() {
    mongoTemplate.dropCollection("tacoOrder").block();
    mongoTemplate.dropCollection("paymentMethod").block();
  }

  @Test
  public void purgeLegacySensitiveCardData_shouldRemovePanAndCvvFromExistingDocuments() {
    Document legacyOrder = new Document()
        .append("_id", "order_legacy_1")
        .append("deliveryName", "Legacy User")
        .append("deliveryStreet", "123 Old St")
        .append("ccNumber", "4111111111111111")
        .append("ccCVV", "123")
        .append("ccExpiration", "12/24");

    Document legacyPm = new Document()
        .append("_id", "pm_legacy_1")
        .append("brand", "VISA")
        .append("last4", "2222")
        .append("ccNumber", "4222222222222222")
        .append("ccCVV", "456")
        .append("ccExpiration", "06/25");

    mongoTemplate.save(legacyOrder, "tacoOrder").block();
    mongoTemplate.save(legacyPm, "paymentMethod").block();

    PaymentDataMigrationService.MigrationSummary summary = migrationService.purgeLegacySensitiveCardData().block();
    assertThat(summary).isNotNull();
    assertThat(summary.getOrdersModified()).isEqualTo(1L);
    assertThat(summary.getPaymentMethodsModified()).isEqualTo(1L);

    Document updatedOrder = mongoTemplate.findById("order_legacy_1", Document.class, "tacoOrder").block();
    assertThat(updatedOrder).isNotNull();
    assertThat(updatedOrder.getString("deliveryName")).isEqualTo("Legacy User");
    assertThat(updatedOrder.containsKey("ccNumber")).isFalse();
    assertThat(updatedOrder.containsKey("ccCVV")).isFalse();
    assertThat(updatedOrder.containsKey("ccExpiration")).isFalse();

    Document updatedPm = mongoTemplate.findById("pm_legacy_1", Document.class, "paymentMethod").block();
    assertThat(updatedPm).isNotNull();
    assertThat(updatedPm.getString("brand")).isEqualTo("VISA");
    assertThat(updatedPm.containsKey("ccNumber")).isFalse();
    assertThat(updatedPm.containsKey("ccCVV")).isFalse();
    assertThat(updatedPm.containsKey("ccExpiration")).isFalse();
  }
}
