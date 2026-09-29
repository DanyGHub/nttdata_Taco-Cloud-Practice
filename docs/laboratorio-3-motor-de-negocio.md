# Laboratorio 3: Motor de negocio (TC-13 a TC-18)

[⬅️ Laboratorio 2 — Contratos y Seguridad](laboratorio-2-contratos-y-seguridad.md) | [Volver al Índice General](README.md) | [Siguiente: Laboratorio 4 — Funciones que sí dan ganas de usar ➡️](laboratorio-4-funciones-que-si-dan-ganas-de-usar.md)

---

## Resumen Ejecutivo del Laboratorio

El tercer laboratorio de Taco Cloud dota a la plataforma de su **núcleo de dominio comercial y gastronómico**. 

Tras asegurar la reactividad y la protección de datos en los laboratorios previos, este bloque convierte a Taco Cloud en una aplicación capaz de **calcular, validar, reservar y decidir de forma autónoma**:
1. **Catálogo Comercial con Control de Stock**: Los ingredientes dejan de ser simples nombres de texto y se convierten en recursos transaccionales con precio (`unitPrice`), control de inventario (`stockOnHand`), umbral de reposición y concurrencia optimista (`@Version`).
2. **Cálculo Financiero Centralizado en el Servidor**: Erradicación del fraude por manipulación de precios; el servidor asume el control absoluto de subtotales, cantidades y totales con precisión monetaria (`BigDecimal`).
3. **Motor de Cupones con Estrategias de Descuento**: Soporte para beneficios porcentuales y de monto fijo, validación de compras mínimas y ventanas de vigencia temporal verificables mediante reloj desacoplado (`Clock`).
4. **Gestión Atómica de Inventario y Compensación**: Prevención de sobreventa (*vender aire*) mediante reservas atómicas condicionales y mecanismos de liberación automática ante cancelaciones o fallos.
5. **Clasificación Alimentaria Automática**: Derivación rigurosa de dietas (`VEGAN`, `GLUTEN_FREE`), unión matemática de alérgenos y cálculo de nivel de picante a nivel de taco.
6. **Física del Taco ("Taco Physics")**: Motor de validación extensible basado en el patrón *Specification / Strategy* para asegurar la viabilidad estructural y gastronómica de los tacos antes de ser ordenados.

---

## Mapa de Retos y Matriz de Contratos

| Reto | Nombre | Módulo Maven | Endpoint / Componente | Método HTTP | Códigos HTTP |
| :--- | :--- | :--- | :--- | :---: | :---: |
| **TC-13** | Catálogo con precio, disponibilidad y stock | `tacocloud-api` | `/api/v1/admin/ingredients/{id}/catalog`, `.../stock-adjustments` | `PATCH`, `POST` | `200`, `400`, `404`, `409` |
| **TC-14** | Calcular precios y cantidades en servidor | `tacocloud-api` | `POST /api/v1/orders` (con items y cantidades) | `POST` | `201`, `400`, `422` |
| **TC-15** | Motor de cupones con reglas y expiración | `tacocloud-api` | `POST /api/v1/coupons/validate` (o quote) | `POST` | `200`, `400`, `422` |
| **TC-16** | Reservar y liberar inventario sin vender aire | `tacocloud-api` | `InventoryService` (reserva atómica y compensación) | *Internal Pipeline* | `201`, `409`, `422` |
| **TC-17** | Etiquetas dietarias, alérgenos y picante | `tacocloud-api` | `/api/v1/tacos/{id}/classification` | `GET` | `200`, `404` |
| **TC-18** | Taco Physics: reglas componibles de diseño | `tacocloud-api` | `POST /api/v1/tacos/validate` | `POST` | `200`, `400`, `422` |

---

## TC-13 — Catálogo con precio, disponibilidad y stock

* **Nivel**: Intermedio | **Puntos**: 8 | **Laboratorio**: 3 | **Dependencias**: TC-08, TC-09

### 1. Objetivo del Reto y Resultado Funcional
Transformar la entidad `Ingredient` en un recurso comercialmente vendible y auditable. Cada ingrediente debe incluir precio unitario en `BigDecimal`, bandera de disponibilidad comercial (`available`), stock físico actual en almacén (`stockOnHand`), umbral de reorden (`reorderLevel`) y control de versiones optimista (`@Version`). Asimismo, se debe permitir únicamente a administradores (`ROLE_ADMIN`) ajustar precios y existencias.

### 2. Archivos Objetivo y Código Base
* **Dominio**: [`Ingredient.java`](../tacocloud/tacocloud-domain-mongodb/src/main/java/tacos/Ingredient.java)
* **Controlador Admin**: [`AdminIngredientController.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/AdminIngredientController.java)
* **Pruebas**: [`AdminIngredientControllerTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/web/api/AdminIngredientControllerTest.java)

### 3. Cambios Realizados y Arquitectura

#### Comparativa de Implementación:
```java
// CÓDIGO ORIGINAL:
public class Ingredient {
  private final String id;
  private final String name;
  private final Type type;
  // Sin precio, sin inventario, sin disponibilidad y sin control de concurrencia
}
```

```java
// CÓDIGO IMPLEMENTADO:
@Data
@Document(collection = "ingredients")
public class Ingredient {
  @Id
  private String id;
  private String name;
  private Type type;
  
  private BigDecimal unitPrice;  // Precisión monetaria sin punto flotante
  private Boolean available;     // Pausa comercial sin eliminar stock
  private Integer stockOnHand;   // Existencias físicas reales
  private Integer reorderLevel;  // Nivel mínimo para alertas de compra
  
  @Version
  private Long version;          // Bloqueo optimista contra escrituras concurrentes
}
```

En [`AdminIngredientController.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/AdminIngredientController.java), se crearon endpoints administrativos protegidos para:
- `PATCH /api/v1/admin/ingredients/{id}/catalog`: Actualiza precio y disponibilidad validando la versión (`OptimisticLockingFailureException` $\rightarrow$ `409 Conflict`).
- `POST /api/v1/admin/ingredients/{id}/stock-adjustments`: Aplica incrementos o decrementos de stock rechazando cualquier saldo negativo (`422 Unprocessable Entity`).

### 4. Contratos y Especificación HTTP

| Operación | Método / URL | Headers | Payload de Entrada | Respuestas |
| :--- | :--- | :--- | :--- | :--- |
| **Ajustar Catálogo** | `PATCH /api/v1/admin/ingredients/{id}/catalog` | `Authorization: Basic admin:admin` | `{"unitPrice": 1.75, "available": true, "version": 0}` | `200 OK`, `409 Conflict` |
| **Ajustar Stock** | `POST /api/v1/admin/ingredients/{id}/stock-adjustments` | `Authorization: Basic admin:admin` | `{"amountDelta": 50, "reason": "Restock"}` | `200 OK`, `422 Unprocessable` |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Actualización de Precio por Administrador (200 OK)
* **Método**: `PATCH`
* **URL**: `http://localhost:8080/api/v1/admin/ingredients/CARN/catalog`
* **Headers**:
  - `Content-Type: application/json`
  - `Authorization: Basic YWRtaW46YWRtaW4=` (`admin:admin`)
* **Body**:
  ```json
  {
    "unitPrice": 1.95,
    "available": true,
    "version": 0
  }
  ```
* **Comando cURL**:
  ```bash
  curl -i -X PATCH http://localhost:8080/api/v1/admin/ingredients/CARN/catalog \
    -u admin:admin \
    -H "Content-Type: application/json" \
    -d "{\"unitPrice\":1.95,\"available\":true,\"version\":0}"
  ```
* **Respuesta Esperada**: `200 OK` reflejando el nuevo precio `$1.95` e incrementando la versión a `1`.

### 6. Espacio para Evidencias de Ejecución (Postman)

* **Evidencia Ajuste Exitoso de Catálogo (200 OK)**:
  ![TC-13 Ajuste Catálogo](images/lab3/tc13_admin_catalog_200.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿`available=false` y `stockOnHand > 0` es una contradicción o una pausa comercial válida?*

**Defensa Técnica**:
> **Es una pausa comercial completamente válida y una práctica estándar en operaciones gastronómicas**.
> 
> Existencias físicas (`stockOnHand > 0`) no implican automáticamente aptitud para la venta:
> 1. **Control de Calidad o Maduración**: Un ingrediente (ej. aguacate o carne marinada) puede estar en almacén pero no haber alcanzado el punto de maduración o estar en cuarentena sanitaria preventiva.
> 2. **Pausa Operativa de Cocina**: Si la plancha de cocción está saturada o se aproxima el cierre del turno, el administrador puede pausar la venta de ciertos ingredientes de alta demanda sin necesidad de mentir en el inventario colocando existencias en cero.
> 
> Separar `available` (decisión de negocio) de `stockOnHand` (realidad física en almacén) evita corromper las auditorías de almacén al suspender temporalmente un ítem del menú.

### 8. Criterios de Aceptación Cumplidos
- [x] El catálogo expone precio monetario en `BigDecimal` y disponibilidad.
- [x] Ajustes que inducirían stock negativo son rechazados con error semántico.
- [x] Conflictos de concurrencia optimista (`@Version`) se traducen a `409 Conflict`.
- [x] Las operaciones de modificación requieren estrictamente credenciales `ROLE_ADMIN`.

---

## TC-14 — Calcular precios y cantidades del lado servidor

* **Nivel**: Avanzado | **Puntos**: 13 | **Laboratorio**: 3 | **Dependencias**: TC-08, TC-13

### 1. Objetivo del Reto y Resultado Funcional
Eliminar de raíz la vulnerabilidad de alteración de precios por parte del cliente. El servidor asume el cálculo íntegro de subtotales, multiplicadores de cantidad (`quantity`) y total de la orden a partir de la lista de precios oficial de los ingredientes en MongoDB, guardando un snapshot inmutable de los precios al momento de la compra para auditoría histórica.

### 2. Archivos Objetivo y Código Base
* **Servicio Financiero**: [`PricingService.java`](../tacocloud/tacocloud-api/src/main/java/tacos/pricing/PricingService.java)
* **Líneas de Orden**: [`OrderItem.java`](../tacocloud/tacocloud-domain-mongodb/src/main/java/tacos/OrderItem.java), [`OrderItemRequest.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/dto/OrderItemRequest.java)
* **Pruebas**: [`PricingServiceTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/pricing/PricingServiceTest.java)

### 3. Cambios Realizados y Arquitectura

#### Comparativa de Implementación:
```java
// CÓDIGO ORIGINAL:
public class TacoOrder {
  // Confia el total enviado en el JSON por el cliente:
  private Double totalPrice; 
}
```

```java
// CÓDIGO IMPLEMENTADO:
@Service
public class PricingService {
  public static final RoundingMode DEFAULT_ROUNDING_MODE = RoundingMode.HALF_UP;
  public static final String DEFAULT_CURRENCY = "USD";

  public Mono<TacoOrder> calculateAndApplyPricing(TacoOrder order) {
    // 1. Consulta los precios unitarios oficiales de cada ingrediente en MongoDB
    return ingredientRepo.findAllById(extractIngredientIds(order))
        .collectMap(Ingredient::getId, Ingredient::getUnitPrice)
        .map(priceMap -> {
          BigDecimal subtotal = BigDecimal.ZERO;
          for (OrderItem item : order.getItems()) {
            // Precio del taco = Suma de sus ingredientes
            BigDecimal tacoPrice = item.getTaco().getIngredients().stream()
                .map(ing -> priceMap.getOrDefault(ing.getId(), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            item.setUnitPriceAtPurchase(tacoPrice.setScale(2, DEFAULT_ROUNDING_MODE));
            BigDecimal lineTotal = tacoPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
            item.setLineTotal(lineTotal.setScale(2, DEFAULT_ROUNDING_MODE));
            subtotal = subtotal.add(lineTotal);
          }
          order.setSubtotal(subtotal);
          order.setTotal(subtotal.subtract(order.getDiscountAmount()));
          order.setCurrency(DEFAULT_CURRENCY);
          return order;
        });
  }
}
```

### 4. Contratos y Especificación HTTP

| Parámetro | Detalle |
| :--- | :--- |
| **Método / URL** | `POST http://localhost:8080/api/v1/orders` |
| **Payload del Cliente** | Envía únicamente `ingredientIds` y `quantity` (ej. 2 tacos) |
| **Atributos Ignorados** | Cualquier campo `total`, `subtotal` o `price` enviado en el JSON es descartado |
| **Cálculo en Servidor** | $\text{LineTotal} = \text{TacoPrice} \times \text{Quantity}$; $\text{Total} = \sum \text{LineTotal} - \text{Discount}$ |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Creación de Orden con Cantidades y Validación de Total (201 Created)
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
          "name": "Double Beef Taco",
          "ingredientIds": ["FLTO", "GRBF", "CHED"]
        },
        "quantity": 2
      }
    ]
  }
  ```
* **Comando cURL**:
  ```bash
  curl -i -X POST http://localhost:8080/api/v1/orders \
    -u habuma:password \
    -H "Content-Type: application/json" \
    -d "{\"deliveryName\":\"Craig Walls\",\"deliveryStreet\":\"123 North Street\",\"deliveryCity\":\"Cross Roads\",\"deliveryState\":\"TX\",\"deliveryZip\":\"76227\",\"paymentMethodId\":\"pm-test-token-4111\",\"items\":[{\"taco\":{\"name\":\"Double Beef Taco\",\"ingredientIds\":[\"FLTO\",\"GRBF\",\"CHED\"]},\"quantity\":2}]}"
  ```
* **Respuesta Esperada**: `201 Created`. Si Flour Tortilla cuesta `$0.79`, Ground Beef `$1.50` y Cheddar `$0.65` ($\text{Taco} = \$2.94$), el total para `quantity: 2` se calcula exactamente en `$5.88`.

### 6. Espacio para Evidencias de Ejecución (Postman)
* **Evidencia Cálculo de Precios del Lado Servidor (201 Created)**:
  ![TC-14 Pricing Server](images/lab3/tc14_pricing_calculation_201.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿El precio pertenece al taco, al ingrediente, a la orden o a los tres en momentos distintos?*

**Defensa Técnica**:
> **El precio pertenece a los tres elementos en fases temporales y conceptuales distintas del ciclo de vida**:
> 1. **Al Ingrediente en el Catálogo (Dato Maestro / Oferta)**: Es la tarifa unitaria viva que define la empresa en base de datos (`Ingredient.unitPrice`). Puede fluctuar por inflación o costo de insumos.
> 2. **Al Taco en el Momento del Diseño (Composición Dinámica)**: Es el valor derivado que resulta de sumar los precios de los ingredientes que el cliente seleccionó en la interfaz.
> 3. **A la Línea de la Orden al Momento de la Compra (Snapshot Financiero Inmutable)**: Una vez que el cliente pulsa "Pagar", el precio se "congela" en `OrderItem.unitPriceAtPurchase`. Si al día siguiente el precio de la carne sube en el catálogo, la orden histórica **no puede mutar retroactivamente**, preservando la integridad contable y legal de la transacción.

### 8. Criterios de Aceptación Cumplidos
- [x] Dos unidades producen exactamente el doble del subtotal unitario de la línea.
- [x] Precios manipulados enviados por el cliente son ignorados por el servidor.
- [x] Se almacena el snapshot histórico de precios para auditorías futuras.
- [x] Cantidades menores a 1 o superiores al límite permitido arrojan error de validación.

---

## TC-15 — Motor de cupones con reglas y fecha de expiración

* **Nivel**: Intermedio | **Puntos**: 8 | **Laboratorio**: 3 | **Dependencias**: TC-14

### 1. Objetivo del Reto y Resultado Funcional
Evolucionar la gestión de descuentos hacia un motor de promociones desacoplado, configurable y comprobable de forma determinista. El motor debe soportar descuentos de monto fijo (`FIXED`) y porcentuales (`PERCENTAGE`) con tope máximo de beneficio (`maxDiscountAmount`), validando el monto mínimo de compra y su vigencia temporal mediante un reloj inyectable (`Clock`).

### 2. Archivos Objetivo y Código Base
* **Motor de Cupones**: [`CouponService.java`](../tacocloud/tacocloud-api/src/main/java/tacos/pricing/CouponService.java)
* **Propiedades Externas**: [`CouponProperties.java`](../tacocloud/tacocloud-api/src/main/java/tacos/pricing/CouponProperties.java)
* **Configuración del Reloj**: [`ClockConfig.java`](../tacocloud/tacocloud-api/src/main/java/tacos/pricing/ClockConfig.java)
* **Pruebas Automatizadas**: [`CouponServiceTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/pricing/CouponServiceTest.java)

### 3. Cambios Realizados y Arquitectura

#### Comparativa de Implementación:
```java
// CÓDIGO ORIGINAL:
// Mapa en memoria cableado en un controlador estático:
Map<String, Integer> discountCodes = Map.of("TACO10", 10); // Sin fechas, sin topes
```

```java
// CÓDIGO IMPLEMENTADO:
@Service
public class CouponService {
  private final CouponProperties properties;
  private final Clock clock; // Inyección de tiempo para pruebas deterministas

  public CouponValidationResult validateAndCalculate(String rawCode, BigDecimal subtotal) {
    String code = normalizeCode(rawCode);
    CouponRule rule = properties.findCoupon(code);
    if (rule == null) {
      return CouponValidationResult.invalid(CouponStatus.INVALID_CODE, "Invalid coupon code", code, subtotal);
    }
    
    Instant now = clock.instant();
    if (now.isBefore(rule.getValidFrom())) return CouponValidationResult.notStarted(...);
    if (now.isAfter(rule.getValidTo())) return CouponValidationResult.expired(...);
    if (subtotal.compareTo(rule.getMinOrderAmount()) < 0) return CouponValidationResult.minNotMet(...);

    BigDecimal discount = rule.getType() == DiscountType.PERCENTAGE
        ? subtotal.multiply(rule.getValue().divide(BigDecimal.valueOf(100)))
        : rule.getValue();

    if (rule.getMaxDiscountAmount() != null && discount.compareTo(rule.getMaxDiscountAmount()) > 0) {
      discount = rule.getMaxDiscountAmount(); // Aplica tope de seguridad
    }
    return CouponValidationResult.valid(code, discount, subtotal.subtract(discount));
  }
}
```

### 4. Cupones Configurados en `application.yml`

| Código | Tipo | Beneficio | Mínimo de Compra | Límite Máximo | Vigencia |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **`TACO10`** | `PERCENTAGE` | 10% | $10.00 | $5.00 | 2026-01-01 a 2026-12-31 (Activo) |
| **`SAVE5`** | `FIXED` | $5.00 | $15.00 | N/A | 2026-01-01 a 2026-12-31 (Activo) |
| **`EXPIRED2020`** | `PERCENTAGE` | 20% | $0.00 | N/A | Expirado en 2020 |
| **`FUTURE2030`** | `FIXED` | $10.00 | $20.00 | N/A | Inicia en 2030 |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Aplicación Exitosa de Cupón TACO10 (200 OK)
* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/coupons/validate`
* **Headers**: `Content-Type: application/json`
* **Body**:
  ```json
  {
    "code": "TACO10",
    "subtotal": 20.00
  }
  ```
* **Comando cURL**:
  ```bash
  curl -i -X POST http://localhost:8080/api/v1/coupons/validate \
    -H "Content-Type: application/json" \
    -d "{\"code\":\"TACO10\",\"subtotal\":20.00}"
  ```
* **Respuesta Esperada**: `200 OK`. Descuento de `$2.00` aplicado sobre el subtotal de `$20.00`, con un total resultante de `$18.00`.

#### Caso 2: Rechazo de Cupón Expirado (422 Unprocessable Entity)
* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/coupons/validate`
* **Body**: `{"code": "EXPIRED2020", "subtotal": 30.00}`
* **Respuesta Esperada**: `422 Unprocessable Entity` ("Coupon code has expired").

### 6. Espacio para Evidencias de Ejecución (Postman)
* **Evidencia Cupón Válido Aplicado (200 OK)**:
  ![TC-15 Cupón Válido](images/lab3/tc15_coupon_valid_200.png)

* **Evidencia Cupón Expirado Rechazado (422 Unprocessable)**:
  ![TC-15 Cupón Expirado](images/lab3/tc15_coupon_expired_422.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿Conviene responder “código inexistente” distinto de “expirado”, o eso ayuda a enumerar promociones?*

**Defensa Técnica**:
> Desde la perspectiva de **seguridad defensiva contra enumeración de promociones**, responder con errores hiperespecíficos como *"Cupón no iniciado"* o *"Cupón expirado"* permite a usuarios maliciosos inferir la convención de nombres de la empresa (ej. si prueban `PROMO2025` y dice "expirado", sabrán que `PROMO2026` probablemente existe).
> 
> La buena práctica recomendada para APIs públicas es unificar los mensajes externos bajo un genérico seguro: *"El cupón no es válido para esta compra"*. Sin embargo, **internamente el motor debe registrar en logs auditables y métricas (TC-32)** la causa raíz exacta (`EXPIRED`, `NOT_STARTED`, `MIN_NOT_MET`) para que el equipo de soporte y marketing pueda depurar incidentes sin comprometer la seguridad.

### 8. Criterios de Aceptación Cumplidos
- [x] Cupones vigentes calculan el descuento exacto con precisión decimal.
- [x] Códigos expirados o con mínimo de compra no alcanzado son rechazados de forma determinista.
- [x] El límite `maxDiscountAmount` restringe porcentajes altos en compras voluminosas.
- [x] La inyección de `Clock` permite probar escenarios temporales en cualquier fecha.

---

## TC-16 — Reservar y liberar inventario sin vender aire

* **Nivel**: Avanzado | **Puntos**: 13 | **Laboratorio**: 3 | **Dependencias**: TC-13, TC-14

### 1. Objetivo del Reto y Resultado Funcional
Resolver la condición de carrera por sobreventa (*vender aire*). La creación de órdenes debe descontar el inventario de forma **atómica y condicional** ($\text{stockOnHand} \ge \text{solicitado}$). Si un ingrediente se queda sin stock en un pedido multi-ingrediente, el sistema debe ejecutar una **compensación automática**, restituyendo lo reservado previamente y rechazando la orden sin dejar jamás existencias negativas en almacén.

### 2. Archivos Objetivo y Código Base
* **Interfaz de Inventario**: [`InventoryService.java`](../tacocloud/tacocloud-api/src/main/java/tacos/inventory/InventoryService.java)
* **Implementación Atómica**: [`InventoryServiceImpl.java`](../tacocloud/tacocloud-api/src/main/java/tacos/inventory/InventoryServiceImpl.java)
* **Pruebas Concurrentes**: [`InventoryServiceConcurrencyTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/inventory/InventoryServiceConcurrencyTest.java)

### 3. Cambios Realizados y Arquitectura

#### Comparativa de Implementación:
```java
// CÓDIGO ORIGINAL:
// Patrón vulnerable Read-Modify-Write:
Ingredient ing = repo.findById(id).block();
ing.setStock(ing.getStock() - qty); // Dos hilos leen el mismo stock y sobrevenden
repo.save(ing).block();
```

```java
// CÓDIGO IMPLEMENTADO:
@Service
public class InventoryServiceImpl implements InventoryService {
  private final ReactiveMongoTemplate mongoTemplate;

  @Override
  public Mono<StockReservation> reserve(TacoOrder order) {
    // 1. Descuenta atómicamente en MongoDB con filtro condicional: { id: ingId, stockOnHand: { $gte: qty } }
    return Flux.fromIterable(order.getAggregatedIngredients())
        .concatMap(req -> {
          Query query = Query.query(Criteria.where("id").is(req.getIngredientId())
              .and("stockOnHand").gte(req.getQuantity()));
          Update update = new Update().inc("stockOnHand", -req.getQuantity());

          return mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), Ingredient.class)
              .switchIfEmpty(Mono.error(new InsufficientStockException("Insufficient stock for: " + req.getIngredientId())));
        })
        .collectList()
        // 2. Patrón SAGA de Compensación: Si un ingrediente posterior falla, libera lo ya descontado
        .onErrorResume(InsufficientStockException.class, ex -> 
            compensatePartialReservations(order).then(Mono.error(ex))
        )
        .map(savedList -> buildReservation(order));
  }
}
```

### 4. Contratos y Especificación HTTP

| Evento | Comportamiento | Resultado en MongoDB |
| :--- | :--- | :--- |
| **Reserva Exitosa** | Stock suficiente para todos los ingredientes | Se descuentan las existencias y la orden procede a crearse (`201 Created`) |
| **Falta de Stock** | Al menos un ingrediente no tiene stock suficiente | Se aborta la orden (`409 Conflict` / `400`), se compensan los ingredientes ya tocados y el stock final queda intacto |
| **Cancelación (TC-05)** | Cliente o admin cancela la orden | `releaseForOrder(orderId)` incrementa atómicamente el stock devuelto |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Intentar Comprar Ingrediente Agotado (422 Unprocessable Entity)
* Si el stock de Carnitas (`CARN`) es de 2 unidades y se solicita una orden con 500 tacos de Carnitas:
* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/orders`
* **Headers**:
  - `Content-Type: application/json`
  - `Authorization: Basic aGFidW1hOnBhc3N3b3Jk`
* **Body**:
  ```json
  {
    "deliveryName": "Customer",
    "deliveryStreet": "123 St",
    "deliveryCity": "City",
    "deliveryState": "AGS",
    "deliveryZip": "20000",
    "paymentMethodId": "pm-token-1",
    "items": [
      {
        "taco": {"name": "Carnitas Feast", "ingredientIds": ["FLTO", "CARN"]},
        "quantity": 500
      }
    ]
  }
  ```
* **Respuesta Esperada**: `409 Conflict Entity` ("Insufficient stock for ingredient CARN"). En base de datos, el stock de tortillas `FLTO` se restituye limpiamente sin pérdidas.

### 6. Espacio para Evidencias de Ejecución (Postman)
* **Evidencia Rechazo por Stock Insuficiente (409 Conflict)**:
  ![TC-16 Sin Stock](images/lab3/tc16_insufficient_stock_409.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿Qué garantía ofrece una actualización atómica por ingrediente y cuál falta para una orden con diez ingredientes?*

**Defensa Técnica**:
> - **Garantía que ofrece**: La actualización atómica a nivel de documento (`findAndModify` con `$inc` y `$gte`) garantiza que **ningún ingrediente individual sufrirá sobreventa ni llegará a saldo negativo**, resolviendo de raíz condiciones de carrera a nivel de ítem.
> - **Garantía que falta para una orden multi-ingrediente**: MongoDB por defecto no proporciona aislamiento transaccional multi-documento a través de colecciones sin transacciones distribuidas multi-documento (las cuales requieren replica sets activos). Si una orden requiere 10 ingredientes y los primeros 9 se descuentan con éxito pero el décimo falla por falta de stock, **la orden completa queda en un estado inconsistente a medio reservar**.
> 
> Para subsanar esta carencia sin forzar un replica set en entornos locales, la solución implementa el **patrón SAGA de Compensación reactiva (`onErrorResume`)**: si cualquier ítem falla, el servicio recorre en reversa los ingredientes previamente modificados y re-incrementa sus saldos con `$inc: +qty`, restaurando la consistencia eventual perfecta del inventario.

### 8. Criterios de Aceptación Cumplidos
- [x] Dos solicitudes concurrentes no pueden comprar más existencias que las disponibles en almacén.
- [x] Un fallo a mitad de una orden compuesta libera inmediatamente los ingredientes previos.
- [x] Cancelar una orden restituye el stock en almacén exactamente una vez.
- [x] El stock de ningún ingrediente puede alcanzar valores negativos bajo ninguna circunstancia.

---

## TC-17 — Etiquetas dietarias, alérgenos y nivel de picante

* **Nivel**: Intermedio | **Puntos**: 8 | **Laboratorio**: 3 | **Dependencias**: TC-13

### 1. Objetivo del Reto y Resultado Funcional
Permitir a los clientes consultar con total certidumbre si un taco personalizado respeta sus restricciones médicas o alimentarias. El sistema debe clasificar dinámicamente cada taco a partir de sus ingredientes, computando etiquetas dietarias (`VEGAN`, `VEGETARIAN`, `GLUTEN_FREE`), calculando la unión de alérgenos (`Allergen`) y deduciendo el nivel de picante resultante (`SpiceLevel`).

### 2. Archivos Objetivo y Código Base
* **Enums de Clasificación**: [`DietaryTag.java`](../tacocloud/tacocloud-domain-mongodb/src/main/java/tacos/classification/DietaryTag.java), [`Allergen.java`](../tacocloud/tacocloud-domain-mongodb/src/main/java/tacos/classification/Allergen.java), [`SpiceLevel.java`](../tacocloud/tacocloud-domain-mongodb/src/main/java/tacos/classification/SpiceLevel.java)
* **Servicio de Clasificación**: [`TacoClassificationService.java`](../tacocloud/tacocloud-api/src/main/java/tacos/classification/TacoClassificationService.java)
* **Pruebas**: [`TacoClassificationServiceTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/classification/TacoClassificationServiceTest.java)

### 3. Cambios Realizados y Arquitectura

#### Comparativa de Implementación:
```java
// CÓDIGO ORIGINAL:
public class Ingredient {
  // Sin información nutricional, alérgenos ni el nivel de picante
}
```

```java
// CÓDIGO IMPLEMENTADO:
@Service
public class TacoClassificationService {

  public TacoClassification classify(Taco taco) {
    List<Ingredient> ingredients = taco.getIngredients();

    // 1. Regla Dietaria: Una etiqueta "Libre de" o "Vegano" exige unanimidad absoluta
    Set<DietaryTag> tags = new HashSet<>(Arrays.asList(DietaryTag.values()));
    for (Ingredient ing : ingredients) {
      tags.retainAll(ing.getDietaryTags() != null ? ing.getDietaryTags() : Collections.emptySet());
    }

    // 2. Regla de Alérgenos: Es la unión matemática de todos los alérgenos presentes
    Set<Allergen> allergens = ingredients.stream()
        .filter(ing -> ing.getAllergens() != null)
        .flatMap(ing -> ing.getAllergens().stream())
        .collect(Collectors.toSet());

    // 3. Regla de Picante: Es el nivel máximo entre todos los ingredientes
    SpiceLevel maxSpice = ingredients.stream()
        .map(Ingredient::getSpiceLevel)
        .filter(Objects::nonNull)
        .max(Comparator.comparingInt(SpiceLevel::getScovilleRank))
        .orElse(SpiceLevel.NONE);

    return new TacoClassification(tags, allergens, maxSpice);
  }
}
```

### 4. Contratos y Especificación HTTP

| Campo Clasificado | Regla de Negocio del Sistema |
| :--- | :--- |
| **`VEGAN`** | `true` si y solo si el 100% de los ingredientes tienen la etiqueta `VEGAN`. |
| **`GLUTEN_FREE`** | `true` si ningún ingrediente aporta gluten (ej. tortilla de harina `FLTO` aporta `Allergen.GLUTEN`). |
| **`allergens`** | Colección JSON con la unión de todos los alérgenos (`DAIRY`, `GLUTEN`, `SOY`, etc.). |
| **`spiceLevel`** | `NONE` $\rightarrow$ `MILD` $\rightarrow$ `MEDIUM` $\rightarrow$ `HOT` $\rightarrow$ `EXTRA_HOT` (Mayor intensidad detectada). |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Clasificación de Taco con Tortilla de Harina y Carne (200 OK)
* **Método**: `GET`
* **URL**: `http://localhost:8080/api/v1/tacos/TACO1/classification`
* **Respuesta Esperada**: `200 OK`.
  ```json
  {
    "dietaryTags": [],
    "allergens": ["GLUTEN", "DAIRY"],
    "spiceLevel": "MEDIUM"
  }
  ```
  *(Pierde la condición VEGAN por tener carne y GLUTEN_FREE por tener harina)*.

### 6. Espacio para Evidencias de Ejecución (Postman)
* **Evidencia Clasificación Nutricional y Alérgenos (200 OK)**:
  ![TC-17 Clasificación Taco](images/lab3/tc17_taco_classification_200.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿Qué reglas son datos del ingrediente y cuáles son políticas derivadas del producto?*

**Defensa Técnica**:
> - **Datos Intrínsecos del Ingrediente (Hechos de la Materia Prima)**: Pertenecen exclusivamente al ingrediente y son inmutables respecto a la receta. Por ejemplo, el queso Cheddar contiene lácteos (`Allergen.DAIRY`), la salsa Habanero tiene picante `HOT`, y la lechuga es `VEGAN`.
> - **Políticas Derivadas del Producto (Lógica de Composición del Taco)**: Pertenecen a las reglas de negocio de Taco Cloud y definen cómo se combinan esos datos:
>   - ¿Un taco es vegano si tiene carne? No (política de intersección estricta).
>   - ¿Los alérgenos se promedian? Jamás; por seguridad alimentaria se aplica la unión matemática estricta.
>   - ¿El picante se suma o se toma el pico máximo? Se aplica la política de nivel máximo perceptible.
> 
> *Disclaimer de responsabilidad*: Esta metadata informa la composición declarada de ingredientes, pero no sustituye en el mundo físico los controles de contaminación cruzada en las superficies de cocina.

### 8. Criterios de Aceptación Cumplidos
- [x] Un taco con un solo ingrediente cárnico o lácteo pierde automáticamente la clasificación `VEGAN`.
- [x] Los alérgenos del taco representan la unión exacta de los alérgenos de sus ingredientes.
- [x] El nivel de picante se calcula de manera determinista tomando el grado máximo de sus salsas.
- [x] El cliente no puede falsificar etiquetas dietarias enviándolas arbitrariamente en peticiones.

---

## TC-18 — Taco Physics: reglas componibles de diseño

* **Nivel**: Avanzado | **Puntos**: 13 | **Laboratorio**: 3 | **Dependencias**: TC-13, TC-17

### 1. Objetivo del Reto y Resultado Funcional
Evitar que combinaciones absurdas o estructuralmente inviables se almacenen en el sistema. Se debe construir el motor de validación de diseño **"Taco Physics"** utilizando el patrón *Specification / Strategy*, permitiendo evaluar reglas de envoltura, límites de ingredientes, balance de consistencia y restricciones gastronómicas configurables, devolviendo listas legibles de violaciones antes de cotizar o guardar el taco.

### 2. Archivos Objetivo y Código Base
* **Motor Validador**: [`TacoDesignValidator.java`](../tacocloud/tacocloud-api/src/main/java/tacos/physics/TacoDesignValidator.java)
* **Reglas de Diseño**: [`tacos.physics.rules`](../tacocloud/tacocloud-api/src/main/java/tacos/physics/rules/)
* **DTO de Violación**: [`DesignViolation.java`](../tacocloud/tacocloud-api/src/main/java/tacos/physics/DesignViolation.java)
* **Pruebas**: [`TacoDesignValidatorTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/physics/TacoDesignValidatorTest.java)

### 3. Cambios Realizados y Arquitectura

#### Comparativa de Implementación:
```java
// CÓDIGO ORIGINAL:
// Sin validaciones estructurales:
tacoRepo.save(taco); // Permite tacos irregulares [sin tortilla | 10 salsas | 10 ingredinetes | ...]
```

```java
// CÓDIGO IMPLEMENTADO:
@Service
public class TacoDesignValidator {
  private final List<TacoDesignRule> rules;

  @Autowired
  public TacoDesignValidator(List<TacoDesignRule> rules) {
    this.rules = rules; // Reglas inyectadas automáticamente por Spring
  }

  public TacoDesignValidationResult validate(TacoDesignContext context) {
    List<DesignViolation> violations = new ArrayList<>();
    for (TacoDesignRule rule : rules) {
      rule.evaluate(context, violations); // Cada regla se evalúa
    }
    return new TacoDesignValidationResult(violations.isEmpty(), violations);
  }
}
```

#### Reglas de Física Culinaria Implementadas:
1. **`SingleBaseRule`**: Todo taco debe tener exactamente una envoltura o base (`WRAP`). Un taco sin tortilla o con 3 tortillas es estructuralmente inválido.
2. **`IngredientCountRule`**: Debe contener entre 2 y 12 ingredientes totales.
3. **`NoDuplicateIngredientsRule`**: Prohíbe ingredientes repetidos en el mismo taco.
4. **`AvailableIngredientsRule`**: Rechaza ingredientes suspendidos del catálogo (`available: false`).
5. **`VeganMeatConflictRule`**: Un taco vegano no puede incluir proteínas cárnicas.
6. **`ExtremeHeatRequiresCoolingRule`**: Tacos con picante `EXTRA_HOT` requieren al menos un ingrediente refrescante (crema agria o queso).
7. **`SoggyTacoPhysicsRule`**: Limita el exceso de salsas líquidas para evitar que la tortilla se rompa.

### 4. Contratos y Especificación HTTP

| Parámetro | Detalle |
| :--- | :--- |
| **Método / URL** | `POST http://localhost:8080/api/v1/tacos/validate` (o `/api/tacos/validate`) |
| **Headers** | `Content-Type: application/json` |
| **Cuerpo (JSON)** | `{"name": "Invalid Taco", "ingredientIds": ["SLSA", "SRCR"]}` (Sin tortilla) |
| **Respuestas** | `200 OK` con `{ "valid": true, "violations": [] }` o `422 Unprocessable` / `{ "valid": false, "violations": [...] }` |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Validación de Taco Estructuralmente Inválido (Sin Tortilla)
* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/tacos/validate`
* **Headers**: `Content-Type: application/json`
* **Body**:
  ```json
  {
    "name": "Floating Ingredients",
    "ingredientIds": ["GRBF", "CHED", "SLSA"]
  }
  ```
* **Comando cURL**:
  ```bash
  curl -i -X POST http://localhost:8080/api/v1/tacos/validate \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Floating Ingredients\",\"ingredientIds\":[\"GRBF\",\"CHED\",\"SLSA\"]}"
  ```
* **Respuesta Esperada**:
  ```json
  {
    "valid": false,
    "violations": [
      {
        "code": "MISSING_BASE",
        "message": "Taco must have exactly one base ..."
      }
    ]
  }
  ```

#### Caso 2: Taco Válido y Físicamente Viable (200 OK)
* **Body**: `{"name": "Balanced Taco", "ingredientIds": ["FLTO", "CARN", "CHED", "SLSA"]}`
* **Respuesta Esperada**: `{ "valid": true, "violations": [] }`.

### 6. Espacio para Evidencias de Ejecución (Postman)
* **Evidencia Taco Inválido con Violaciones de Física (Postman)**:
  ![TC-18 Taco Physics Inválido](images/lab3/tc18_taco_physics_violations.png)

* **Evidencia Taco Válido Aprobado (200 OK)**:
  ![TC-18 Taco Physics Válido](images/lab3/tc18_taco_physics_valid_200.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿Es Specification, Chain of Responsibility o Strategy? Defienda el nombre por comportamiento, no por sticker arquitectónico.*

**Defensa Técnica**:
> El comportamiento implementado corresponde primariamente al **patrón Specification** (combinado con elementos de *Strategy*):
> 
> 1. **Por qué NO es Chain of Responsibility**: En la Cadena de Responsabilidad clásica, cada eslabón decide si procesa la petición o la delega al siguiente, deteniéndose usualmente en el primer fallo (*fail-fast*). En Taco Physics no queremos detenernos en el primer error; el cliente necesita recibir la **lista completa de todas las infracciones de su diseño** en una sola respuesta interactiva para poder corregirlas.
> 2. **Por qué es Specification**: Cada regla (`SingleBaseRule`, `IngredientCountRule`, etc.) encapsula un predicado de negocio atómico independiente que evalúa si un objeto del dominio (`TacoDesignContext`) cumple con un criterio determinado. 
> 3. **Extensibilidad sin Modificar el Núcleo (Open/Closed Principle)**: Spring inyecta automáticamente cualquier clase que implemente `TacoDesignRule` en la lista del validador. Si mañana se desea agregar una nueva regla (ej. *"Prohibir piña con salsa verde"*), solo se crea una clase nueva sin tocar una sola línea de `TacoDesignValidator`.

### 8. Criterios de Aceptación Cumplidos
- [x] Diseños válidos se aprueban con cero violaciones.
- [x] Diseños con múltiples defectos retornan todas las violaciones consolidadas en una sola respuesta.
- [x] Se pueden añadir nuevas reglas de física sin alterar la clase validadora central.
- [x] La validación física ocurre preventivamente antes de cotizar o reservar stock en almacén.

---

[⬅️ Laboratorio 2 — Contratos y Seguridad](laboratorio-2-contratos-y-seguridad.md) | [Volver al Índice General](README.md) | [Siguiente: Laboratorio 4 — Funciones que sí dan ganas de usar ➡️](laboratorio-4-funciones-que-si-dan-ganas-de-usar.md)
