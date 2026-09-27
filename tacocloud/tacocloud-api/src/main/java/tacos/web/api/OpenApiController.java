package tacos.web.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador para publicar el contrato OpenAPI 3.0 de Taco Cloud.
 */
@RestController
@CrossOrigin(origins = "*")
public class OpenApiController {

  private static final String OPENAPI_RESOURCE = "openapi.yaml";
  private static final MediaType YAML_MEDIA_TYPE = MediaType.parseMediaType("text/yaml;charset=UTF-8");

  @GetMapping(path = {"/openapi.yaml", "/api/v1/openapi.yaml", "/api/v1/api-docs"}, produces = "text/yaml;charset=UTF-8")
  public ResponseEntity<String> getOpenApiSpec() {
    try {
      Resource resource = new ClassPathResource(OPENAPI_RESOURCE);
      if (!resource.exists()) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body("OpenAPI specification file 'openapi.yaml' not found on classpath.");
      }
      String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, YAML_MEDIA_TYPE.toString())
          .body(content);
    } catch (IOException e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Error reading OpenAPI specification: " + e.getMessage());
    }
  }

}
