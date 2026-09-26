package tacos.correlation;

import java.util.UUID;

import org.slf4j.MDC;

import reactor.util.context.Context;
import reactor.util.context.ContextView;


//Manejo unificado de contexto para el Correlation ID en hilos y en flujos reactivos (Project Reactor Context).

public final class CorrelationContext {

  public static final String HEADER_NAME = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";
  public static final String REACTOR_KEY = "correlationId";

  private static final ThreadLocal<String> CURRENT_CORRELATION_ID = new ThreadLocal<>();

  private CorrelationContext() {} // Utility class

  public static String get() {
    return CURRENT_CORRELATION_ID.get();
  }

  public static String getOrGenerate() {
    String current = get();
    if (current != null && !current.isEmpty()) {
      return current;
    }
    String generated = UUID.randomUUID().toString();
    set(generated);
    return generated;
  }

  public static void set(String correlationId) {
    if (correlationId != null) {
      CURRENT_CORRELATION_ID.set(correlationId);
      MDC.put(MDC_KEY, correlationId);
    } else {
      clear();
    }
  }

  public static void clear() {
    CURRENT_CORRELATION_ID.remove();
    MDC.remove(MDC_KEY);
  }

  /**
   * Adjunta el correlationId a un Reactor Context.
   */
  public static Context withCorrelationId(Context context, String correlationId) {
    return context.put(REACTOR_KEY, correlationId);
  }

  /**
   * Extrae el correlationId de un ContextView reactivo, con fallback al ThreadLocal o generación.
   */
  public static String extractFrom(ContextView contextView) {
    if (contextView != null && contextView.hasKey(REACTOR_KEY)) {
      return contextView.get(REACTOR_KEY);
    }
    return getOrGenerate();
  }

}
