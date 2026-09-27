package tacos.versioning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class ApiDeprecationFilterTest {

  private ApiDeprecationFilter filter;

  @BeforeEach
  public void setUp() {
    filter = new ApiDeprecationFilter();
  }

  @Test
  @DisplayName("Petición a ruta legada /api/orders añade encabezados de deprecación RFC 8594 y enlace a /api/v1/orders")
  public void legacyOrderRoute_addsDeprecationHeaders() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(ApiDeprecationFilter.HEADER_DEPRECATION)).isEqualTo("true");
    assertThat(response.getHeader(ApiDeprecationFilter.HEADER_SUNSET)).isEqualTo(ApiDeprecationFilter.DEFAULT_SUNSET_DATE);
    assertThat(response.getHeader(ApiDeprecationFilter.HEADER_LINK)).isEqualTo("</api/v1/orders>; rel=\"successor-version\"");
    assertThat(response.getHeader(ApiDeprecationFilter.HEADER_WARNING)).contains("deprecated");
  }

  @Test
  @DisplayName("Petición a ruta legada /api/ingredients/FLTO añade enlace sucesor /api/v1/ingredients/FLTO")
  public void legacyIngredientRoute_addsCorrectSuccessorLink() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ingredients/FLTO");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(ApiDeprecationFilter.HEADER_DEPRECATION)).isEqualTo("true");
    assertThat(response.getHeader(ApiDeprecationFilter.HEADER_LINK)).isEqualTo("</api/v1/ingredients/FLTO>; rel=\"successor-version\"");
  }

  @Test
  @DisplayName("Petición a ruta moderna /api/v1/orders NO añade encabezados de deprecación")
  public void canonicalV1Route_doesNotAddDeprecationHeaders() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(ApiDeprecationFilter.HEADER_DEPRECATION)).isNull();
    assertThat(response.getHeader(ApiDeprecationFilter.HEADER_SUNSET)).isNull();
    assertThat(response.getHeader(ApiDeprecationFilter.HEADER_LINK)).isNull();
    assertThat(response.getHeader(ApiDeprecationFilter.HEADER_WARNING)).isNull();
  }

  @Test
  @DisplayName("Petición a /openapi.yaml y documentación NO añade encabezados de deprecación")
  public void docsRoute_doesNotAddDeprecationHeaders() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/openapi.yaml");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(ApiDeprecationFilter.HEADER_DEPRECATION)).isNull();
  }

}
