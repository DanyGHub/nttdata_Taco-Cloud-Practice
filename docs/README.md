# Taco Cloud: Documentación Técnica y Manual de Operaciones

Este repositorio documenta la evolución arquitectónica, refactorización y resolución de los **36 Retos Funcionales del archivo adjunto** organizados a lo largo de **6 Laboratorios Técnicos**.

Taco Cloud evolucionó desde una base de código inicial con operaciones reactivas incompletas, acoplamientos rígidos y vulnerabilidades de seguridad, hacia un ecosistema empresarial moderno, completamente reactivo (**Spring WebFlux**, **Project Reactor**, **Spring Data Reactive MongoDB**), seguro (**Spring Security** con política *Deny-by-Default*), desacoplado mediante el patrón de Puertos y Adaptadores (Arquitectura Hexagonal), tolerante a fallos (**Transactional Outbox**, **Idempotent Consumer**, **Dead Letter Queue**) y auditable mediante **OpenAPI 3.0**, métricas de **Micrometer** y trazabilidad distribuida con **Correlation ID**.

---

## Índice Navegable de Laboratorios y Retos Funcionales

La documentación detallada se encuentra dividida en 6 documentos especializados. Cada documento incluye la justificación funcional, comparativa de código, contratos HTTP, comandos cURL listos para Postman, espacios para evidencias y la defensa arquitectónica oficial:

| Laboratorio | Retos Cubiertos | Enfoque Principal | Documento |
| :--- | :--- | :--- | :--- |
| **Lab 1: Cazar operaciones fantasma** | **TC-01 al TC-06** | Reactividad pura con Project Reactor: eliminación de bloqueos `block()`, repositorios reactivos, corrección de publishers fríos no suscritos, eliminación de suscripciones anidadas y sincronización reactiva en MongoDB. | [Ver Documento del Lab 1](laboratorio-1-cazar-operaciones-fantasma.md) |
| **Lab 2: Contratos y seguridad** | **TC-07 al TC-12** | Seguridad estricta *Deny-by-Default*, desacople de entidades sensibles (eliminación de filtración de contraseñas y datos bancarios), migración a BCrypt adaptativo, saneamiento de Spring Data REST y blindaje de Actuator. | [Ver Documento del Lab 2](laboratorio-2-contratos-y-seguridad.md) |
| **Lab 3: Motor de negocio** | **TC-13 al TC-18** | Motor de dominio empresarial: catálogo monetario con `BigDecimal` y bloqueo optimista `@Version`, control de inventario con validación de diseño físico de tacos, motor de cotización con cupones y pasarela de pagos simulada. | [Ver Documento del Lab 3](laboratorio-3-motor-de-negocio.md) |
| **Lab 4: Funciones que sí dan ganas de usar** | **TC-19 al TC-24** | Experiencia de usuario y catálogo: búsqueda multicriterio con paginación acotada, recomendación determinista del taco del día con `Clock`, favoritos seguros sin IDOR, calificaciones y ranking con agregación en MongoDB, historial privado y reorden de compras con cotización de discrepancias (`409 Conflict`). | [Ver Documento del Lab 4](laboratorio-4-funciones-que-si-dan-ganas-de-usar.md) |
| **Lab 5: Cocina y mensajería confiable** | **TC-25 al TC-30** | Integración asíncrona robusta: máquina de estados para órdenes (`CREATED` a `DELIVERED`), reclamo atómico en cocina con `findAndModify` y cálculo de ETA, contrato canónico `OrderEvent`, selección de broker en runtime (NoOp/JMS/Rabbit/Kafka), Transactional Outbox y consumidor de cocina idempotente con DLQ. | [Ver Documento del Lab 5](laboratorio-5-cocina-y-mensajeria-confiable.md) |
| **Lab 6: Operación y calidad** | **TC-31 al TC-36** | Observabilidad y calidad en producción: Correlation ID distribuido en logs y eventos, métricas de negocio con Micrometer, anuncios operativos persistentes, control de concurrencia e idempotencia HTTP (`Idempotency-Key`), contrato OpenAPI 3.0 (`openapi.yaml`) y suite de regresión automatizada. | [Ver Documento del Lab 6](laboratorio-6-operacion-y-calidad.md) |

---

## Guía de Compilación y Ejecución

Esta sección detalla los prerrequisitos, comandos de construcción y formas de ejecución del proyecto para garantizar que cualquier auditor compile el código de manera 100% segura y reproducible.

### 1. Prerrequisitos del Entorno

* **Java Development Kit (JDK):** Java 11 LTS (OpenJDK, Eclipse Temurin o Amazon Corretto 11).
  * Verificar en terminal: `java -version`
  * Asegurarse de que la variable de entorno `JAVA_HOME` apunte a la instalación de JDK 11.
* **Apache Maven:** Versión 3.9+ (o utilizar los wrappers incluidos `./mvnw` en Linux/macOS o `.\mvnw.cmd` en Windows).
  * Verificar en terminal: `mvn -version`
* **Node.js y npm:** No se requiere instalación manual en el sistema anfitrión. El módulo `tacocloud-ui` utiliza el plugin `frontend-maven-plugin`, el cual descarga e instala automáticamente una versión local y aislada de Node.js y npm durante la compilación.
* **Docker / Docker Desktop (Opcional):** Requerido únicamente para ejecutar pruebas de integración avanzadas con contenedores reales de MongoDB mediante Testcontainers (`BrokerAndTestcontainersRegressionTest`). Para la ejecución estándar, el proyecto utiliza MongoDB embebido (Flapdoodle) o perfiles locales.

---

### 2. Comandos de Compilación (Maven)

Todos los comandos deben ejecutarse desde la raíz del proyecto multimódulo (`C:\...\nttdata_Taco-Cloud-Practice\tacocloud`):

#### Opción A: Compilación y Empaquetado Completo con Pruebas (Recomendada)
Ejecuta la limpieza, compilación, ejecución de toda la suite de pruebas unitarias, de integración y regresión, y empaqueta el artefacto ejecutable final:
```powershell
mvn clean package
```
> **Resultado Esperado:** `BUILD SUCCESS` en el 100% de los 17 módulos, generando el archivo ejecutable fat-JAR en `tacocloud/target/tacocloud-0.0.17-SNAPSHOT.jar` (~70.3 MB).

#### Opción B: Compilación Rápida (Omitiendo Pruebas)
Útil para generar el ejecutable rápidamente durante sesiones de desarrollo local:
```powershell
mvn clean package -DskipTests
```

#### Opción C: Verificación Exclusiva de Compilación de Pruebas
Valida la sintaxis y dependencias de todas las clases de prueba sin ejecutarlas:
```powershell
mvn test-compile
```

#### Opción D: Ejecución Única de Pruebas Unitarias y de Regresión
Ejecuta únicamente las pruebas del proyecto:
```powershell
mvn test
```

#### Opción E: Ejecución de un Módulo Específico
Si se desea compilar o probar únicamente un módulo en particular (por ejemplo, el módulo de API):
```powershell
mvn test -pl tacocloud-api
```

---

### 3. Mapa de Módulos del Proyecto (17 Módulos Maven)

| # | Módulo Maven | Responsabilidad Arquitectónica | Artefacto Generado |
| :---: | :--- | :--- | :--- |
| 1 | `tacocloud-parent` | POM padre con gestión de versiones y dependencias centralizadas. | `pom.xml` |
| 2 | `tacocloud-domain-mongodb` | Entidades del dominio, Value Objects y documentos MongoDB. | JAR de Dominio |
| 3 | `tacocloud-data-mongodb` | Repositorios reactivos y consultas dinámicas en MongoDB. | JAR de Persistencia |
| 4 | `tacocloud-messaging-contract` | Contrato de integración canónico desacoplado (`OrderEvent` v1). | JAR de Contrato |
| 5 | `tacocloud-security` | Configuración de Spring Security, encoders y UserDetailsService. | JAR de Seguridad |
| 6 | `tacocloud-api` | Controladores REST WebFlux, servicios de outbox, idempotencia y métricas. | JAR de Servicios API |
| 7 | `tacocloud-messaging-noop` | Adaptador de mensajería en memoria para desarrollo local. | JAR Adaptador NoOp |
| 8 | `tacocloud-messaging-jms` | Adaptador de transporte sobre JMS / Apache ActiveMQ. | JAR Adaptador JMS |
| 9 | `tacocloud-messaging-rabbitmq` | Adaptador de transporte sobre RabbitMQ (AMQP). | JAR Adaptador Rabbit |
| 10 | `tacocloud-messaging-kafka` | Adaptador de transporte sobre Apache Kafka. | JAR Adaptador Kafka |
| 11 | `tacocloud-kitchen` | Aplicación y consumidor de cocina con reclamo y DLQ. | JAR de Cocina |
| 12 | `tacocloud-email` | Flujo de integración por correo electrónico con Spring Integration. | JAR de Correo |
| 13 | `tacocloud-jmx` | MBeans de administración y monitorización JMX. | JAR de JMX |
| 14 | `tacocloud-restclient` | Cliente HTTP tipado para consumo de la API REST. | JAR Cliente REST |
| 15 | `tacocloud-web` | Vistas web y controladores Spring MVC. | JAR Web MVC |
| 16 | `tacocloud-ui` | Frontend compilado en Angular (embebido en los estáticos). | Recursos Web Estáticos |
| 17 | `tacocloud` (Principal) | Aplicación principal ejecutable que ensambla todos los módulos. | `tacocloud-0.0.17-SNAPSHOT.jar` |

---

### 4. Instrucciones de Ejecución de la Aplicación

Una vez completada la compilación (`mvn clean package`), la aplicación puede iniciarse mediante cualquiera de las siguientes modalidades:

#### Modalidad 1: Ejecución Estándar con Transporte NoOp (Desarrollo Local)
No requiere intermediarios de mensajería externos activos:
```powershell
java -jar tacocloud/target/tacocloud-0.0.17-SNAPSHOT.jar
```
* **Puerto Web/API:** `http://localhost:8080`
* **Interfaz Web:** Abrir el navegador en `http://localhost:8080` para interactuar con la aplicación.

#### Modalidad 2: Conmutación de Transporte de Mensajería en Tiempo de Ejecución
Sin necesidad de recompilar, se puede seleccionar el broker deseado mediante la bandera JVM `-Dtacocloud.messaging.transport`:

* **Conectar a RabbitMQ:**
  ```powershell
  java -Dtacocloud.messaging.transport=rabbit -jar tacocloud/target/tacocloud-0.0.17-SNAPSHOT.jar
  ```
* **Conectar a Apache Kafka:**
  ```powershell
  java -Dtacocloud.messaging.transport=kafka -jar tacocloud/target/tacocloud-0.0.17-SNAPSHOT.jar
  ```
* **Conectar a JMS / ActiveMQ:**
  ```powershell
  java -Dtacocloud.messaging.transport=jms -jar tacocloud/target/tacocloud-0.0.17-SNAPSHOT.jar
  ```

#### Modalidad 3: Ejecución de Servidores Complementarios
* **Servidor Spring Boot Admin (Supervisión y Monitorización):**
  ```powershell
  java -jar admin-server/target/admin-server-0.0.17-SNAPSHOT.jar
  ```
  * Disponible en: `http://localhost:9090`
* **Aplicación Autónoma de Cocina:**
  ```powershell
  java -jar tacocloud-kitchen/target/tacocloud-kitchen-0.0.17-SNAPSHOT.jar
  ```

---

## Matriz de Seguridad y Credenciales Preconfiguradas

Para pruebas mediante Postman, curl o interfaz web, el sistema cuenta con los siguientes usuarios precargados en la inicialización:

| Usuario | Contraseña | Rol Asignado | Alcance de Operaciones Permitidas |
| :--- | :--- | :--- | :--- |
| `habuma` | `password` | `ROLE_USER` | Crear tacos, realizar pedidos, consultar historial propio (`/users/me/orders`), gestionar favoritos (`/users/me/favorites`) y emitir calificaciones. |
| `kitchen` | `kitchen123` | `ROLE_KITCHEN` | Consultar cola de cocina (`/api/v1/kitchen/queue`), reclamar órdenes (`/claim`) y cambiar estados a `ACCEPTED`, `PREPARING` o `READY`. |
| `admin` | `admin` | `ROLE_ADMIN` | Acceso global: ajuste de catálogo de ingredientes y stock, anuncios operativos (`/admin/announcements`), métricas Actuator y replay de DLQ. |
| `delivery` | `delivery123` | `ROLE_DELIVERY` | Despacho logístico: avanzar órdenes de `READY` hacia `OUT_FOR_DELIVERY` y `DELIVERED`. |

---

## Catálogo de Endpoints Operativos y Documentación

* **Especificación OpenAPI 3.0:**
  * URL: `http://localhost:8080/openapi.yaml` (o `http://localhost:8080/api/v1/openapi.yaml`)
  * Descargable e importable directamente en Swagger Editor, Postman o Insomnia.
* **Comprobación de Salud del Sistema (Actuator):**
  * URL: `http://localhost:8080/actuator/health`
  * Muestra el estado de MongoDB, Outbox, transporte de mensajería y espacio en disco.
* **Métricas Operativas (Micrometer):**
  * URL: `http://localhost:8080/actuator/metrics`
  * Consulta específica: `http://localhost:8080/actuator/metrics/tacocloud.orders.created`
  * Backlog del Outbox: `http://localhost:8080/actuator/metrics/tacocloud.outbox.backlog`
* **Anuncios Operativos del Sistema:**
  * Públicos: `http://localhost:8080/api/v1/announcements`
  * Gestión Administrativa: `http://localhost:8080/api/v1/admin/announcements`

---

## Estructura de Directorios de Documentación

```text
nttdata_Taco-Cloud-Practice/
└── docs/
    ├── README.md                                           <-- (Este documento)
    ├── laboratorio-1-cazar-operaciones-fantasma.md         <-- Retos TC-01 a TC-06
    ├── laboratorio-2-contratos-y-seguridad.md              <-- Retos TC-07 a TC-12
    ├── laboratorio-3-motor-de-negocio.md                   <-- Retos TC-13 a TC-18
    ├── laboratorio-4-funciones-que-si-dan-ganas-de-usar.md  <-- Retos TC-19 a TC-24
    ├── laboratorio-5-cocina-y-mensajeria-confiable.md      <-- Retos TC-25 a TC-30
    ├── laboratorio-6-operacion-y-calidad.md                <-- Retos TC-31 a TC-36
    └── images/
        ├── lab1/  <-- Capturas de evidencia para Lab 1
        ├── lab2/  <-- Capturas de evidencia para Lab 2
        ├── lab3/  <-- Capturas de evidencia para Lab 3
        ├── lab4/  <-- Capturas de evidencia para Lab 4
        ├── lab5/  <-- Capturas de evidencia para Lab 5
        └── lab6/  <-- Capturas de evidencia para Lab 6
```
