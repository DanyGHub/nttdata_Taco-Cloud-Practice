package tacos;

import java.util.Arrays;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import tacos.Ingredient.Type;
import tacos.data.IngredientRepository;
import tacos.data.PaymentDataMigrationService;
import tacos.data.PaymentMethodRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;

@Profile("!prod")
@Configuration
public class DevelopmentConfig {

  @Bean
  public CommandLineRunner paymentDataMigrationRunner(PaymentDataMigrationService migrationService) {
    return args -> migrationService.purgeLegacySensitiveCardData().subscribe();
  }

  @Bean
  public CommandLineRunner dataLoader(IngredientRepository repo,
        UserRepository userRepo, PasswordEncoder encoder, TacoRepository tacoRepo,
        PaymentMethodRepository paymentMethodRepo) { // user repo for ease of testing with a built-in user
    
    return new CommandLineRunner() {
      @Override
      public void run(String... args) throws Exception {
        Ingredient flourTortilla = saveAnIngredient("FLTO", "Flour Tortilla", Type.WRAP);
        Ingredient cornTortilla = saveAnIngredient("COTO", "Corn Tortilla", Type.WRAP);
        Ingredient groundBeef = saveAnIngredient("GRBF", "Ground Beef", Type.PROTEIN);
        Ingredient carnitas = saveAnIngredient("CARN", "Carnitas", Type.PROTEIN);
        Ingredient tomatoes = saveAnIngredient("TMTO", "Diced Tomatoes", Type.VEGGIES);
        Ingredient lettuce = saveAnIngredient("LETC", "Lettuce", Type.VEGGIES);
        Ingredient cheddar = saveAnIngredient("CHED", "Cheddar", Type.CHEESE);
        Ingredient jack = saveAnIngredient("JACK", "Monterrey Jack", Type.CHEESE);
        Ingredient salsa = saveAnIngredient("SLSA", "Salsa", Type.SAUCE);
        Ingredient sourCream = saveAnIngredient("SRCR", "Sour Cream", Type.SAUCE);
        
        userRepo.findByUsername("habuma")
            .flatMap(existing -> {
              if (existing.getRoles() == null || existing.getRoles().isEmpty()) {
                existing.setRoles(Arrays.asList("ROLE_USER"));
                return userRepo.save(existing);
              }
              return reactor.core.publisher.Mono.just(existing);
            })
            .switchIfEmpty(reactor.core.publisher.Mono.defer(() -> userRepo.save(new User("habuma", encoder.encode("password"), 
                  "Craig Walls", "123 North Street", "Cross Roads", "TX", 
                  "76227", "123-123-1234", "craig@habuma.com", Arrays.asList("ROLE_USER")))))
            .flatMap(user -> paymentMethodRepo.save(new PaymentMethod(user, "tok_habuma_visa4111", "VISA", "4111", "10/25")))
            .block();

        userRepo.findByUsername("admin")
            .flatMap(existing -> {
              if (existing.getRoles() == null || !existing.getRoles().contains("ROLE_ADMIN")) {
                existing.setRoles(Arrays.asList("ROLE_ADMIN"));
                return userRepo.save(existing);
              }
              return reactor.core.publisher.Mono.just(existing);
            })
            .switchIfEmpty(reactor.core.publisher.Mono.defer(() -> userRepo.save(new User("admin", encoder.encode("admin"),
                  "Admin User", "Admin Street 1", "Capital", "AGS",
                  "20000", "555-010-0001", "admin@tacocloud.com", Arrays.asList("ROLE_ADMIN")))))
            .block();

        userRepo.findByUsername("kitchen")
            .flatMap(existing -> {
              if (existing.getRoles() == null || !existing.getRoles().contains("ROLE_KITCHEN")) {
                existing.setRoles(Arrays.asList("ROLE_KITCHEN"));
                return userRepo.save(existing);
              }
              return reactor.core.publisher.Mono.just(existing);
            })
            .switchIfEmpty(reactor.core.publisher.Mono.defer(() -> userRepo.save(new User("kitchen", encoder.encode("kitchen"),
                  "Kitchen Staff", "Kitchen Avenue 2", "Capital", "AGS",
                  "20000", "555-010-0002", "kitchen@tacocloud.com", Arrays.asList("ROLE_KITCHEN")))))
            .block();        
        
        Taco taco1 = new Taco();
        taco1.setId("TACO1");
        taco1.setName("Carnivore");
        taco1.setIngredients(Arrays.asList(flourTortilla, groundBeef, carnitas, sourCream, salsa, cheddar));
        tacoRepo.save(taco1).subscribe();

        Taco taco2 = new Taco();
        taco2.setId("TACO2");
        taco2.setName("Bovine Bounty");
        taco2.setIngredients(Arrays.asList(cornTortilla, groundBeef, cheddar, jack, sourCream));
        tacoRepo.save(taco2).subscribe();

        Taco taco3 = new Taco();
        taco3.setId("TACO3");
        taco3.setName("Veg-Out");
        taco3.setIngredients(Arrays.asList(flourTortilla, cornTortilla, tomatoes, lettuce, salsa));
        tacoRepo.save(taco3).subscribe();

      }

      private Ingredient saveAnIngredient(String id, String name, Type type) {
        Ingredient ingredient = new Ingredient(id, name, type);
        repo.save(ingredient).subscribe();
        return ingredient;
      }
    };
  }
  
}
