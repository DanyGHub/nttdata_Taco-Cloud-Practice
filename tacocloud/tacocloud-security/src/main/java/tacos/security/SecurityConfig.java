package tacos.security;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation
             .authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web
             .builders.HttpSecurity;
import org.springframework.security.config.annotation.web
                        .configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web
                        .configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@SuppressWarnings("deprecation")
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {
  
  @Autowired
  private UserDetailsService userDetailsService;
  
  @Override
  protected void configure(HttpSecurity http) throws Exception {
    http
      .cors().configurationSource(corsConfigurationSource())
      .and()
      .csrf()
        .ignoringAntMatchers("/h2-console/**", "/api/**", "/register/**", "/actuator/**")
      .and()
      .authorizeRequests()
        // 1. Preflight CORS
        .antMatchers(HttpMethod.OPTIONS, "/**").permitAll()

        // 2. Vistas web públicas, recursos estáticos y endpoint de error
        .antMatchers(
            "/", "/index.html", "/favicon.ico",
            "/login", "/register/**",
            "/error",
            "/styles.css", "/styles/**", "/scripts/**", "/images/**", "/assets/**", "/webjars/**",
            "/*.bundle.js", "/*.bundle.js.map", "/*.js", "/*.css",
            "/design", "/cart", "/recents", "/specials", "/locations", "/home"
        ).permitAll()

        // 3. Catálogo público: Lectura de ingredientes y tacos
        .antMatchers(HttpMethod.GET, "/api/ingredients/**", "/api/tacos/**").permitAll()

        // 4. Registro de usuarios REST
        .antMatchers(HttpMethod.POST, "/api/users").permitAll()

        // 5. Actuator: Base, Health e Info públicos; el resto sólo ADMIN
        .antMatchers(HttpMethod.GET, "/actuator", "/actuator/health", "/actuator/info").permitAll()
        .antMatchers("/actuator/**").hasRole("ADMIN")

        // 6. Spring Data REST (/data-api/**)
        .antMatchers("/data-api/**").hasRole("ADMIN")

        // 7. Administración de ingredientes, catálogo, stock y endpoints administrativos
        .antMatchers("/api/admin/**").hasRole("ADMIN")
        .antMatchers(HttpMethod.POST, "/api/ingredients/**").hasRole("ADMIN")
        .antMatchers(HttpMethod.PUT, "/api/ingredients/**").hasRole("ADMIN")
        .antMatchers(HttpMethod.PATCH, "/api/ingredients/**").hasRole("ADMIN")
        .antMatchers(HttpMethod.DELETE, "/api/ingredients/**").hasRole("ADMIN")

        // 8. Mutaciones de tacos
        .antMatchers(HttpMethod.POST, "/api/tacos/**").hasAnyRole("USER", "ADMIN")
        .antMatchers(HttpMethod.PUT, "/api/tacos/**").hasAnyRole("USER", "ADMIN")
        .antMatchers(HttpMethod.DELETE, "/api/tacos/**").hasAnyRole("USER", "ADMIN")

        // 9. Cocina (debe ir antes de /orders/** por especificidad)
        .antMatchers("/orders/receive/**", "/kitchen/**", "/api/kitchen/**").hasAnyRole("KITCHEN", "ADMIN")

        // 10. Pedidos / Órdenes (API y Vistas Web) y Métodos de Pago
        .antMatchers("/api/payment-methods/**", "/api/orders/**", "/orders/**", "/discounts/**").hasAnyRole("USER", "ADMIN")

        // 11. TC-11 Regla final: Deny-by-default
        .anyRequest().denyAll()
        
      .and()
        .formLogin()
          .loginPage("/login")
          .defaultSuccessUrl("/", true)
          .permitAll()
          
      .and()
        .httpBasic()
          .realmName("Taco Cloud")
          
      .and()
        .logout()
          .logoutSuccessUrl("/")
          .permitAll()

      // Allow pages to be loaded in frames from the same origin; needed for H2-Console
      .and()  
        .headers()
          .frameOptions()
            .sameOrigin()
      ;
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(Arrays.asList(
        "http://localhost:8080",
        "http://nitro:8080",
        "http://localhost:4200",
        "http://localhost:9090",
        "http://nitro:9090",
        "https://tacocloud.com"
    ));
    configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(Arrays.asList("*"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  @Bean
  public PasswordEncoder encoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  public DaoAuthenticationProvider authenticationProvider() {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
    provider.setUserDetailsService(userDetailsService);
    provider.setPasswordEncoder(encoder());
    return provider;
  }

  @Bean
  @Override
  public AuthenticationManager authenticationManagerBean() throws Exception {
    return super.authenticationManagerBean();
  }
  
  @Override
  protected void configure(AuthenticationManagerBuilder auth)
      throws Exception {
    auth.authenticationProvider(authenticationProvider());
  }

}
