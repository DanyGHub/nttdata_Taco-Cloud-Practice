package tacos.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class CorrelationIdFilterTest {

  private CorrelationIdFilter filter;

  @BeforeEach
  public void setUp() {
    filter = new CorrelationIdFilter();
    CorrelationContext.clear();
  }

  @AfterEach
  public void tearDown() {
    CorrelationContext.clear();
  }

  @Test
  @DisplayName("Request con header X-Correlation-Id válido lo conserva en contexto, MDC y response header")
  public void requestWithValidHeader_shouldPreserveId() throws ServletException, IOException {
    String validId = "c8b4b45a-932f-4a33-8a30-84381736fa2a";
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(CorrelationContext.HEADER_NAME, validId);
    MockHttpServletResponse response = new MockHttpServletResponse();

    AtomicReference<String> threadIdInsideChain = new AtomicReference<>();
    AtomicReference<String> mdcIdInsideChain = new AtomicReference<>();

    FilterChain chain = (req, res) -> {
      threadIdInsideChain.set(CorrelationContext.get());
      mdcIdInsideChain.set(MDC.get(CorrelationContext.MDC_KEY));
    };

    filter.doFilter(request, response, chain);

    // Valida que durante la cadena se conservó el correlationId
    assertThat(threadIdInsideChain.get()).isEqualTo(validId);
    assertThat(mdcIdInsideChain.get()).isEqualTo(validId);

    // Valida cabecera HTTP de salida
    assertThat(response.getHeader(CorrelationContext.HEADER_NAME)).isEqualTo(validId);

    // Valida limpieza garantizada tras finalizar petición
    assertThat(CorrelationContext.get()).isNull();
    assertThat(MDC.get(CorrelationContext.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("Request sin header X-Correlation-Id genera UUID v4 y lo devuelve en response")
  public void requestWithoutHeader_shouldGenerateUuid() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    AtomicReference<String> generatedId = new AtomicReference<>();

    FilterChain chain = (req, res) -> {
      generatedId.set(CorrelationContext.get());
    };

    filter.doFilter(request, response, chain);

    assertThat(generatedId.get()).isNotNull();
    assertThat(UUID.fromString(generatedId.get())).isNotNull();

    String responseHeader = response.getHeader(CorrelationContext.HEADER_NAME);
    assertThat(responseHeader).isEqualTo(generatedId.get());

    assertThat(CorrelationContext.get()).isNull();
    assertThat(MDC.get(CorrelationContext.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("Header malicioso con saltos de línea (Log/Header Injection) es reemplazado por un UUID seguro")
  public void requestWithMaliciousHeader_shouldBeReplaced() throws ServletException, IOException {
    String maliciousId = "safe-prefix\r\nInjected-Header: evil\n[ADMIN] Granted";
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(CorrelationContext.HEADER_NAME, maliciousId);
    MockHttpServletResponse response = new MockHttpServletResponse();

    AtomicReference<String> contextId = new AtomicReference<>();

    FilterChain chain = (req, res) -> {
      contextId.set(CorrelationContext.get());
    };

    filter.doFilter(request, response, chain);

    assertThat(contextId.get()).isNotNull();
    assertThat(contextId.get()).isNotEqualTo(maliciousId);
    assertThat(UUID.fromString(contextId.get())).isNotNull();

    String responseHeader = response.getHeader(CorrelationContext.HEADER_NAME);
    assertThat(responseHeader).isEqualTo(contextId.get());
  }

  @Test
  @DisplayName("Header con longitud excesiva (>64 caracteres) es reemplazado por UUID seguro")
  public void requestWithExcessiveLength_shouldBeReplaced() throws ServletException, IOException {
    String longId = "a".repeat(100);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(CorrelationContext.HEADER_NAME, longId);
    MockHttpServletResponse response = new MockHttpServletResponse();

    AtomicReference<String> contextId = new AtomicReference<>();

    FilterChain chain = (req, res) -> {
      contextId.set(CorrelationContext.get());
    };

    filter.doFilter(request, response, chain);

    assertThat(contextId.get()).isNotNull();
    assertThat(contextId.get()).isNotEqualTo(longId);
    assertThat(UUID.fromString(contextId.get())).isNotNull();
  }

  @Test
  @DisplayName("Fin de petición limpia el MDC garantizado incluso si la cadena lanza excepción")
  public void filterChainThrowsException_shouldAlwaysCleanUpMdc() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(CorrelationContext.HEADER_NAME, "valid-corr-id-fail");
    MockHttpServletResponse response = new MockHttpServletResponse();

    FilterChain failingChain = (req, res) -> {
      throw new RuntimeException("Simulated unexpected failure in controller");
    };

    assertThatThrownBy(() -> filter.doFilter(request, response, failingChain))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Simulated unexpected failure in controller");

    // Limpieza post-excepción
    assertThat(CorrelationContext.get()).isNull();
    assertThat(MDC.get(CorrelationContext.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("Peticiones consecutivas en el mismo hilo no contaminan el contexto")
  public void consecutiveRequestsOnSameThread_doNotPolluteContext() throws ServletException, IOException {
    MockHttpServletRequest req1 = new MockHttpServletRequest();
    req1.addHeader(CorrelationContext.HEADER_NAME, "req-1-id");
    MockHttpServletResponse res1 = new MockHttpServletResponse();

    AtomicReference<String> seen1 = new AtomicReference<>();
    filter.doFilter(req1, res1, (req, res) -> seen1.set(CorrelationContext.get()));

    assertThat(seen1.get()).isEqualTo("req-1-id");
    assertThat(CorrelationContext.get()).isNull();

    // Segunda petición sin cabecera en el mismo hilo
    MockHttpServletRequest req2 = new MockHttpServletRequest();
    MockHttpServletResponse res2 = new MockHttpServletResponse();

    AtomicReference<String> seen2 = new AtomicReference<>();
    filter.doFilter(req2, res2, (req, res) -> seen2.set(CorrelationContext.get()));

    assertThat(seen2.get()).isNotNull();
    assertThat(seen2.get()).isNotEqualTo("req-1-id");
    assertThat(UUID.fromString(seen2.get())).isNotNull();
    assertThat(CorrelationContext.get()).isNull();
  }

}
