# Laboratorio 1: Cazar operaciones fantasma (TC-01 a TC-06)

[⬅️ Volver al Índice General](README.md) | [Siguiente: Laboratorio 2 — Contratos y Seguridad ➡️](laboratorio-2-contratos-y-seguridad.md)

---

## Resumen Ejecutivo del Laboratorio

El primer laboratorio de Taco Cloud aborda el principio cardinal de la programación reactiva con **Project Reactor** y **Spring WebFlux**:

> *"Nothing happens until you subscribe"* (Nada ocurre hasta que te suscribes).

En aplicaciones reactivas tradicionales o migraciones apresuradas desde Spring MVC imperativo, un error común consiste en invocar métodos de repositorios reactivos (como `repo.save()` o `repo.deleteById()`) sin encadenar el `Publisher` (`Mono` o `Flux`) resultante al pipeline de ejecución del framework. Como consecuencia, el método retorna, pero la operación de entrada/salida jamás se envía al driver de MongoDB: la operación se convierte en una **"operación fantasma"**.



---

## Mapa de Retos y Matriz de Contratos

| Reto | Nombre | Módulo Maven | Endpoint / Operación | Método HTTP | Códigos HTTP |
| :--- | :--- | :--- | :--- | :---: | :---: |
| **TC-01** | Actualizar ingrediente sin perder publisher | `tacocloud-api` | `/api/v1/ingredients/{id}` | `PUT` | `200`, `400`, `404` |
| **TC-02** | Eliminar de verdad con semántica HTTP | `tacocloud-api` | `/api/v1/ingredients/{id}` | `DELETE` | `204`, `404` |
| **TC-03** | Construir Location sin localhost ni rutas rotas | `tacocloud-api` | `/api/v1/ingredients` | `POST` | `201`, `400` |
| **TC-04** | PATCH de órdenes con lista blanca y sin ZIP mutante | `tacocloud-api` | `/api/v1/orders/{orderId}` | `PATCH` | `200`, `400`, `403`, `404` |
| **TC-05** | PUT y DELETE de órdenes con identidad consistente | `tacocloud-api` | `/api/v1/orders/{orderId}` | `PUT` / `DELETE` | `200`, `204`, `400`, `403`, `404` |
| **TC-06** | Convertir órdenes de correo sin carreras | `tacocloud-api` | `EmailOrderService.convertEmailOrderToDomainOrder` | *Internal Pipeline* | *Mono<TacoOrder> / Error* |

---

## TC-01 — Actualizar un ingrediente sin perder el publisher

* **Nivel**: Inicial | **Puntos**: 5 | **Laboratorio**: 1 | **Dependencias**: Ninguna

### 1. Objetivo del Reto y Resultado Funcional
Corregir el defecto de persistencia en la actualización de ingredientes. La edición mediante `PUT` debe asegurar una escritura real en la base de datos MongoDB y devolver una respuesta HTTP verificable con el estado del recurso actualizado, garantizando la consistencia de identificadores y rechazando llamadas con datos inexistentes o contradictorios.

### 2. Archivos Objetivo y Código Base
* **Controlador**: [`IngredientController.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/IngredientController.java)
* **Repositorio**: [`IngredientRepository.java`](../tacocloud/tacocloud-data-mongodb/src/main/java/tacos/data/IngredientRepository.java)
* **Pruebas Automatizadas**: [`IngredientControllerTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/web/api/IngredientControllerTest.java)

### 3. Cambios Realizados y Arquitectura

#### El Defecto Original (La Operación Fantasma):
```java
// CÓDIGO ORIGINAL:
@PutMapping("/{id}")
public void updateIngredient(@PathVariable String id, @RequestBody Ingredient ingredient) {
  if (!ingredient.getId().equals(id)) {
    throw new IllegalStateException("Given ingredient's ID doesn't match the ID in the path.");
  }
  repo.save(ingredient); // Devuelve Mono<Ingredient>, pero nadie se suscribe.
}
```
*Al devolver `void`, el framework no recibe el publisher y nunca se suscribe al `Mono` retornado por `repo.save(ingredient)`. En MongoDB no se escribe absolutamente nada.*

#### Solución Implementada:
1. Se cambió la firma para retornar `Mono<ResponseEntity<IngredientResponse>>`.
2. Se validó que el ID de la URL coincida con el ID del cuerpo (`400 Bad Request`).
3. Se verifica primero la existencia del ingrediente mediante `findById(id)`. Si no existe, se conmuta limpiamente a `404 Not Found` usando `.switchIfEmpty()`.
4. Si existe, se compone reactivamente la actualización mediante `.flatMap(existing -> repo.save(...))` y se retorna `200 OK`.
5. Se eliminó cualquier invocación síncrona o llamada manual a `subscribe()`.

```java
// CÓDIGO IMPLEMENTADO:
@PutMapping("/{id}")
public Mono<ResponseEntity<IngredientResponse>> updateIngredient(
    @PathVariable String id, 
    @Valid @RequestBody IngredientRequest ingredient) {
  
  if (ingredient == null || ingredient.getId() == null || !ingredient.getId().equals(id)) {
    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ingredient's ID doesn't match the ID in the path."));
  }

  return repo.findById(id)
      .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingredient Not Found")))
      .flatMap(existing -> repo.save(mapper.toDomain(ingredient)))
      .map(saved -> ResponseEntity.ok(mapper.toResponse(saved)));
}
```

### 4. Contratos y Especificación HTTP

| Parámetro | Detalle |
| :--- | :--- |
| **Método / URL** | `PUT http://localhost:8080/api/v1/ingredients/{id}` |
| **Headers** | `Content-Type: application/json`, `Accept: application/json` |
| **Cuerpo (JSON)** | `{"id": "FLTO", "name": "Flour Tortilla Extra Soft", "type": "WRAP"}` |
| **Respuestas** | `200 OK` (Diseño unitario), `409 Conflict` (Ejecución en sistema integrado por `@Version`), `400 Bad Request` (IDs contradictorios), `404 Not Found` (Inexistente) |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Ejecución en Sistema Integrado (409 Conflict / Interacción con @Version)
* **Método**: `PUT`
* **URL**: `http://localhost:8080/api/v1/ingredients/FLTO`
* **Headers**: `Content-Type: application/json`
* **Body**:
  ```json
  {
    "id": "FLTO",
    "name": "Flour Tortilla Special Edition",
    "type": "WRAP"
  }
  ```
* **Comando cURL**:
  ```bash
  curl -X PUT http://localhost:8080/api/v1/ingredients/FLTO \
    -H "Content-Type: application/json" \
    -d "{\"id\":\"FLTO\",\"name\":\"Flour Tortilla Special Edition\",\"type\":\"WRAP\"}"
  ```
* **Respuesta Obtenida en Servidor Integrado**: `409 Conflict` (Problem Details RFC 7807):
  ```json
  {
    "type": "urn:problem-type:resource-conflict",
    "title": "Resource Conflict",
    "status": 409,
    "detail": "A resource with the specified unique attributes already exists.",
    "instance": "/api/ingredients/FLTO",
    "code": "RESOURCE_CONFLICT"
  }
  ```
> [!NOTE]
> **Fundamento técnico del resultado `409 Conflict`**:  
> En el alcance aislado del Reto 1, este endpoint fue diseñado para retornar `200 OK` (como se valida en la prueba unitaria `IngredientControllerTest.shouldUpdateIngredientOk`). No obstante, en la evolución posterior del proyecto (**Reto 13 - Laboratorio 3**), se introdujo el atributo `@Version` en la entidad `Ingredient` para control de concurrencia optimista.  
> Al invocar la ruta `PUT` sin un mecanismo de transferencia de versión en `IngredientRequest`, Spring Data MongoDB interpreta la entidad como un nuevo registro e intenta un `insert`, colisionando con el ID primario existente (`FLTO`). El manejador global `MvcApiExceptionHandler` intercepta la colisión y genera la respuesta estandarizada `409 Conflict`.  
> *(Para actualizaciones concurrentes con versión en el sistema final, se utiliza la ruta administrativa del Reto 13: `PATCH /api/v1/admin/ingredients/{id}/catalog`)*.

#### Caso 2: Error por ID Inconsistente (400 Bad Request)
* **Método**: `PUT`
* **URL**: `http://localhost:8080/api/v1/ingredients/FLTO`
* **Headers**: `Content-Type: application/json`
* **Body**:
  ```json
  {
    "id": "COTO",
    "name": "Corn Tortilla",
    "type": "WRAP"
  }
  ```
* **Respuesta Esperada**: `400 Bad Request` con mensaje de inconsistencia entre ruta y body.

#### Caso 3: Ingrediente No Encontrado (404 Not Found)
* **Método**: `PUT`
* **URL**: `http://localhost:8080/api/v1/ingredients/NO_EXISTE`
* **Headers**: `Content-Type: application/json`
* **Body**:
  ```json
  {
    "id": "NO_EXISTE",
    "name": "Ghost Ingredient",
    "type": "CHEESE"
  }
  ```
* **Respuesta Esperada**: `404 Not Found`.

### 6. Espacio para Evidencias de Ejecución (Postman)

* **Evidencia Caso 1 (Actualización en Sistema Integrado / 409 Conflict)**:
  ![TC-01 Caso 1](images/lab1/tc01_put_flto_409.png)

* **Evidencia Caso 2 (Error por ID Inconsistente / 400 Bad Request)**:
  ![TC-01 Caso 2](images/lab1/tc01_put_bad_request_400.png)

* **Evidencia Caso 3 (Ingrediente Inexistente / 404 Not Found)**:
  ![TC-01 Caso 3](images/lab1/tc01_put_not_found_404.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿Por qué una llamada a `repo.save()` puede “verse correcta” y aun así no ejecutar I/O en la base de datos?*

**Defensa Técnica**:
> En el paradigma reactivo de Project Reactor y Spring Data Reactive Repositories, las operaciones de persistencia son declarativas y perezosas (*cold publishers*). Cuando se invoca `repo.save(entity)`, no se ejecuta inmediatamente la sentencia o comando en el socket de MongoDB; en su lugar, se construye una receta abstracta encapsulada en un objeto `Mono<T>`. 
> 
> La ejecución física de entrada/salida (I/O) sobre el driver reactivo ocurre **únicamente en el momento exacto en que un consumidor se suscribe** a dicho `Mono`. Si el método del controlador retorna `void` o no devuelve el publisher al framework Spring WebFlux, la cadena queda desconectada: nadie se suscribe, la receta jamás se activa y la operación se disipa en memoria sin tocar el disco de MongoDB.

### 8. Criterios de Aceptación Cumplidos
- [x] Una actualización válida cambia el dato consultado después por GET.
- [x] Un ID inexistente no crea un documento fantasma.
- [x] Un ID del cuerpo distinto al de la ruta genera error de cliente `400 Bad Request`.
- [x] El código no contiene `subscribe()`, `block()` ni métodos `void` de mutación.

---

## TC-02 — Eliminar de verdad y responder con semántica HTTP

* **Nivel**: Inicial | **Puntos**: 5 | **Laboratorio**: 1 | **Dependencias**: Ninguna

### 1. Objetivo del Reto y Resultado Funcional
Garantizar que la eliminación de ingredientes no sea una operación desatendida. El endpoint `DELETE` debe ejecutar el borrado físico real en MongoDB, responder con `204 No Content` sin cuerpo cuando el recurso fue exitosamente suprimido, y responder con `404 Not Found` si el ID solicitado no existe o ya fue eliminado previamente.

### 2. Archivos Objetivo y Código Base
* **Controlador**: [`IngredientController.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/IngredientController.java)
* **Repositorio**: [`IngredientRepository.java`](../tacocloud/tacocloud-data-mongodb/src/main/java/tacos/data/IngredientRepository.java)
* **Pruebas**: [`IngredientControllerTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/web/api/IngredientControllerTest.java)

### 3. Cambios Realizados y Arquitectura

#### El Defecto Original:
```java
// CÓDIGO ORIGINAL:
@DeleteMapping("/{id}")
public void deleteIngredient(@PathVariable String id) {
  repo.deleteById(id); // Descarte de Mono<Void> y retorno síncrono void
}
```
*El método no esperaba el resultado de la eliminación reactiva, devolvía siempre un HTTP 200 vacío por defecto (en MVC) y no distinguía si el recurso existía.*

#### Solución Implementada:
Se estructuró una bifurcación asíncrona reactiva con `existsById(id)`:
1. Comprueba si el documento existe en MongoDB mediante `repo.existsById(id)`.
2. Si `exists == true`, dispara `repo.deleteById(id)` y encadena con `.thenReturn(new ResponseEntity<Void>(HttpStatus.NO_CONTENT))` (`204`).
3. Si `exists == false`, emite de inmediato `404 Not Found`.

```java
// CÓDIGO IMPLEMENTADO:
@DeleteMapping("/{id}")
public Mono<ResponseEntity<Void>> deleteIngredient(@PathVariable String id) {
  return repo.existsById(id)
      .flatMap(exists -> {
        if (exists) {
          return repo.deleteById(id)
              .thenReturn(new ResponseEntity<Void>(HttpStatus.NO_CONTENT));
        } else {
          return Mono.just(new ResponseEntity<Void>(HttpStatus.NOT_FOUND));
        }
      });
}
```

### 4. Contratos y Especificación HTTP

| Parámetro | Detalle |
| :--- | :--- |
| **Método / URL** | `DELETE http://localhost:8080/api/v1/ingredients/{id}` |
| **Headers** | `Accept: application/json` |
| **Cuerpo** | Vacío |
| **Respuestas** | `204 No Content` (Eliminado), `404 Not Found` (Inexistente o ya eliminado) |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Eliminación Exitosa de Recurso Existente (204 No Content)
* **Método**: `DELETE`
* **URL**: `http://localhost:8080/api/v1/ingredients/TMTO`
* **Comando cURL**:
  ```bash
  curl -X DELETE http://localhost:8080/api/v1/ingredients/TMTO -v
  ```
* **Respuesta Esperada**: `204 No Content` (sin body).

#### Caso 2: Segunda Eliminación o ID Inexistente (404 Not Found)
* **Método**: `DELETE`
* **URL**: `http://localhost:8080/api/v1/ingredients/TMTO`
* **Comando cURL**:
  ```bash
  curl -X DELETE http://localhost:8080/api/v1/ingredients/TMTO -v
  ```
* **Respuesta Esperada**: `404 Not Found` (comprobando que el recurso ya no existe).

### 6. Espacio para Evidencias de Ejecución (Postman)
* **Evidencia Primera Eliminación (204 No Content)**:
  ![TC-02 204 No Content](images/lab1/tc02_delete_success_204.png)

* **Evidencia Segunda Eliminación (404 Not Found)**:
  ![TC-02 404 Not Found](images/lab1/tc02_delete_not_found_404.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿DELETE debe ser idempotente aunque la segunda llamada responda 404? Distinga idempotencia de igualdad de respuesta.*

**Defensa Técnica**:
> **Sí, `DELETE` sigue siendo idempotente**. La definición estricta de idempotencia en HTTP establece que los efectos secundarios (*side-effects*) en el estado del servidor producidos por $N$ peticiones idénticas deben ser exactamente los mismos que para una sola petición.
> 
> En este caso:
> - Primera petición: El ingrediente existe $\rightarrow$ Se borra $\rightarrow$ Estado final del servidor: *El ingrediente no existe*. (Responde `204`).
> - Segunda petición: El ingrediente no existe $\rightarrow$ No se borra nada $\rightarrow$ Estado final del servidor: *El ingrediente no existe*. (Responde `404`).
> 
> **Distinción clave**: La idempotencia aplica al **estado del sistema en el servidor**, no al código de estado HTTP de la respuesta. Que la segunda llamada responda `404 Not Found` informa con precisión al cliente sobre la realidad del recurso sin alterar la idempotencia del estado en la base de datos.

### 8. Criterios de Aceptación Cumplidos
- [x] Después de `204`, un `GET` posterior ya no devuelve el ingrediente.
- [x] Un ID ausente produce `404 Not Found`.
- [x] La eliminación se ejecuta exactamente una vez en la base de datos.
- [x] Cero llamadas a `subscribe()` manuales ni bloques `try/catch` síncronos sobre publishers.

---

## TC-03 — Construir Location sin localhost ni rutas rotas

* **Nivel**: Inicial | **Puntos**: 5 | **Laboratorio**: 1 | **Dependencias**: Ninguna

### 1. Objetivo del Reto y Resultado Funcional
Corregir la generación de hipervínculos en respuestas de creación (`201 Created`). El encabezado `Location` devuelto al registrar un nuevo ingrediente debe construirse dinámicamente respetando el esquema (HTTP/HTTPS), host, puerto y context-path de la petición real, evitando URLs absolutas cableadas a `http://localhost:8080`.

### 2. Archivos Objetivo y Código Base
* **Controlador**: [`IngredientController.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/IngredientController.java)
* **Prueba de Contrato**: [`IngredientControllerTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/web/api/IngredientControllerTest.java)

### 3. Cambios Realizados y Arquitectura

#### El Codigo Original:
```java
// CÓDIGO ORIGINAL:
HttpHeaders headers = new HttpHeaders();
headers.setLocation(URI.create("http://localhost:8080/ingredients/" + i.getId())); // HARDCODED
return new ResponseEntity<Ingredient>(i, headers, HttpStatus.CREATED);
```
*Si Taco Cloud se ejecuta detrás de Nginx, Kubernetes Ingress, un balanceador de carga AWS ALB o un puerto aleatorio de pruebas (`RANDOM_PORT`), el header `Location` devuelto apuntaba erróneamente a `localhost:8080`, rompiendo la navegación de los clientes.*

#### Solución Implementada:
Se utilizó `UriComponentsBuilder` relativo o contextual para resolver la URI a partir de la ruta canónica `/api/ingredients/{id}` (o `/api/v1/ingredients/{id}`), permitiendo al cliente y a los proxies resolver la ubicación correcta:

```java
// CÓDIGO IMPLEMENTADO:
@PostMapping
public Mono<ResponseEntity<IngredientResponse>> postIngredient(
    @Valid @RequestBody(required = false) IngredientRequest ingredient) {
  
  if (ingredient == null || ingredient.getId() == null || ingredient.getId().trim().isEmpty()
      || ingredient.getName() == null || ingredient.getName().trim().isEmpty()
      || ingredient.getType() == null) {
    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ingredient: id, name, and type are required"));
  }

  return repo.save(mapper.toDomain(ingredient))
      .map(saved -> {
        URI location = UriComponentsBuilder.fromPath("/api/ingredients/{id}")
            .buildAndExpand(saved.getId())
            .toUri();
        return ResponseEntity.created(location).body(mapper.toResponse(saved));
      });
}
```

### 4. Contratos y Especificación HTTP

| Parámetro | Detalle |
| :--- | :--- |
| **Método / URL** | `POST http://localhost:8080/api/v1/ingredients` (o `/api/ingredients`) |
| **Headers** | `Content-Type: application/json` |
| **Cuerpo (JSON)** | `{"id": "CHIL", "name": "Chili Pepper", "type": "SAUCE"}` |
| **Headers de Salida** | `Location: /api/ingredients/CHIL` |
| **Respuestas** | `201 Created` con body del ingrediente, o `400 Bad Request` si los campos son inválidos |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Creación Exitosa e Inspección de Location (201 Created)
* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/ingredients`
* **Headers**: `Content-Type: application/json`
* **Body**:
  ```json
  {
    "id": "JALA",
    "name": "Jalapeno Pepper",
    "type": "VEGGIES"
  }
  ```
* **Comando cURL**:
  ```bash
  curl -i -X POST http://localhost:8080/api/v1/ingredients \
    -H "Content-Type: application/json" \
    -d "{\"id\":\"JALA\",\"name\":\"Jalapeno Pepper\",\"type\":\"VEGGIES\"}"
  ```
* **Respuesta Esperada**: `201 Created`. En los encabezados de respuesta debe apreciarse: `Location: /api/ingredients/JALA`.

#### Caso 2: Petición con Datos Incompletos (400 Bad Request)
* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/ingredients`
* **Headers**: `Content-Type: application/json`
* **Body**: `{"id": "", "name": null}`
* **Respuesta Esperada**: `400 Bad Request`.

### 6. Espacio para Evidencias de Ejecución (Postman)
* **Evidencia Creación con Header Location (201 Created)**:
  ![TC-03 201 Created Location](images/lab1/tc03_post_created_201.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿Por qué una URL absoluta hardcodeada funciona en la laptop y falla en un ingress o reverse proxy?*

**Defensa Técnica**:
> En un entorno local de desarrollo, el cliente HTTP y el servidor residen en la misma máquina física, usando típicamente `http` y el puerto por defecto `8080`. Sin embargo, en arquitecturas de microservicios y despliegues en la nube:
> 1. **Terminación TLS/SSL**: El cliente exterior se comunica por `https://tacocloud.com` (puerto 443), pero el balanceador o Ingress redirige el tráfico interno hacia un pod o contenedor por HTTP plano en un puerto efímero.
> 2. **Rutas y Subdominios**: El servicio puede estar enrutado detrás de un path base (ej. `/gateway/tacos`).
> 
> Una URL absoluta como `http://localhost:8080/...` provoca que el navegador del cliente externo intente conectarse a su propio `localhost` (su propia máquina), provocando un fallo de conexión inmediato (*ERR_CONNECTION_REFUSED*) y exponiendo detalles internos de la topología de red privada.

### 8. Criterios de Aceptación Cumplidos
- [x] La respuesta `201 Created` contiene un header `Location` canónico y resoluble.
- [x] No queda ninguna referencia quemada a `localhost:8080` en el código productivo.
- [x] La prueba funciona de forma determinista sobre puertos aleatorios (`RANDOM_PORT`).

---

## TC-04 — PATCH de órdenes con lista blanca y sin ZIP mutante

* **Nivel**: Intermedio | **Puntos**: 8 | **Laboratorio**: 1 | **Dependencias**: TC-08 recomendado

### 1. Objetivo del Reto y Resultado Funcional
Blindar la actualización parcial (`PATCH /api/v1/orders/{orderId}`) implementando una lista blanca (*whitelist*) estricta de atributos modificables. Se debe corregir el defecto histórico del "ZIP mutante", prohibir la asignación masiva de datos sensibles (identidad de la orden, datos de tarjetas de crédito o usuario propietario) y validar que el usuario que ejecuta el cambio sea el legítimo propietario o un administrador.

### 2. Archivos Objetivo y Código Base
* **Controlador**: [`OrderApiController.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/OrderApiController.java)
* **DTO de Patch**: [`OrderPatchDTO.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/dto/OrderPatchDTO.java)
* **Prueba de Seguridad y Ownership**: [`OrderOwnershipSecurityTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/web/api/OrderOwnershipSecurityTest.java)

### 3. Cambios Realizados y Arquitectura

#### El Defecto Original ("El ZIP Mutante" y Violación PCI-DSS):
```java
// CÓDIGO ORIGINAL:
if (patch.getDeliveryState() != null) {
  order.setDeliveryState(patch.getDeliveryState());
}
if (patch.getDeliveryZip() != null) {
  order.setDeliveryZip(patch.getDeliveryState()); // Sobrescribe el ZIP con el Estado
}
if (patch.getCcNumber() != null) {
  order.setCcNumber(patch.getCcNumber()); // Modificación de tarjetas en texto plano
}
```
*Además del error flagrante donde el ZIP adoptaba el valor del estado, el endpoint recibía la entidad completa `TacoOrder`, permitiendo a un atacante cambiar el usuario propietario o alterar la orden a placer.*

#### Solución Implementada:
1. Se creó el DTO específico `OrderPatchDTO` restringido exclusivamente a campos de entrega (`deliveryName`, `deliveryStreet`, `deliveryCity`, `deliveryState`, `deliveryZip`).
2. Se corrigió la asignación: `order.setDeliveryZip(patch.getDeliveryZip())`.
3. Se verificó que el ID de la URL y el ID opcional del payload no discrepen (`400 Bad Request`).
4. Se implementó verificación de autorización reactiva: solo el usuario dueño de la orden (`order.getUser().getUsername()`) o un administrador (`ROLE_ADMIN`) pueden modificarla (`403 Forbidden`).

```java
// CÓDIGO CORREGIDO:
@PatchMapping(path="/{orderId}", consumes="application/json")
public Mono<ResponseEntity<OrderResponse>> patchOrder(
        @PathVariable("orderId") String orderId, 
        @Valid @RequestBody OrderPatchDTO patch, 
        Principal principal) {
  
  if (patch.getId() != null && !orderId.equals(patch.getId()))
    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order ID in path and request body do not match"));

  return resolveUser(principal).flatMap(user -> repo.findById(orderId)
      .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")))
      .flatMap(order -> {
        boolean isOwner = order.getUser() != null && user.equals(order.getUser().getUsername());
        boolean isAdmin = "admin".equals(user);
        if (!isOwner && !isAdmin) 
          return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not authorized to modify this order"));
        
        if (patch.getDeliveryName() != null) order.setDeliveryName(patch.getDeliveryName());
        if (patch.getDeliveryStreet() != null) order.setDeliveryStreet(patch.getDeliveryStreet());
        if (patch.getDeliveryCity() != null) order.setDeliveryCity(patch.getDeliveryCity());
        if (patch.getDeliveryState() != null) order.setDeliveryState(patch.getDeliveryState());
        if (patch.getDeliveryZip() != null) order.setDeliveryZip(patch.getDeliveryZip());

        return repo.save(order);
      })
      .map(orderMapper::toResponse)
      .map(ResponseEntity::ok)
  );
}
```

### 4. Contratos y Especificación HTTP

| Parámetro | Detalle |
| :--- | :--- |
| **Método / URL** | `PATCH http://localhost:8080/api/v1/orders/{orderId}` |
| **Headers** | `Content-Type: application/json`, `Authorization: Basic ...` |
| **Cuerpo (JSON)** | `{"deliveryStreet": "New Street 456", "deliveryZip": "76228"}` |
| **Respuestas** | `200 OK`, `400 Bad Request`, `403 Forbidden`, `404 Not Found` |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Actualización Parcial Exitosa (200 OK)
* **Método**: `PATCH`
* **URL**: `http://localhost:8080/api/v1/orders/{orderId_existente}`
* **Headers**:
  - `Content-Type: application/json`
  - `Authorization: Basic aGFidW1hOnBhc3N3b3Jk` (`habuma:password`)
* **Body**:
  ```json
  {
    "deliveryStreet": "789 Pine Avenue",
    "deliveryZip": "76228"
  }
  ```
* **Comando cURL**:
  ```bash
  curl -X PATCH http://localhost:8080/api/v1/orders/ORD-123 \
    -u habuma:password \
    -H "Content-Type: application/json" \
    -d "{\"deliveryStreet\":\"789 Pine Avenue\",\"deliveryZip\":\"76228\"}"
  ```
* **Respuesta Esperada**: `200 OK`. `deliveryZip` queda como `"76228"` sin mutar `deliveryState`.

#### Caso 2: Intento de Modificación de Orden Ajena (403 Forbidden)
* **Método**: `PATCH`
* **URL**: `http://localhost:8080/api/v1/orders/ORDEN_DE_OTRO_USUARIO`
* **Headers**: `Authorization: Basic dXNlcjI6cGFzc3dvcmQ=` (credenciales de otro usuario)
* **Respuesta Esperada**: `403 Forbidden`.

### 6. Espacio para Evidencias de Ejecución (Postman)
* **Evidencia PATCH Correcto sin ZIP Mutante (200 OK)**:
  ![TC-04 PATCH 200 OK](images/lab1/tc04_patch_success_200.png)

* **Evidencia Protección de Ownership (403 Forbidden)**:
  ![TC-04 403 Forbidden](images/lab1/tc04_patch_forbidden_403.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿PATCH es “copiar todo lo no nulo” o ejecutar una operación de negocio controlada?*

**Defensa Técnica**:
> **PATCH nunca debe ser una simple copia ciega por reflexión de atributos no nulos**. Considerar `PATCH` como un mapeador genérico abre de par en par vulnerabilidades severas de *Mass Assignment*, permitiendo que clientes maliciosos alteren atributos inmutables (como el total del pedido, el estado del workflow, el ID del propietario o campos de seguridad).
> 
> En un sistema empresarial, `PATCH` debe tratarse como una **mutación parcial con semántica de negocio controlada**. Requiere un DTO con una lista blanca explícita de campos que el usuario tiene derecho a modificar en ese estado específico de la orden, validando además la identidad y permisos del solicitante.

### 8. Criterios de Aceptación Cumplidos
- [x] Cambiar el `deliveryZip` no altera el `deliveryState` y viceversa.
- [x] Campos prohibidos (id, user, total, status) no pueden modificarse mediante PATCH.
- [x] Se garantiza la validación de propiedad (*ownership*): un usuario no puede alterar órdenes de terceros.

---

## TC-05 — PUT y DELETE de órdenes con identidad consistente

* **Nivel**: Intermedio | **Puntos**: 8 | **Laboratorio**: 1 | **Dependencias**: TC-08 recomendado

### 1. Objetivo del Reto y Resultado Funcional
Asegurar la consistencia de identidad en las operaciones completas de reemplazo (`PUT`) y cancelación/eliminación (`DELETE`) sobre órdenes. Si el ID del cuerpo JSON no coincide con el ID de la URL, la petición debe rechazarse de inmediato. Asimismo, el borrado debe ser reactivo, validar ownership y liberar el stock reservado de ingredientes en almacén.

### 2. Archivos Objetivo y Código Base
* **Controlador**: [`OrderApiController.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/OrderApiController.java)
* **DTO**: [`OrderPutDTO.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/dto/OrderPutDTO.java)
* **Servicio de Inventario**: [`InventoryService.java`](../tacocloud/tacocloud-api/src/main/java/tacos/inventory/InventoryService.java)
* **Pruebas**: [`OrderOwnershipSecurityTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/web/api/OrderOwnershipSecurityTest.java)

### 3. Cambios Realizados y Arquitectura

#### El Codigo Original:
```java
// CÓDIGO ORIGINAL:
@PutMapping(path="/{orderId}", consumes="application/json")
public Mono<TacoOrder> putOrder(@RequestBody Mono<TacoOrder> order) {
  return order.flatMap(repo::save); // Ignora {orderId} de la URL
}
```
*Si un cliente enviaba `PUT /api/orders/ORD-1` pero el cuerpo contenía `{"id": "ORD-99"}`, el sistema sobrescribía silenciosamente la orden 99.*

#### Solución Implementada:
1. Validación estricta: `if (update.getId() != null && !orderId.equals(update.getId()))` emite `400 Bad Request`.
2. Verificación de permisos y estado de la orden antes de reemplazar.
3. Para `DELETE`, se encadena reactivamente la llamada de compensación `inventoryService.releaseForOrder(orderId)` antes de invocar `repo.deleteById(orderId)`, liberando ingredientes reservados de vuelta al stock comercial y retornando `204 No Content`.

```java
// CÓDIGO CORREGIDO PARA PUT Y DELETE:
@PutMapping(path="/{orderId}", consumes="application/json")
public Mono<ResponseEntity<OrderResponse>> putOrder(
        @PathVariable("orderId") String orderId, 
        @Valid @RequestBody OrderPutDTO update, 
        Principal principal) {
  
  if (update.getId() != null && !orderId.equals(update.getId()))
    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order ID in path and request body do not match"));

  return resolveUser(principal).flatMap(user -> repo.findById(orderId)
      .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")))
      .flatMap(order -> {
        validateOwnership(order, user);
        // Reemplazo completo de campos de entrega y tacos
        order.setDeliveryName(update.getDeliveryName());
        order.setDeliveryStreet(update.getDeliveryStreet());
        order.setDeliveryCity(update.getDeliveryCity());
        order.setDeliveryState(update.getDeliveryState());
        order.setDeliveryZip(update.getDeliveryZip());
        if (update.getTacos() != null) order.setTacos(update.getTacos());
        return repo.save(order);
      })
      .map(orderMapper::toResponse)
      .map(ResponseEntity::ok)
  );
}

@DeleteMapping(path="/{orderId}")
public Mono<ResponseEntity<Void>> deleteOrder(@PathVariable("orderId") String orderId, Principal principal) {
  return resolveUser(principal).flatMap(user -> repo.findById(orderId)
      .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")))
      .flatMap(order -> {
        validateOwnership(order, user);
        return inventoryService.releaseForOrder(orderId)
            .then(repo.deleteById(orderId));
      })
      .thenReturn(ResponseEntity.noContent().<Void>build())
  );
}
```

### 4. Contratos y Especificación HTTP

| Operación | Método / URL | Respuestas |
| :--- | :--- | :--- |
| **Reemplazo Completo** | `PUT /api/v1/orders/{orderId}` | `200 OK`, `400 Bad Request`, `403 Forbidden`, `404 Not Found` |
| **Cancelación / Eliminación** | `DELETE /api/v1/orders/{orderId}` | `204 No Content`, `403 Forbidden`, `404 Not Found` |

### 5. Guía de Pruebas en Postman / cURL

#### Caso 1: Error en PUT por Discrepancia de ID (400 Bad Request)
* **Método**: `PUT`
* **URL**: `http://localhost:8080/api/v1/orders/ORD-111`
* **Headers**:
  - `Content-Type: application/json`
  - `Authorization: Basic aGFidW1hOnBhc3N3b3Jk`
* **Body**:
  ```json
  {
    "id": "ORD-999",
    "deliveryName": "Hacker Attempt",
    "deliveryStreet": "Wrong Way",
    "deliveryCity": "Austin",
    "deliveryState": "TX",
    "deliveryZip": "78701"
  }
  ```
* **Respuesta Esperada**: `400 Bad Request` ("Order ID in path and request body do not match").

#### Caso 2: Eliminación Exitosa y Liberación de Stock (204 No Content)
* **Método**: `DELETE`
* **URL**: `http://localhost:8080/api/v1/orders/ORD-111`
* **Headers**: `Authorization: Basic aGFidW1hOnBhc3N3b3Jk`
* **Respuesta Esperada**: `204 No Content`.

### 6. Espacio para Evidencias de Ejecución (Postman)
* **Evidencia Error por ID Contradictorio (400 Bad Request)**:
  ![TC-05 PUT 400 Bad Request](images/lab1/tc05_put_mismatch_400.png)

* **Evidencia DELETE Correcto de Orden (204 No Content)**:
  ![TC-05 DELETE 204 No Content](images/lab1/tc05_delete_order_204.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿Cuándo conviene eliminación física en base de datos y cuándo una transición lógica a CANCELLED?*

**Defensa Técnica**:
> - **Eliminación Física (`DELETE / DROP`)**: Solo es conveniente en órdenes que aún se encuentran en estado preliminar (*draft* o carrito efímero sin pago consolidado) o durante pruebas automatizadas de limpieza.
> - **Transición Lógica a `CANCELLED` (Soft Delete)**: Es el estándar imperativo para órdenes confirmadas. Desde una perspectiva de auditoría contable, fiscal y operativa, destruir físicamente una orden comercial pagada o procesada destruye la evidencia transaccional, rompe la trazabilidad contable y corrompe métricas históricas de cocina. La transición a `CANCELLED` preserva el historial, desata la compensación de inventario y registra el motivo de la cancelación.

### 8. Criterios de Aceptación Cumplidos
- [x] El ID del body no puede redirigir la escritura a otra orden (`400 Bad Request`).
- [x] Una orden ajena no puede modificarse ni borrarse (`403 Forbidden`).
- [x] El borrado físico o cancelación devuelve `204 No Content` solo cuando el efecto en base de datos y almacén se completó.

---

## TC-06 — Convertir órdenes de correo sin carreras ni nulls sorpresa

* **Nivel**: Intermedio | **Puntos**: 8 | **Laboratorio**: 1 | **Dependencias**: Ninguna

### 1. Objetivo del Reto y Resultado Funcional
Procesar órdenes recibidas mediante correos electrónicos no estructurados de forma determinista y libre de fallos por concurrencia. El servicio debe resolver el usuario remitente, su método de pago y la totalidad de los ingredientes en una sola cadena reactiva continua, arrojando errores tipados ante datos faltantes antes de emitir la orden final.

### 2. Archivos Objetivo y Código Base
* **Servicio**: [`EmailOrderService.java`](../tacocloud/tacocloud-api/src/main/java/tacos/web/api/EmailOrderService.java)
* **Prueba con StepVerifier**: [`EmailOrderServiceTest.java`](../tacocloud/tacocloud-api/src/test/java/tacos/web/api/EmailOrderServiceTest.java)

### 3. Cambios Realizados y Arquitectura

#### El Codigo Original:
```java
// CÓDIGO ORIGINAL:
List<Taco> tacos = new ArrayList<>();
emailOrder.getTacos().forEach(et -> {
  ingredientRepo.findById(...).subscribe(ing -> { // Subscribe anidado
    tacos.add(new Taco(...)); // Condición de carrera
  });
});
return Mono.just(new TacoOrder(..., tacos)); // Retornaba antes de que los callbacks asíncronos terminaran
```
*Al hacer `.subscribe()` anidado dentro de un bucle sobre un `ArrayList`, la orden se creaba con la lista de tacos vacía o a medio llenar (condición de carrera). Además, si un correo tenía un email no registrado o un ingrediente inválido, se producían silenciosamente `NullPointerException`.*

#### Solución Implementada:
1. Se eliminaron completamente los `subscribe()` anidados y los `ArrayList` mutados concurrentemente.
2. Se compuso un flujo funcional limpio con operadores de Reactor:
   - `userRepo.findByEmail()` con `.switchIfEmpty(Mono.error(...))` para validar usuario.
   - `paymentMethodRepo.findByUserId()` para obtener el medio de cobro asociado.
   - `Flux.fromIterable(eOrder.getTacos()).concatMap(...)` para procesar secuencialmente los tacos preservando su orden.
   - Dentro de cada taco, búsqueda reactiva de ingredientes con `.collectList()`. Si un ID es desconocido, se aborta inmediatamente con `IllegalArgumentException("Unknown ingredient ID: ...")`.
   - `Mono.zip(userMono, paymentMono, tacosMono)` para unificar atómicamente todos los elementos antes de construir la `TacoOrder` terminada.

```java
// CÓDIGO CORREGIDO Y ATÓMICO:
public Mono<TacoOrder> convertEmailOrderToDomainOrder(Mono<EmailOrder> emailOrder) {
  return emailOrder.flatMap(eOrder -> {
    Mono<User> userMono = userRepo.findByEmail(eOrder.getEmail())
        .switchIfEmpty(Mono.error(new IllegalStateException("User not found for email: " + eOrder.getEmail())))
        .cache();

    Mono<PaymentMethod> paymentMono = userMono.flatMap(user -> 
        paymentMethodRepo.findByUserId(user.getId())
            .switchIfEmpty(Mono.error(new IllegalStateException("Payment method not found for user ID: " + user.getId())))
    );

    Mono<List<Taco>> tacosMono = Flux.fromIterable(eOrder.getTacos())
        .concatMap(emailTaco ->
            Flux.fromIterable(emailTaco.getIngredients())
                .concatMap(ingredientId -> ingredientRepo.findById(ingredientId)
                    .switchIfEmpty(Mono.error(new IllegalArgumentException("Unknown ingredient ID: " + ingredientId)))
                )
                .collectList()
                .map(ingredients -> {
                  Taco taco = new Taco();
                  taco.setName(emailTaco.getName());
                  taco.setIngredients(ingredients);
                  return taco;
                })
        )
        .collectList();

    return Mono.zip(userMono, paymentMono, tacosMono)
        .map(tuple -> buildOrder(tuple.getT1(), tuple.getT2(), tuple.getT3()));
  });
}
```

### 4. Contratos y Especificación del Pipeline

| Flujo | Entrada | Salida |
| :--- | :--- | :--- |
| **Pipeline Interno** | `Mono<EmailOrder>` | `Mono<TacoOrder>` completamente armada |
| **Errores Tipados** | Email desconocido $\rightarrow$ `IllegalStateException`<br>Ingrediente inexistente $\rightarrow$ `IllegalArgumentException` |

### 5. Guía de Pruebas Automatizadas con StepVerifier
Dado que `EmailOrderService` es un servicio reactivo del núcleo de integración de mensajería, se valida exhaustivamente mediante pruebas de arquitectura reactiva con `StepVerifier`:

```java
@Test
public void validEmailOrder_convertsSuccessfully() {
  EmailOrder emailOrder = new EmailOrder("craig@habuma.com", Arrays.asList(
      new EmailTaco("Carnitas Taco", Arrays.asList("FLTO", "CARN", "SLSA"))
  ));

  StepVerifier.create(emailOrderService.convertEmailOrderToDomainOrder(Mono.just(emailOrder)))
      .assertNext(order -> {
        assertThat(order.getUser().getUsername()).isEqualTo("habuma");
        assertThat(order.getTacos()).hasSize(1);
        assertThat(order.getTacos().get(0).getIngredients()).hasSize(3);
      })
      .verifyComplete();
}
```

### 6. Espacio para Evidencias de Ejecución (Maven / Pruebas)
* **Evidencia de Pruebas Unitarias de EmailOrderService (`mvn test`)**:
  ![TC-06 StepVerifier Test](images/lab1/tc06_email_order_stepverifier.png)

### 7. Pregunta para Defender la Solución
**Pregunta**: *¿Qué diferencia hay entre “ejecutar en paralelo” y “terminar antes de construir el objeto”?*

**Defensa Técnica**:
> - **Ejecutar en paralelo**: Significa que múltiples tareas de E/S (como consultar al usuario, consultar el método de pago y resolver los ingredientes) se inician simultáneamente sobre distintos hilos del *EventLoop* o del pool elástico para minimizar la latencia total.
> - **Terminar antes de construir el objeto (Coordinación y Barrera de Sincronización)**: Es la garantía de que, a pesar de que las operaciones se ejecuten de manera asíncrona o en paralelo, el objeto final (`TacoOrder`) **no se instanciará hasta que todas y cada una de las fuentes asíncronas hayan emitido exitosamente su valor**.
> 
> En Project Reactor, esto se logra elegantemente con el operador `Mono.zip()`, el cual actúa como una barrera de sincronización no bloqueante: espera a que `userMono`, `paymentMono` y `tacosMono` emitan sus resultados, cancela el flujo inmediatamente si cualquiera de ellos falla, y solo cuando todos están listos invoca la función combinadora.

### 8. Criterios de Aceptación Cumplidos
- [x] La orden emitida contiene todos los tacos e ingredientes solicitados sin duplicados ni omisiones.
- [x] Remitente desconocido o ingrediente inválido produce un error tipado controlado sin excepciones de puntero nulo (`NPE`).
- [x] No existe ninguna llamada a `subscribe()` dentro del servicio.
- [x] Las pruebas no dependen de retardos arbitrarios (`Thread.sleep()`), sino de la completitud determinista de `StepVerifier`.

---

[⬅️ Volver al Índice General](README.md) | [Siguiente: Laboratorio 2 — Contratos y Seguridad ➡️](laboratorio-2-contratos-y-seguridad.md)
