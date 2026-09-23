package tacos.kitchen.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import tacos.kitchen.domain.KitchenOrder;

@Repository
public interface KitchenOrderRepository extends MongoRepository<KitchenOrder, String> {

  Optional<KitchenOrder> findByOrderId(String orderId);

  boolean existsByOrderId(String orderId);

}
