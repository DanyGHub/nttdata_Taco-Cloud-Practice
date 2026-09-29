# Laboratorio 2: Contratos y seguridad (TC-07 a TC-12)

[⬅️ Laboratorio 1 — Cazar Operaciones Fantasma](laboratorio-1-cazar-operaciones-fantasma.md) | [Volver al Índice General](README.md) | [Siguiente: Laboratorio 3 — Motor de Negocio ➡️](laboratorio-3-motor-de-negocio.md)

---

## Resumen Ejecutivo del Laboratorio

El segundo laboratorio de Taco Cloud marca la transición de una arquitectura preliminar con fallas de diseño hacia una **plataforma empresarial robusta, desacoplada y fuertemente protegida**.

Sus metas centrales consisten en:
1. **Unificar efectos asíncronos**: Erradicar suscripciones concurrentes que duplican ejecuciones frías al guardar en base de datos y publicar al broker de mensajería.
2. **Desacoplar la API de la Persistencia (DTOs)**: Dejar de exponer directamente las entidades de MongoDB hacia el cliente, previniendo fuga de datos internos y ataques de asignación masiva (*Mass Assignment*).
3. **Estandarizar Respuestas de Error**: Implementar el estándar de la industria **RFC 7807 (Problem Details for HTTP APIs)** para todas las respuestas `4xx` y `5xx`.
4. **Seguridad y Criptografía Moderna**: Reemplazar codificadores inseguros por hash adaptativo con sal (**BCrypt**) y aplicar el principio de mínimo privilegio con la regla estricta **Deny-by-default**.
5. **Cumplimiento Normativo Financiero (PCI-DSS)**: Desterrar de raíz el almacenamiento de números de tarjeta (PAN) y códigos de seguridad (CVV) del modelo de dominio, adoptando una arquitectura de **Tokenización segura**.

---

## Mapa de Retos y Matriz de Contratos

| Reto | Nombre | Módulo Maven | Endpoint / Componente | Método HTTP | Códigos HTTP |
| :--- | :--- | :--- | :--- | :---: | :---: |
| **TC-07** | Una sola suscripción para guardar y publicar | `tacocloud-api` | `/api/v1/orders/fromEmail` | `POST` | `201`, `400` |
| **TC-08** | Separar DTOs de entrada, respuesta y persistencia | `tacocloud-api` | `/api/v1/orders`, `/api/v1/ingredients` | `POST`, `GET`, `PUT` | `200`, `201`, `400`, `404` |
| **TC-09** | Validación y errores tipo Problem Details | `tacocloud-api` | `RestExceptionHandler` (`@ControllerAdvice`) | *Global* | `400`, `403`, `404`, `409`, `422` |
| **TC-10** | Registro reactivo con contraseñas protegidas | `tacocloud-security` | `/register`, `/api/v1/users` | `POST` | `201`, `302`, `400`, `409` |
| **TC-11** | Autorización deny-by-default y roles útiles | `tacocloud-security` | Matriz de seguridad (`SecurityConfig`) | *All Routes* | `200`, `401`, `403` |
| **TC-12** | Tokenizar pago y eliminar PAN/CVV del dominio | `tacocloud-domain` | `TacoOrder`, `PaymentMethod`, Purge Service | `POST /api/v1/orders` | `201`, `400`, `422` |

---

## TC-07 — Una sola suscripción para guardar y publicar

* **Nivel**: Intermedio | **Puntos**: 8 | **Laboratorio**: 2 | **Dependencias**: TC-06

### 1. Objetivo del Reto y Resultado Funcional
Garantizar que una orden proveniente de un canal asíncrono (como correo electrónico o REST) se convierta, se persista en base de datos y se publique al broker de eventos **exactamente una vez**. Se debe eliminar la duplicidad de ejecuciones producida por múltiples suscripciones manuales y definir formalmente la política transaccional de guardado previo a la publicación.

### 2. Archivos Objetivo y Código Base
* **Controlador**: [`OrderApiController.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/OrderApiController.java)
* **Servicio de Mensajería**: [`OrderMessagingService.java`](../tacocloud/tacocloud-messaging-contract/src/main/java/tacos/messaging/OrderMessagingService.java)
* **Pruebas Automatizadas**: [`OrderApiControllerTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/web/api/OrderApiControllerTest.java)

### 3. Cambios Realizados y Arquitectura

#### El Código Anterior (Con Doble Suscripción y Carrera):
```java
// CÓDIGO ORIGINAL:
@PostMapping(path="/fromEmail")
public Mono<TacoOrder> postOrderFromEmail(@RequestBody Mono<EmailOrder> emailOrder) {
  Mono<TacoOrder> order = emailOrderService.convertEmailOrderToDomainOrder(emailOrder);
  
  order.subscribe(orderMessages::sendOrder); // SUSCRIPCIÓN MANUAL 1
  return order.flatMap(repo::save);          // SUSCRIPCIÓN 2
}
```
*En Project Reactor, los publishers son típicamente fríos (*cold*). Al ejecutar `order.subscribe(...)` manualmente y luego retornar `order.flatMap(repo::save)`, toda la lógica de conversión se ejecutaba dos veces en paralelo, enviando la orden al broker antes de que estuviera guardada e incluso si la base de datos fallaba.*

#### Solución Implementada:
1. Se unificó el flujo bajo una política estricta de **Guardar antes de Enviar** (*Save-then-Send*):
   $$\text{Conversión} \longrightarrow \text{Persistencia en MongoDB} \longrightarrow \text{Publicación en Broker} \longrightarrow \text{Respuesta HTTP 201}$$
2. Se encadenaron las operaciones mediante composición reactiva con `.flatMap()` y callbacks no intrusivos (`.doOnSuccess()`):
   - Si la conversión falla, no se persiste ni se publica.
   - Si la persistencia en MongoDB falla, la cadena se interrumpe y el evento jamás se envía a la cocina.
3. Se retorna un único `Mono<OrderResponse>` que el framework suscribe una sola vez.

```java
// CÓDIGO CORREGIDO:
@PostMapping(path="/fromEmail", consumes="application/json")
@ResponseStatus(HttpStatus.CREATED)
public Mono<OrderResponse> postOrderFromEmail(@RequestBody Mono<EmailOrder> emailOrder) {
  return emailOrderService.convertEmailOrderToDomainOrder(emailOrder)
      .flatMap(order -> repo.save(order))
      .doOnSuccess(saved -> {
        if (saved != null && orderMessages != null) {
          orderMessages.sendOrder(orderEventMapper.toOrderCreatedEvent(saved));
        }
      })
      .map(orderMapper::toResponse);
}
```

### 4. Especificación Funcional Prevista (Cómo debió trabajar)

El diseño conceptual y contractual establecido para este reto busca resolver la recepción asíncrona de pedidos mediante la composición reactiva pura:
* **Entrada**: Una petición HTTP `POST` a `/api/v1/orders/fromEmail` con el correo electrónico del cliente emisor y el listado de tacos con sus ingredientes.
* **Flujo Previsto**:
  1. `EmailOrderService` resuelve el usuario por su correo electrónico y recupera su método de pago registrado.
  2. Valida la existencia de cada ingrediente consultando el catálogo y construye la entidad de dominio `TacoOrder`.
  3. Ejecuta la política **Save-then-Send**: persiste la orden en MongoDB y, tras la confirmación exitosa del guardado, emite exactamente un evento `OrderEvent` al broker hacia la cocina.
  4. Retorna un código **`201 Created`** con la representación formateada de la orden (`OrderResponse`), garantizando una única suscripción y evitando duplicidades.

### 5. Situación Arquitectónica y Continuidad en Retos Posteriores

Al analizar este reto en el contexto global del proyecto, este endpoint representaba una solución preliminar en el Laboratorio 2, la cual sirvió como punto de partida para identificar dependencias rígidas y áreas de oportunidad que fueron **retomadas, refinadas y resueltas definitivamente en laboratorios posteriores**:

1. **Evolución del Modelo de Pagos y Tokenización (Reto TC-12 — Laboratorio 2)**:
   * Inicialmente, la resolución de pagos dependía de datos bancarios acoplados directamente al usuario. En el Reto TC-12 se implementó la tokenización completa compatible con PCI-DSS (`PaymentMethod` con token opaco), desacoplando los datos sensibles de la orden.
2. **Superación del Problema de la Doble Escritura (Dual-Write) y Adopción del Transactional Outbox (Reto TC-29 y TC-30 — Laboratorio 5)**:
   * La política *Guardar y luego Publicar* de este reto dejaba una ventana de fallo abierta: si el broker caía inmediatamente después de guardar en MongoDB, el mensaje para la cocina se perdía.
   * Por ello, en el **Laboratorio 5 (Reto TC-29: Outbox Transaccional para órdenes desde email)**, el método `postOrderFromEmail` fue completamente refundado para delegar en `OrderPlacementService`. En lugar de emitir al broker de forma directa, la orden y un registro `OutboxEvent` se guardan en MongoDB dentro de la misma transacción atómica, y un despachador en segundo plano (*Outbox Poller*) entrega el mensaje con reintentos hacia Kafka, RabbitMQ o JMS.
3. **Resiliencia, Idempotencia y Trazabilidad (Retos TC-31 a TC-36 — Laboratorios 5 y 6)**:
   * El procesamiento de órdenes asíncronas se complementó posteriormente con llaves de idempotencia contra pedidos duplicados (TC-28), Dead Letter Queues (DLQ) para descartar fallos de cocina (TC-30), correlación distribuida (`X-Correlation-ID` en TC-33) y métricas de latencia con Micrometer (TC-34).

### 6. Verificación de la Solución (Pruebas Automatizadas)

Debido a que este reto establece una garantía de suscripción reactiva en la capa de servicio y controlador (erradicar `.subscribe()` manual y llamadas duplicadas), su verificación formal se valida a través de la suite automatizada de pruebas unitarias y de integración reactiva:

#### Casos de Prueba Verificados en `OrderApiControllerTest.java`:
1. **`shouldCreateOrderFromEmail`**: Valida que una orden por email válida desencadene exactamente una persistencia en el repositorio (`times(1)`) y exactamente una publicación de evento (`times(1)`), respondiendo con estado HTTP `201 Created`.
2. **`shouldNotPersistNorPublishWhenConversionFails`**: Valida que ante cualquier error en la conversión (usuario o ingrediente no reconocido), la ejecución se interrumpa inmediatamente sin persistir en la base de datos (`never()`) ni enviar mensajes al broker (`never()`).
3. **`shouldNotPublishWhenPersistenceFails`**: Valida que si la base de datos experimenta un fallo de persistencia, la publicación al broker quede totalmente bloqueada, protegiendo a la cocina de pedidos fantasma.

#### Ejecución del Comando Maven:
```powershell
mvn test -f tacocloud/pom.xml -pl tacocloud-api -Dtest=OrderApiControllerTest#shouldCreateOrderFromEmail,OrderApiControllerTest#shouldNotPersistNorPublishWhenConversionFails,OrderApiControllerTest#shouldNotPublishWhenPersistenceFails
```

* **Resultado de la Ejecución**:
  ```text
  [INFO] Running tacos.web.api.OrderApiControllerTest
  [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.821 s - in tacos.web.api.OrderApiControllerTest
  [INFO] BUILD SUCCESS
  ```

* **Evidencia Verificación de Suscripción Reactiva Única (JUnit / Maven)**:
  ![TC-07 Verificación Unitaria](images/lab2/tc07_unit_tests_success.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿“Guardar y luego publicar” es atómico? ¿Qué ventana de fallo sigue abierta?*

**Defensa Técnica**:
> **No, guardar y luego publicar no es una operación atómica**; constituye el problema clásico de la escritura doble (*Dual-Write Problem*).
> 
> La ventana de fallo que permanece abierta ocurre en el lapso exacto transcurrido **después de que MongoDB confirma el guardado, pero antes de que el mensaje alcance con éxito al broker de eventos**:
> 1. Si la red se desconecta, el proceso colapsa por *OutOfMemory*, o el pod/servidor se reinicia repentinamente en ese instante, la orden existirá en la base de datos de MongoDB, pero la cocina nunca recibirá el evento: **la orden se pierde para la operación culinaria**.
> 2. Si se intentara la alternativa inversa (*Enviar y luego Guardar*), un fallo en la base de datos provocaría que la cocina prepare un pedido fantasma que jamás existió en el sistema.
> 
> *Nota de evolución arquitectónica*: Esta ventana de fallo se aceptó temporalmente como deuda técnica en este laboratorio y fue **resuelta definitivamente en el TC-29 mediante el patrón Transactional Outbox**.

### 8. Criterios de Aceptación Cumplidos
- [x] Una petición genera exactamente una persistencia y exactamente una emisión de evento, jamás dos.
- [x] Un error de conversión no persiste ni publica datos parciales.
- [x] Un error de base de datos bloquea la publicación hacia el broker.
- [x] Cero suscripciones manuales (`.subscribe()`) dentro del controlador.

---

## TC-08 — Separar DTOs de entrada, respuesta y persistencia

* **Nivel**: Intermedio | **Puntos**: 8 | **Laboratorio**: 2 | **Dependencias**: TC-01 a TC-07 recomendados

### 1. Objetivo del Reto y Resultado Funcional
Desacoplar la capa web de la capa de almacenamiento físico. La API debe dejar de exponer directamente entidades de MongoDB (`TacoOrder`, `Ingredient`, `User`), impidiendo que clientes maliciosos manipulen atributos internos mediante asignación masiva (*Mass Assignment*) o que la API filtre contraseñas hasheadas, roles de seguridad o datos financieros sensibles en las respuestas JSON.

### 2. Archivos Objetivo y Código Base
* **Paquete de DTOs**: [`tacos.web.api.dto`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/dto/)
* **Mappers**: [`OrderMapper.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/dto/OrderMapper.java), [`IngredientMapper.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/dto/IngredientMapper.java)
* **Pruebas de Serialización**: [`OrderDtoAndSerializationTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/web/api/OrderDtoAndSerializationTest.java)

### 3. Cambios Realizados y Arquitectura

#### El Código Anterior (Acoplamiento de Entidades y Fuga de Datos):
```java
// CÓDIGO ORIGINAL:
@PostMapping(consumes="application/json")
public Mono<TacoOrder> postOrder(@RequestBody TacoOrder order) { // Expone entidad de persistencia
  return repo.save(order); // El cliente podía forzar id, placedAt, status o mutar el User
}
```

#### Solución Implementada:
Se diseñó un ecosistema de contratos explícitos:
1. **DTOs de Entrada (Requests)**:
   - `OrderCreateRequest`: Exige únicamente datos de entrega, token de pago y lista de tacos deseados (`OrderItemRequest`). No admite campos controlados por el servidor (`id`, `placedAt`, `status`, `totalPrice`).
   - `IngredientRequest`: Requiere únicamente `id`, `name` y `type`.
2. **DTOs de Salida (Responses)**:
   - `OrderResponse`: Expone identificadores comerciales limpios, fechas, importes calculados y metadatos no sensibles de pago (`brand`, `last4`). Excluye por completo objetos `User` internos, contraseñas y autoridades.
   - `IngredientResponse`: Estructura JSON canónica del catálogo.
3. **Mappers Funcionales Puros**: Clases dedicadas a la transformación de datos sin lógica de negocio ni consultas a base de datos.

```java
// CÓDIGO CORREGIDO:
@PostMapping(consumes="application/json")
@ResponseStatus(HttpStatus.CREATED)
public Mono<OrderResponse> postOrder(
    @Valid @RequestBody OrderCreateRequest request, 
    Principal principal) {
  
  return resolveUser(principal)
      .flatMap(user -> orderApplicationService.placeOrder(request, user))
      .map(orderMapper::toResponse);
}
```

### 4. Contratos y Especificación HTTP

| DTO | Propósito | Campos Clave Incluidos | Campos Estrictamente Excluidos |
| :--- | :--- | :--- | :--- |
| **`OrderCreateRequest`** | Crear orden | `deliveryName`, `deliveryStreet`, `paymentMethodId`, `items` | `id`, `placedAt`, `status`, `totalPrice`, `user` |
| **`OrderResponse`** | Respuesta pública | `id`, `deliveryName`, `placedAt`, `status`, `brand`, `last4`, `items` | `password`, `ccNumber`, `ccCVV`, `authorities` |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Creación de Orden con DTO de Request (201 Created)
* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/orders`
* **Headers**:
  - `Content-Type: application/json`
  - `Authorization: Basic aGFidW1hOnBhc3N3b3Jk` (`habuma:password`)
* **Body**:
  ```json
  {
    "deliveryName": "Craig Walls",
    "deliveryStreet": "123 North Street",
    "deliveryCity": "Cross Roads",
    "deliveryState": "TX",
    "deliveryZip": "76227",
    "paymentMethodId": "pm-test-token-4111",
    "items": [
      {
        "taco": {
          "name": "Carnitas Special",
          "ingredientIds": ["FLTO", "CARN", "SLSA"]
        },
        "quantity": 1
      }
    ]
  }
  ```
* **Comando cURL**:
  ```bash
  curl -X POST http://localhost:8080/api/v1/orders \
    -u habuma:password \
    -H "Content-Type: application/json" \
    -d "{\"deliveryName\":\"Craig Walls\",\"deliveryStreet\":\"123 North Street\",\"deliveryCity\":\"Cross Roads\",\"deliveryState\":\"TX\",\"deliveryZip\":\"76227\",\"paymentMethodId\":\"pm-test-token-4111\",\"items\":[{\"taco\":{\"name\":\"Carnitas Special\",\"ingredientIds\":[\"FLTO\",\"CARN\",\"SLSA\"]},\"quantity\":1}]}"
  ```
* **Respuesta Esperada**: `201 Created`. El JSON de respuesta contiene `OrderResponse` sin rastro de contraseñas ni objetos User internos de MongoDB.

#### Caso 2: Intento de Mass Assignment (Campos Ignorados o Rechazados)
* Si el cliente intenta inyectar `"totalPrice": 0.01` o `"status": "DELIVERED"` en el cuerpo JSON, los campos son ignorados por el serializador o la petición se rechaza, protegiendo las propiedades gobernadas por el servidor.

### 6. Espacio para Evidencias de Ejecución (Postman)
* **Evidencia Creación de Orden con DTOs Seguros (201 Created)**:
  ![TC-08 DTO 201 Created](images/lab2/tc08_post_order_dto_201.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿Una entidad de persistencia es automáticamente un buen contrato público?*

**Defensa Técnica**:
> **No, casi nunca lo es**. Utilizar entidades de base de datos como contratos HTTP públicos introduce tres fallas arquitectónicas severas:
> 1. **Acoplamiento Rígido**: Cualquier cambio estructural en la base de datos (renombrar una columna, añadir una relación de persistencia interna) rompe inmediatamente la interfaz con todos los clientes web o móviles externos.
> 2. **Fuga de Información Sensible**: Las entidades suelen albergar hashes de contraseñas, marcas de auditoría, llaves foráneas o datos protegidos que se serializan involuntariamente hacia el exterior.
> 3. **Vulnerabilidad de Asignación Masiva (*Mass Assignment*)**: Permite que un cliente inyecte propiedades que deberían ser de solo lectura o exclusivas del servidor (como roles, estados de aprobación o precios).
> 
> Los DTOs desacoplan ambas capas, permitiendo que la API sea un contrato formal, estable y seguro mientras el modelo de persistencia evoluciona libremente.

### 8. Criterios de Aceptación Cumplidos
- [x] La serialización de la orden no contiene `password`, `ccNumber`, `ccCVV` ni `authorities`.
- [x] El cliente no puede forzar `id`, `placedAt`, `status`, `totalPrice` ni asociar órdenes a usuarios ajenos.
- [x] Los controladores no reciben `TacoOrder` directamente en el cuerpo de la petición.

---

## TC-09 — Validación y errores tipo Problem Details

* **Nivel**: Intermedio | **Puntos**: 8 | **Laboratorio**: 2 | **Dependencias**: TC-08

### 1. Objetivo del Reto y Resultado Funcional
Unificar y profesionalizar las respuestas de error en toda la API. Se debe implementar el estándar **RFC 7807 (Problem Details for HTTP APIs)** para que cualquier fallo de validación, conflicto de datos, recurso inexistente o denegación de permisos devuelva un payload JSON consistente, claro y procesable por la interfaz de usuario, sin exponer jamás trazas internas de depuración (*stack traces*) ni detalles técnicos de drivers.

### 2. Archivos Objetivo y Código Base
* **DTO Problem Details**: [`ApiProblem.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/dto/ApiProblem.java)
* **Manejador Global de Excepciones**: [`RestExceptionHandler.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/RestExceptionHandler.java)
* **Pruebas de Validación**: [`ValidationAndProblemDetailsTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/web/api/ValidationAndProblemDetailsTest.java)

### 3. Cambios Realizados y Arquitectura

#### El Comportamiento Anterior (Respuestas Heterogéneas y Stacktraces):
*Anteriormente, una validación fallida arrojaba un volcado HTML genérico o un JSON dependiente de Tomcat/WebFlux con volcados de excepciones (`NullPointerException`, `MongoException`), revelando la estructura interna del servidor y dificultando el manejo de errores en el frontend.*

#### Solución Implementada:
1. Se creó el DTO `ApiProblem` estructurado de acuerdo a la especificación estándar RFC 7807:
   - `type`: URI con la categoría de error (ej. `https://tacocloud.com/errors/validation-failed`).
   - `title`: Título legible del problema (ej. `"Validation Error"`).
   - `status`: Código de estado HTTP equivalente (ej. `400`).
   - `detail`: Descripción humana y segura del error.
   - `instance`: URI del endpoint específico que originó el problema.
   - `fieldErrors`: Mapa detallado con cada campo inválido y su motivo (`{ "deliveryZip": "Must be 5 numeric digits" }`).
2. Se implementó `@RestControllerAdvice` global (`RestExceptionHandler`) que captura:
   - `MethodArgumentNotValidException` / `WebExchangeBindException` $\rightarrow$ `400 Bad Request`.
   - `ResponseStatusException` (Not Found, Forbidden) $\rightarrow$ `404` / `403`.
   - Conflictos de unicidad (`DuplicateKeyException`) $\rightarrow$ `409 Conflict`.
   - Excepciones de negocio (`StockUnavailableException`) $\rightarrow$ `422 Unprocessable Entity` / `400`.

```json
// EJEMPLO DE RESPUESTA RFC 7807 GENERADA:
{
  "type": "https://tacocloud.com/errors/validation-failed",
  "title": "Validation Error",
  "status": 400,
  "detail": "Invalid fields present in the request",
  "instance": "/api/v1/orders",
  "fieldErrors": {
    "deliveryZip": "Must match a valid 5-digit ZIP code",
    "deliveryStreet": "Delivery street is required"
  }
}
```

### 4. Contratos y Especificación HTTP

| Código HTTP | Escenario de Aplicación | Título de Problem Details |
| :---: | :--- | :--- |
| **`400 Bad Request`** | Parámetros mal formados o campos que violan Bean Validation | `"Validation Error"` |
| **`403 Forbidden`** | El usuario autenticado no tiene permisos de rol ni propiedad | `"Access Denied"` |
| **`404 Not Found`** | Identificador inexistente en base de datos | `"Resource Not Found"` |
| **`409 Conflict`** | Conflicto de estado o colisión de clave única (username/idempotencia) | `"Resource Conflict"` |
| **`422 Unprocessable`** | Entidad semánticamente correcta pero inviable (ej. sin stock) | `"Business Rule Violation"` |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Provocar Error de Validación Estructurado (400 Bad Request)
* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/orders`
* **Headers**:
  - `Content-Type: application/json`
  - `Authorization: Basic aGFidW1hOnBhc3N3b3Jk`
* **Body**:
  ```json
  {
    "deliveryName": "",
    "deliveryStreet": "",
    "deliveryZip": "ZIP_INVALIDO",
    "paymentMethodId": "",
    "items": []
  }
  ```
* **Comando cURL**:
  ```bash
  curl -i -X POST http://localhost:8080/api/v1/orders \
    -u habuma:password \
    -H "Content-Type: application/json" \
    -d "{\"deliveryName\":\"\",\"deliveryStreet\":\"\",\"deliveryZip\":\"ZIP_INVALIDO\",\"paymentMethodId\":\"\",\"items\":[]}"
  ```
* **Respuesta Esperada**: `400 Bad Request`. Estructura completa `ApiProblem` con mapa de `fieldErrors` listando los campos fallidos sin mostrar ningún stack trace.

### 6. Espacio para Evidencias de Ejecución (Postman)
* **Evidencia Error RFC 7807 Problem Details (400 Bad Request)**:
  ![TC-09 Problem Details 400](images/lab2/tc09_problem_details_400.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿Cuándo usar 400, 409 y 422? Defina la política del curso y úsela consistentemente.*

**Defensa Técnica**:
> La política de estados HTTP adoptada sigue los estándares RFC 7231 y RFC 4918:
> - **`400 Bad Request` (Sintaxis y Validación de Formato)**: Se utiliza cuando el mensaje enviado por el cliente es fundamentalmente inválido a nivel sintáctico o formal (ej. un JSON mal cerrado, un campo numérico que contiene letras, campos obligatorios nulos o un ZIP que no respeta el formato de 5 dígitos).
> - **`409 Conflict` (Conflicto de Estado en el Servidor)**: Se reserva para colisiones con el estado actual del almacenamiento. Aplica cuando el cliente intenta crear un recurso cuyo identificador único ya existe (ej. registrar un username duplicado en TC-10), cuando una clave de idempotencia colisiona con un payload modificado (TC-34), o en fallas de concurrencia optimista.
> - **`422 Unprocessable Entity` (Violación de Regla de Negocio Semántica)**: Se emplea cuando el payload es sintácticamente impecable y supera las validaciones de formato, pero **viola una regla semántica del negocio** (ej. ordenar tacos cuyos ingredientes no tienen inventario en bodega o solicitar cupones vencidos).

### 8. Criterios de Aceptación Cumplidos
- [x] Todas las respuestas de error `4xx/5xx` comparten el esquema canónico `ApiProblem`.
- [x] Las órdenes inválidas listan campos y motivos exactos sin revelar secretos de implementación.
- [x] Ningún error revela stack traces ni nombres de clases de drivers internos.

---

## TC-10 — Registro reactivo con contraseñas protegidas

* **Nivel**: Intermedio | **Puntos**: 8 | **Laboratorio**: 2 | **Dependencias**: TC-09 recomendado

### 1. Objetivo del Reto y Resultado Funcional
Asegurar el proceso de registro de usuarios (`/register` y `/api/v1/users`). La contraseña debe persistirse siempre transformada mediante un algoritmo de hash criptográfico adaptativo con sal (*salt*), garantizando la reactividad en el guardado en MongoDB y protegiendo la base de datos contra colisiones de nombres de usuario mediante índices únicos.

### 2. Archivos Objetivo y Código Base
* **Controlador de Registro**: [`RegistrationController.java`](../tacocloud/tacocloud-security/src/main/java/tacos/security/RegistrationController.java)
* **Configuración de Seguridad**: [`SecurityConfig.java`](../tacocloud/tacocloud-security/src/main/java/tacos/security/SecurityConfig.java)
* **Repositorio de Usuarios**: [`UserRepository.java`](../tacocloud/tacocloud-data-mongodb/src/main/java/tacos/data/UserRepository.java)
* **Pruebas**: [`RegistrationControllerTest.java`](../tacocloud/tacocloud-security/src/test/java/tacos/security/RegistrationControllerTest.java)

### 3. Cambios Realizados y Arquitectura

#### El Código Anterior (Contraseñas Inseguras o En Texto Plano):
```java
// CÓDIGO ORIGINAL:
@Bean
public PasswordEncoder encoder() {
  return NoOpPasswordEncoder.getInstance(); // Almacenamiento en texto plano
}
// En el registro síncrono sin encadenar publisher:
userRepo.save(form.toUser(passwordEncoder)); // Operación fantasma o síncrona
```

#### Solución Implementada:
1. En `SecurityConfig`, se reemplazó el encoder por `PasswordEncoderFactories.createDelegatingPasswordEncoder()`, el cual utiliza **BCrypt** de forma predeterminada anteponiendo el prefijo identificador `{bcrypt}`.
2. En `UserRepository`, se estableció el índice único `@Indexed(unique = true)` sobre el campo `username`.
3. El controlador reactivo valida la unicidad previa del usuario con `switchIfEmpty()` y captura colisiones concurrentes de `DuplicateKeyException` para responder de forma segura con `409 Conflict`.
4. El hash generado jamás se devuelve en la respuesta HTTP ni en logs de depuración.

```java
// CÓDIGO CORREGIDO :
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public Mono<ResponseEntity<UserResponse>> registerUser(@Valid @RequestBody RegistrationForm form) {
  return userRepo.findByUsername(form.getUsername())
      .flatMap(existing -> Mono.<ResponseEntity<UserResponse>>error(
          new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists")
      ))
      .switchIfEmpty(
          Mono.defer(() -> {
            User user = form.toUser(passwordEncoder);
            user.setRoles(Arrays.asList("ROLE_USER"));
            return userRepo.save(user)
                .map(saved -> ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved)));
          })
      );
}
```

### 4. Contratos y Especificación HTTP

| Parámetro | Detalle |
| :--- | :--- |
| **Método / URL** | `POST http://localhost:8080/api/v1/users` (o vista `/register`) |
| **Headers** | `Content-Type: application/json` |
| **Cuerpo (JSON)** | `{"username": "nuevo_cliente", "password": "SuperSecretPassword123", "fullname": "Nuevo Usuario", "street": "Main St 1", "city": "Aguascalientes", "state": "AGS", "zip": "20000", "phone": "555-010-9999", "email": "nuevo@tacocloud.com"}` |
| **Respuestas** | `201 Created` (Usuario registrado con éxito), `409 Conflict` (Username ya existente) |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Registro Exitoso de Nuevo Usuario (201 Created)
* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/users`
* **Headers**: `Content-Type: application/json`
* **Body**:
  ```json
  {
    "username": "taco_fan_2026",
    "password": "PasswordSegura99!",
    "fullname": "Taco Lover",
    "street": "Avenida Revolución 45",
    "city": "Aguascalientes",
    "state": "AGS",
    "zip": "20000",
    "phone": "449-123-4567",
    "email": "tacofan@gmail.com"
  }
  ```
* **Comando cURL**:
  ```bash
  curl -i -X POST http://localhost:8080/api/v1/users \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"taco_fan_2026\",\"password\":\"PasswordSegura99!\",\"fullname\":\"Taco Lover\",\"street\":\"Avenida Revolución 45\",\"city\":\"Aguascalientes\",\"state\":\"AGS\",\"zip\":\"20000\",\"phone\":\"449-123-4567\",\"email\":\"tacofan@gmail.com\"}"
  ```
* **Respuesta Esperada**: `201 Created`. En el body de respuesta aparece la información del usuario, pero el campo `password` no se incluye. En MongoDB, el campo contiene un hash seguro: `{bcrypt}$2a$10$...`.

#### Caso 2: Intento de Duplicación de Username (409 Conflict)
* Enviar nuevamente la misma petición anterior.
* **Respuesta Esperada**: `409 Conflict` ("Username already exists").

### 6. Espacio para Evidencias de Ejecución (Postman)
* **Evidencia Registro Exitoso con Contraseña Hasheada (201 Created)**:
  ![TC-10 Registro 201 Created](images/lab2/tc10_register_user_201.png)

* **Evidencia Colisión de Usuario Duplicado (409 Conflict)**:
  ![TC-10 409 Conflict](images/lab2/tc10_register_conflict_409.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿Por qué “cifrar” contraseñas no es la meta y sí lo es aplicar hash adaptativo con salt?*

**Defensa Técnica**:
> - **Cifrado (Criptografía Simétrica/Asimétrica)**: Es un mecanismo bidireccional diseñado para recuperar el texto original mediante una clave secreta. Si un atacante compromete la clave maestra del servidor, puede descifrar instantáneamente todas las contraseñas de la base de datos.
> - **Hash Adaptativo con Salt (BCrypt)**: Es una función estrictamente **unidireccional** e irreversible. 
>   1. **Salt Aleatorio**: Se incorpora una secuencia aleatoria única para cada usuario antes de calcular el hash, neutralizando por completo los ataques de tablas precalculadas (*Rainbow Tables*).
>   2. **Factor de Costo Adaptativo (*Work Factor*)**: Algoritmos como BCrypt implementan un costo computacional deliberadamente ajustable. Esto hace que intentar adivinar una contraseña por fuerza bruta requiera una cantidad masiva de procesamiento y tiempo para un atacante, mientras que para el servidor verificar un solo intento toma únicamente fracciones de segundo.

### 8. Criterios de Aceptación Cumplidos
- [x] La contraseña almacenada en MongoDB no coincide con el texto plano y `passwordEncoder.matches()` retorna `true`.
- [x] El nuevo usuario puede autenticarse inmediatamente tras el registro.
- [x] Intentos duplicados de registro con el mismo username emiten `409 Conflict`.
- [x] No hay descarte de publishers en la cadena de persistencia.

---

## TC-11 — Autorización deny-by-default y roles útiles

* **Nivel**: Avanzado | **Puntos**: 13 | **Laboratorio**: 2 | **Dependencias**: TC-10

### 1. Objetivo del Reto y Resultado Funcional
Reestructurar la seguridad perimetral de Taco Cloud para cumplir con el estándar **Deny-by-default** (*denegar por defecto*). La aplicación debe erradicar reglas abiertas globales (`permitAll` comodín) y establecer una matriz de autorización estricta donde cada ruta requiera permisos basados en roles útiles (`USER`, `ADMIN`, `KITCHEN`) y propiedad (*ownership*).

### 2. Archivos Objetivo y Código Base
* **Configuración de Seguridad**: [`SecurityConfig.java`](../tacocloud/tacocloud-security/src/main/java/tacos/security/SecurityConfig.java)
* **Pruebas de Seguridad**: [`SecurityAuthorizationTest.java`](../tacocloud/tacocloud/src/test/java/tacos/SecurityAuthorizationTest.java), [`SecurityDenyByDefaultRegressionTest.java`](../tacocloud/tacocloud/src/test/java/tacos/SecurityDenyByDefaultRegressionTest.java)

### 3. Cambios Realizados y Arquitectura

#### El Código Anterior (Permisivo por Defecto):
```java
// CÓDIGO ORIGINAL:
http.authorizeRequests()
    .antMatchers("/design", "/orders").hasRole("USER")
    .antMatchers("/", "/**").permitAll(); // Toda ruta no contemplada queda abierta al mundo
```

#### Solución Implementada:
1. Se construyó una **matriz de control de acceso explícita**:
   - **Público (`permitAll`)**: Catálogo de solo lectura (`GET /api/v1/ingredients`, `GET /api/v1/tacos`), especificación OpenAPI (`/openapi.yaml`), login, registro y vistas estáticas.
   - **`ROLE_USER`**: Creación de órdenes (`POST /api/v1/orders`), favoritos personales y consulta de historial propio.
   - **`ROLE_ADMIN`**: Administración del catálogo de ingredientes (`POST/PUT/DELETE /api/v1/ingredients`), gestión de inventario y endpoints privilegiados de Actuator (`/actuator/**`).
   - **`ROLE_KITCHEN`**: Acceso y despacho en la cola de cocina (`/kitchen/**`, `/api/v1/kitchen/**`).
2. Se selló la cadena con la regla inviolable: `.anyRequest().denyAll()`. Cualquier endpoint nuevo o no registrado en la configuración queda automáticamente denegado.

```java
// CÓDIGO CORREGIDO:
http.authorizeRequests()
    // 1. Catálogo público
    .antMatchers(HttpMethod.GET, "/api/ingredients/**", "/api/v1/ingredients/**").permitAll()
    .antMatchers("/openapi.yaml", "/api/v1/openapi.yaml").permitAll()
    // 2. Operaciones administrativas
    .antMatchers("/api/admin/**", "/api/v1/admin/**").hasRole("ADMIN")
    .antMatchers(HttpMethod.POST, "/api/v1/ingredients/**").hasRole("ADMIN")
    // 3. Clientes y Órdenes
    .antMatchers("/api/orders/**", "/api/v1/orders/**").hasAnyRole("USER", "ADMIN", "KITCHEN")
    // 4. Cocina
    .antMatchers("/kitchen/**", "/api/v1/kitchen/**").hasAnyRole("KITCHEN", "ADMIN")
    // 5. TC-11 Regla Final Obligatoria
    .anyRequest().denyAll();
```

### 4. Matriz de Autorización del Sistema

| Recurso / Ruta | Métodos | Público (Anónimo) | `ROLE_USER` | `ROLE_ADMIN` | `ROLE_KITCHEN` |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `/api/v1/ingredients` | `GET` | ✅ Permitido | ✅ Permitido | ✅ Permitido | ✅ Permitido |
| `/api/v1/ingredients` | `POST`, `PUT`, `DELETE` | ❌ 401 / 302 | ❌ 403 Forbidden | ✅ Permitido | ❌ 403 Forbidden |
| `/api/v1/orders` | `POST` | ❌ 401 / 302 | ✅ Permitido | ✅ Permitido | ❌ 403 Forbidden |
| `/actuator/metrics` | `GET` | ❌ 401 / 302 | ❌ 403 Forbidden | ✅ Permitido | ❌ 403 Forbidden |
| `/api/unmapped-endpoint` | Cualquier método | ❌ 401 / 302 | ❌ 403 Forbidden | ❌ 403 Forbidden | ❌ 403 Forbidden |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Acceso Anónimo al Catálogo Público (200 OK)
* **Método**: `GET`
* **URL**: `http://localhost:8080/api/v1/ingredients`
* **Respuesta Esperada**: `200 OK` con la lista de ingredientes.

#### Caso 2: Intento de Mutación de Catálogo por Usuario Regular (403 Forbidden)
* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/ingredients`
* **Headers**:
  - `Content-Type: application/json`
  - `Authorization: Basic aGFidW1hOnBhc3N3b3Jk` (`habuma:password`, rol USER)
* **Body**: `{"id": "TEST", "name": "Test Ingredient", "type": "WRAP"}`
* **Comando cURL**:
  ```bash
  curl -i -X POST http://localhost:8080/api/v1/ingredients \
    -u habuma:password \
    -H "Content-Type: application/json" \
    -d "{\"id\":\"TEST\",\"name\":\"Test Ingredient\",\"type\":\"WRAP\"}"
  ```
* **Respuesta Esperada**: `403 Forbidden`.

#### Caso 3: Prueba de Deny-by-default en Ruta Inexistente (401 o 403)
* **Método**: `GET`
* **URL**: `http://localhost:8080/api/ruta-accidental-no-mapeada`
* **Headers**: `Authorization: Basic aGFidW1hOnBhc3N3b3Jk`
* **Respuesta Esperada**: `403 Forbidden` (bloqueado por `.denyAll()`, jamás `200 OK`).

### 6. Espacio para Evidencias de Ejecución (Postman)
* **Evidencia Catálogo Público Permitido (200 OK)**:
  ![TC-11 Catálogo 200 OK](images/lab2/tc11_catalogo_publico_200.png)

* **Evidencia Mutación Bloqueada a Rol USER (403 Forbidden)**:
  ![TC-11 Admin 403 Forbidden](images/lab2/tc11_admin_forbidden_403.png)

* **Evidencia Deny-By-Default en Ruta Desconocida (403 Forbidden)**:
  ![TC-11 Deny All 403](images/lab2/tc11_deny_by_default_403.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿Qué falla más seguido: una regla equivocada o una ruta nueva que nadie agregó a la matriz?*

**Defensa Técnica**:
> **Una ruta nueva que nadie agregó a la matriz de seguridad falla con una frecuencia órdenes de magnitud superior**.
> 
> En arquitecturas con políticas permisivas por defecto (`permitAll` al final), cuando un desarrollador crea un nuevo controlador interno, un endpoint de depuración o una nueva API administrativa y olvida registrarla explícitamente en `SecurityConfig`, **el endpoint queda expuesto automáticamente a todo Internet**. 
> 
> Al implementar **Deny-by-default** (`.anyRequest().denyAll()`), cualquier ruta omitida o recién creada nace bloqueada. Si el desarrollador olvida agregarla, la consecuencia es que el test de integración o la prueba manual falla de inmediato con `403 Forbidden`, alertando al equipo antes de llegar a producción. Se transforma un riesgo crítico de seguridad en un simple recordatorio de configuración.

### 8. Criterios de Aceptación Cumplidos
- [x] Usuarios anónimos pueden consultar el catálogo pero no crear órdenes ni alterar inventario.
- [x] Clientes con rol `USER` pueden crear órdenes pero no administrar ingredientes ni consultar métricas.
- [x] Administradores tienen acceso exclusivo a la gestión del catálogo y endpoints de Actuator.
- [x] Cualquier ruta no mapeada expresamente queda denegada por defecto (`.denyAll()`).

---

## TC-12 — Tokenizar pago y eliminar PAN/CVV del dominio

* **Nivel**: Avanzado | **Puntos**: 13 | **Laboratorio**: 2 | **Dependencias**: TC-08, TC-09

### 1. Objetivo del Reto y Resultado Funcional
Garantizar el cumplimiento estricto del estándar de seguridad de la industria de tarjetas de pago **PCI-DSS**. La aplicación debe erradicar por completo el almacenamiento, procesamiento y tránsito del número completo de tarjeta (PAN) y el código de verificación (CVV) en el dominio de órdenes y eventos de cocina, adoptando una arquitectura de **Tokenización** con enmascaramiento seguro (*masking*).

### 2. Archivos Objetivo y Código Base
* **Dominio de Órdenes**: [`TacoOrder.java`](../tacocloud/tacocloud-domain-mongodb/src/main/java/tacos/TacoOrder.java)
* **Dominio de Pagos**: [`PaymentMethod.java`](../tacocloud/tacocloud-domain-mongodb/src/main/java/tacos/PaymentMethod.java)
* **Servicio de Migración y Purga**: [`PaymentDataMigrationService.java`](../tacocloud/tacocloud-data-mongodb/src/main/java/tacos/data/PaymentDataMigrationService.java)
* **Pruebas de Sanitización**: [`PaymentMethodSecurityTest.java`](../tacocloud/tacocloud-data-mongodb/src/test/java/tacos/data/PaymentMethodSecurityTest.java)

### 3. Cambios Realizados y Arquitectura

#### El Código Anterior (Violación Grave de PCI-DSS):
```java
// CÓDIGO ANTERIOR:
public class TacoOrder {
  private String ccNumber;     // Número de tarjeta en texto plano
  private String ccExpiration;
  private String ccCVV;        // El CVV nunca puede guardarse
}
```

#### Solución Implementada:
1. **Eliminación Total en el Dominio**: Se borraron los campos `ccNumber`, `ccExpiration` y `ccCVV` de `TacoOrder`, DTOs de orden y del evento de mensajería `OrderEvent`.
2. **Modelo Tokenizado `PaymentMethod`**: Almacena únicamente tokens de pasarela y metadatos no sensibles de consulta:
   - `paymentToken`: Token criptográfico generado por la pasarela de pago (ej. `tok_visa_4111`).
   - `brand`: Franquicia de la tarjeta (`VISA`, `MASTERCARD`, `AMEX`).
   - `last4`: Únicamente los últimos cuatro dígitos de la tarjeta (ej. `"4111"`).
3. **Servicio Automático de Purga (`PaymentDataMigrationService`)**: Se ejecuta al arranque en `DevelopmentConfig` ejecutando `$unset` en MongoDB para limpiar cualquier registro legado que contuviera PAN o CVV.

```java
// CÓDIGO CORREGIDO:
public class TacoOrder {
  private String paymentMethodId; // Referencia al método tokenizado
  private String paymentToken;    // Token de la pasarela (no es el PAN)
  private String brand;           // ej. "VISA"
  private String last4;           // ej. "4111"
  // ¡Cero campos de CVV o PAN en el modelo!
}
```

### 4. Contratos y Especificación HTTP

| Parámetro | Detalle |
| :--- | :--- |
| **Creación de Orden** | Se envía únicamente `paymentMethodId: "pm-runtime-test"` o `paymentToken` |
| **Respuesta HTTP** | Muestra exclusivamente `brand` y `last4` (ej. `brand: "VISA"`, `last4: "4111"`) |
| **Evento de Cocina** | `OrderEvent` no contiene ningún campo financiero ni identificador de cobro |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Creación de Pedido con Método Tokenizado (201 Created)
* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/orders`
* **Headers**:
  - `Content-Type: application/json`
  - `Authorization: Basic aGFidW1hOnBhc3N3b3Jk`
* **Body**:
  ```json
  {
    "deliveryName": "Craig Walls",
    "deliveryStreet": "123 North Street",
    "deliveryCity": "Cross Roads",
    "deliveryState": "TX",
    "deliveryZip": "76227",
    "paymentMethodId": "pm-test-token-4111",
    "items": [
      {
        "taco": {
          "name": "PCI Compliant Taco",
          "ingredientIds": ["FLTO", "CARN", "CHED"]
        },
        "quantity": 1
      }
    ]
  }
  ```
* **Comando cURL**:
  ```bash
  curl -i -X POST http://localhost:8080/api/v1/orders \
    -u habuma:password \
    -H "Content-Type: application/json" \
    -d "{\"deliveryName\":\"Craig Walls\",\"deliveryStreet\":\"123 North Street\",\"deliveryCity\":\"Cross Roads\",\"deliveryState\":\"TX\",\"deliveryZip\":\"76227\",\"paymentMethodId\":\"pm-test-token-4111\",\"items\":[{\"taco\":{\"name\":\"PCI Compliant Taco\",\"ingredientIds\":[\"FLTO\",\"CARN\",\"CHED\"]},\"quantity\":1}]}"
  ```
* **Respuesta Esperada**: `201 Created`. En el JSON de respuesta se aprecia:
  ```json
  {
    "id": "ORD-...",
    "deliveryName": "Craig Walls",
    "brand": "VISA",
    "last4": "4111"
  }
  ```
  Sin presencia de números de tarjeta ni códigos de seguridad.

### 6. Espacio para Evidencias de Ejecución (Postman)
* **Evidencia Respuesta Segura con Datos Tokenizados (201 Created)**:
  ![TC-12 Tokenización 201 Created](images/lab2/tc12_order_tokenized_201.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿Cifrar PAN y CVV en Mongo arregla el problema? Explique por qué CVV sigue siendo un no-go.*

**Defensa Técnica**:
> **No, cifrar el PAN y el CVV no resuelve el problema y continúa siendo una violación inadmisible ante la normativa PCI-DSS**.
> 
> 1. **Prohibición Absoluta del CVV (Requisito 3.2 de PCI-DSS)**: El estándar internacional prohíbe categóricamente almacenar datos de autenticación sensible (*Sensitive Authentication Data - SAD*) **después de la autorización**, sin importar si están cifrados, hasheados o guardados en bóvedas de seguridad. El CVV está diseñado para validar la posesión física de la tarjeta únicamente durante la transacción en vivo; persistirlo tras autorizar es ilegal para la industria financiera.
> 2. **Cifrado vs. Tokenización**: Cifrar el PAN en la base de datos propia mantiene al servidor y a la base de datos dentro del alcance estricto de auditoría PCI-DSS (*in-scope*), exigiendo costosas certificaciones anuales de gestión y rotación de llaves criptográficas. 
> 3. **La Solución Real (Tokenización)**: Delegar la recepción del PAN/CVV directamente al iframe o SDK de una pasarela certificada (como Stripe o MercadoPago) y almacenar únicamente un token opaco y los últimos cuatro dígitos (`last4`) libera la infraestructura propia del alcance de PCI-DSS y elimina el riesgo de que una filtración comprometa los fondos de los clientes.

### 8. Criterios de Aceptación Cumplidos
- [x] Búsquedas de texto sobre `ccCVV` y `ccNumber` en el código y logs confirman su ausencia total.
- [x] Las órdenes se crean exclusivamente asociando métodos de pago tokenizados.
- [x] La respuesta de la API expone únicamente la franquicia (`brand`) y los últimos 4 dígitos (`last4`).
- [x] Los eventos de cocina enviados al broker contienen cero información financiera.
- [x] El servicio de migración purga cualquier residuo legado de tarjetas en MongoDB.

---

[⬅️ Laboratorio 1 — Cazar Operaciones Fantasma](laboratorio-1-cazar-operaciones-fantasma.md) | [Volver al Índice General](README.md) | [Siguiente: Laboratorio 3 — Motor de Negocio ➡️](laboratorio-3-motor-de-negocio.md)
