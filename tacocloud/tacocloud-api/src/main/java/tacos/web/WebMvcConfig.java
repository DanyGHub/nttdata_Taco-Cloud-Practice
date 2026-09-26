package tacos.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuración MVC para servir los bundles estáticos (tacocloud-ui) bajo el prefijo /ui/ y reenviar rutas web a index.html.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/ui/**")
        .addResourceLocations("classpath:/static/");
  }

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addViewController("/ui").setViewName("forward:/index.html");
    registry.addViewController("/ui/").setViewName("forward:/index.html");
    registry.addViewController("/home").setViewName("forward:/index.html");
    registry.addViewController("/login").setViewName("forward:/index.html");
  }

}
