# Cowork API

Microservicio REST para gestionar **reservas de espacios de coworking**: espacios, usuarios con roles, reservas sin solapamientos, validación de pago contra un servicio externo (simulado), notificación asíncrona y reporte de ocupación.

Stack: Java 21 · Spring Boot 3.5 · Spring Data JPA · Spring Security (JWT) · PostgreSQL 16 · Flyway · Resilience4j · Caffeine · springdoc-openapi.

## 1. Cómo ejecutarlo

### Con Docker Compose (recomendado)

Requisitos: Docker con Compose. No hace falta tener Java ni Maven.

```bash
docker compose up --build
```

Levanta PostgreSQL y la aplicación (`http://localhost:8080`). No requiere archivos adicionales ni variables previas: los valores de demo van en el `docker-compose.yml`. Para detener y borrar los datos: `docker compose down -v`.

| Qué | URL |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui/index.html |
| OpenAPI (JSON) | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health |
| Estado del circuit breaker | http://localhost:8080/actuator/circuitbreakers |

Credenciales de demo (solo para evaluación local, ver §6):

| Rol | Correo | Contraseña |
|---|---|---|
| ADMIN | `admin@cowork.local` | `Admin12345!` |
| USER | se crea con `POST /api/v1/auth/register` | la que elijas (mín. 8 caracteres) |

### Desarrollo local (perfil `dev`)

Requisitos: **JDK 21**, Docker (solo para la base de datos).

```bash
docker compose up -d postgres      # PostgreSQL en localhost:5432
./mvnw spring-boot:run             # perfil dev por defecto
```

### Pruebas

```bash
./mvnw clean verify                # 162 pruebas + informe de cobertura
```

Necesita Docker en ejecución: las pruebas de integración usan **Testcontainers** con un PostgreSQL real. El informe de cobertura queda en `target/site/jacoco/index.html` (≈ 97 % de instrucciones, ≈ 83 % de ramas).

### Ejemplos de uso

`api-examples.http` contiene el flujo completo (login, espacios, reservas, confirmación con cada método de pago, reporte, actuator). Funciona con la extensión *REST Client* de VS Code y con el cliente HTTP de IntelliJ. Ejecútalo en orden; las fechas de reserva deben seguir siendo futuras.

### Cómo probar la resiliencia del pago

El servicio de pago simulado decide su comportamiento según el `paymentMethod` de la petición de confirmación:

| `paymentMethod` | Respuesta de `POST /api/v1/reservations/{id}/confirm` | Reserva |
|---|---|---|
| `CARD_OK` (o cualquier otro) | `200` | `CONFIRMED` |
| `CARD_DECLINED` | `402` | sigue `PENDING_PAYMENT` |
| `CARD_ERROR` (el proveedor responde 503) | `202` (fallback) | sigue `PENDING_PAYMENT` |
| `CARD_SLOW` (el proveedor tarda 5 s) | `202` a los ~2 s (timeout) | sigue `PENDING_PAYMENT` |

Tras 5 llamadas con ≥ 50 % de fallos el circuito pasa a `OPEN` y responde `202` de inmediato, sin llamar al proveedor, durante 30 s. Se observa en `/actuator/circuitbreakers`.

## 2. API

| Método y ruta | Quién | Descripción |
|---|---|---|
| `POST /api/v1/auth/register` | público | Registro; siempre crea rol `USER` |
| `POST /api/v1/auth/login` | público | Devuelve un JWT |
| `GET /api/v1/spaces`, `GET /api/v1/spaces/{id}` | ADMIN, USER | Lista paginada / detalle |
| `POST`, `PUT`, `DELETE /api/v1/spaces[/{id}]` | ADMIN | CRUD de espacios |
| `POST /api/v1/reservations` | ADMIN, USER | Crea una reserva para el usuario autenticado (`PENDING_PAYMENT`). Admite `Idempotency-Key` (§5.9) |
| `GET /api/v1/reservations`, `GET /{id}` | USER: las suyas · ADMIN: todas | Consulta |
| `POST /api/v1/reservations/{id}/cancel` | dueño o ADMIN | Cancela |
| `POST /api/v1/reservations/{id}/confirm` | dueño o ADMIN | Valida el pago y confirma. Admite `Idempotency-Key` (§5.9) |
| `GET /api/v1/reports/occupancy?from=&to=` | ADMIN | % de ocupación por espacio (cacheado) |

Códigos de error (cuerpo JSON uniforme `ErrorResponse`): `400` validación / petición mal formada · `401` sin token o inválido · `402` pago rechazado · `403` sin permisos · `404` no existe · `409` conflicto (solapamiento, transición inválida, modificación concurrente, integridad de datos, petición idempotente aún en proceso) · `422` la misma `Idempotency-Key` con otra petición.

## 3. Arquitectura

Capas: `controller` → `service` → `repository`, con `dto` y `mapper` en los bordes (las entidades JPA nunca salen por la API).

```
controller/    REST: valida la entrada y delega
service/       lógica de reservas, confirmación, reporte, autenticación
repository/    Spring Data JPA (+ consulta nativa de agregación)
entity/        User, Space, Reservation (@Version)
state/         State: ciclo de vida de la reserva
pricing/       Strategy: cálculo de tarifas
payment/       cliente del servicio de pago (+ mock interno)
event/         eventos de dominio (Observer)
outbox/        outbox transaccional: registro, publicador, reintentos
notification/  puerto EmailSender (implementación simulada por log)
security/      JWT (servicio + filtro) y manejo de 401/403
config/        propiedades tipadas, seguridad, caché, async, OpenAPI
exception/     excepciones de negocio + @RestControllerAdvice
```

Migraciones Flyway: `V1` extensión `btree_gist`, `V2` esquema, `V3` constraint contra solapamientos, `V4` llaves de idempotencia, `V5` outbox. Hibernate solo **valida** el esquema (`ddl-auto: validate`).

## 4. Requisitos técnicos y por qué se usó cada uno

| Requisito | Qué se hizo y por qué |
|---|---|
| **Spring Boot 3.x + Java 21** | Boot 3.5. Java 21 por los *virtual threads* (`spring.threads.virtual.enabled=true`): las llamadas bloqueantes (HTTP al proveedor de pago, correo) no consumen hilos de plataforma. |
| **Spring Data JPA** | Relaciones `@ManyToOne(fetch = LAZY)` y `@EntityGraph` en las consultas de reservas para traer `space` y `user` en una sola consulta (**sin N+1**); el mapeo a DTO ocurre dentro de la transacción. Listados paginados. El reporte usa una consulta nativa de agregación (los cálculos se hacen en la BD, no en memoria). Índices en las claves foráneas. `open-in-view=false`: la conexión se abre y cierra en la capa de servicio, no durante la serialización de la respuesta. |
| **Spring Security + JWT** | API sin estado: JWT HS256 firmado (1 h) con id, correo y rol; el filtro reconstruye el usuario desde el token, sin consultar la BD en cada petición. Contraseñas con BCrypt. Reglas por ruta y rol en `SecurityConfig`. Contra IDOR: un USER que pide la reserva de otro recibe `404` (no `403`), para no revelar qué ids existen. Los `401`/`403` se escriben como JSON desde el filtro, porque `@ControllerAdvice` no los ve. |
| **Bean Validation + `@ControllerAdvice`** | DTOs `record` con `jakarta.validation`; `GlobalExceptionHandler` centraliza el mapeo a HTTP y no filtra detalles internos (errores 500 y de integridad devuelven mensajes genéricos). |
| **Actuator** | `health`, `info`, `metrics` y `circuitbreakers`. `/actuator/health` es público pero sin detalle; un ADMIN ve la base de datos y el estado del circuito. `metrics` exige autenticación. |
| **Perfiles + `@ConfigurationProperties`** | `application-dev.yml` / `application-prod.yml`. Toda la configuración propia está en records tipados y validados: `AppProperties` (JWT, administrador inicial, pago, horario) e `IdempotencyProperties` (TTL y tiempos de las llaves). En `prod` el secreto JWT y la base de datos son obligatorios por variable de entorno: sin ellos la aplicación no arranca. |
| **Caché `@Cacheable`/`@CacheEvict`** | `@Cacheable` en el reporte de ocupación (Caffeine, 10 min como red de seguridad). **La invalidación no usa la anotación `@CacheEvict`**: se hace en un listener `AFTER_COMMIT` (`OccupancyCacheInvalidator`). Con `@CacheEvict` la entrada se borraría *antes* del commit y otra petición podría volver a cachear el dato viejo. |
| **`@Async` / eventos** | `ApplicationEventPublisher` para los eventos de dominio y `@Async` sobre un executor de virtual threads para entregar cada notificación (ver §5.3 y §5.10). |
| **`@Transactional`** | `ReservationService` es transaccional (lectura por defecto, escritura en `create`/`cancel`). La confirmación **no** lo es a propósito (ver §5.4). |
| **OpenAPI** | springdoc genera el documento; esquema `bearerAuth` para probar con el botón *Authorize*. |
| **Testing** | Mockito para la lógica de servicios; integración con Testcontainers (ver §7). |
| **Docker** | `Dockerfile` multi-stage (JDK 21 para compilar, JRE 21 para ejecutar), usuario no-root, `-XX:MaxRAMPercentage=70`. `docker-compose.yml` con PostgreSQL con *healthcheck* y la aplicación. |
| **Circuit Breaker** | Resilience4j sobre el cliente de pago, configurado en `application.yml` (§5.5). |
| **Patrón GoF de comportamiento** | Se aplican **tres**: State, Strategy y Observer (§5.1–5.3). |

## 5. Patrones y decisiones de diseño

### 5.1 State: ciclo de vida de la reserva

`PENDING_PAYMENT → CONFIRMED → COMPLETED`, y `CANCELLED` desde `PENDING_PAYMENT` o `CONFIRMED`. `CANCELLED` y `COMPLETED` son terminales.

- **Problema que resuelve:** con `if/else` o `switch` sobre el estado, las reglas de transición quedan repartidas por todos los servicios y cada estado nuevo obliga a tocar muchos sitios.
- **Cómo:** `ReservationState` rechaza por defecto cualquier transición (`InvalidReservationTransitionException`, `409`); cada estado (`PendingPaymentState`, `ConfirmedState`…) declara solo lo que permite. `Reservation.confirm()/cancel()/complete()` delegan en el estado, así que la entidad no puede llegar a un estado inválido.
- No hay endpoint para `complete()`: el estado existe y está probado, pero ningún requisito lo expone.

### 5.2 Strategy: tarifas

`PricingStrategy` (`appliesTo` + `calculate`). `StandardPricingStrategy`: tarifa/hora prorrateada por minuto. `LongBookingDiscountStrategy`: 10 % de descuento desde 4 horas. `PriceCalculator` toma la primera que aplica (orden con `@Order`). Una política nueva es una clase nueva, sin tocar el flujo de reservas.

### 5.3 Observer: eventos de dominio

`ReservationConfirmedEvent` y `ReservationCancelledEvent` son `record`s con valores simples, no la entidad JPA: así los listeners (que corren dentro de la transacción o en otro hilo) nunca tocan relaciones perezosas. Los observadores reaccionan solo si la operación se confirma: el invalidador de caché escucha con `@TransactionalEventListener(AFTER_COMMIT)` y el registro en el outbox corre dentro de la propia transacción (si hay rollback, desaparece con ella).

- `OutboxRecorder`: convierte `ReservationConfirmedEvent` en una fila del **outbox**, dentro de la misma transacción de la confirmación; un publicador la entrega después por correo, de forma asíncrona y con reintentos (§5.10). La respuesta HTTP no espera al correo.
- `OccupancyCacheInvalidator`: vacía la caché del reporte.

### 5.4 Concurrencia: tres mecanismos, cada uno para un problema

| Problema | Mecanismo |
|---|---|
| **Dos reservas simultáneas para el mismo horario** | Constraint de exclusión de PostgreSQL (`EXCLUDE USING gist` sobre `space_id` y `tstzrange(start_at, end_at)`, solo para estados activos, rango semiabierto: 10-11 y 11-12 no chocan). La **base de datos** es el árbitro, no un `if exists` en el código (que tendría una condición de carrera) ni un `synchronized` (que no funciona con varias réplicas). |
| **Deadlocks al insertar contra ese constraint** | `create` toma un `SELECT … FOR UPDATE` sobre la fila del espacio: las reservas de un mismo espacio hacen cola en un único lock. Lo descubrió el test concurrente: sin él, PostgreSQL detectaba deadlocks y se agotaba el pool. Con `@Transactional(timeout = 5)`. |
| **Dos transiciones de estado a la vez** (p. ej. cancelar mientras se confirma) | `@Version` en `Reservation` (bloqueo optimista) → `409`. |

Las tres garantías se prueban con 50 hilos reales (`ReservationConcurrencyIntegrationTest`): de 50 peticiones al mismo horario entra exactamente **una** y las otras 49 reciben `409`.

### 5.5 Confirmación de reserva: pago fuera de la transacción

`ReservationConfirmationService` **no es `@Transactional`**. Una transacción durante la llamada remota retendría una conexión de BD todo el tiempo que tarde el proveedor; con un pool pequeño, unos pocos pagos lentos dejarían sin conexiones a endpoints que ni tocan pagos. Se divide en tres pasos y la conexión solo se usa en los dos cortos:

1. Transacción corta de lectura: carga la reserva, comprueba el acceso y que aún pueda confirmarse (no se cobra por una transición ya imposible).
2. **Sin transacción:** llamada al servicio de pago.
3. Transacción corta de escritura: recarga la reserva y aplica el resultado; `@Version` detecta si la cancelaron mientras tanto.

Un test verifica que durante la llamada de pago las conexiones activas del pool son **0**.

### 5.6 Resiliencia del servicio de pago

`PaymentGateway` combina, de fuera hacia dentro:

- **Circuit breaker** (`application.yml`): ventana de 10 llamadas, mínimo 5, abre con ≥ 50 % de fallos o ≥ 80 % de llamadas lentas (> 1 s), 30 s abierto, 3 llamadas de prueba en semiabierto.
- **Bulkhead** de semáforo (10 llamadas simultáneas): evita que un proveedor lento consuma todos los hilos. Una llamada rechazada por el bulkhead no cuenta como fallo del proveedor (no abre el circuito).
- **Timeouts** del cliente: conexión 1 s, lectura 2 s.
- **Fallback:** `UNAVAILABLE`. **Nunca aprueba por defecto**: la reserva queda `PENDING_PAYMENT` y el endpoint responde `202` en vez de fallar, para reintentar luego. Un pago *rechazado* es otra cosa: `402`, y no cuenta como fallo del servicio.
- Se envía `Idempotency-Key` con el id de la reserva para que un proveedor real pueda deduplicar.

Con virtual threads no se "reservan hilos" para pagos: lo que se limita es la **concurrencia** hacia el proveedor (el bulkhead).

### 5.7 Reporte de ocupación

`ocupación = horas reservadas dentro del horario / horas disponibles` (días del rango × ventana diaria). El horario es configurable (`app.business-hours`: `opening` 08:00, `closing` 18:00, `zone` America/El_Salvador; variables `APP_BUSINESS_OPENING`, `APP_BUSINESS_CLOSING`, `APP_BUSINESS_ZONE`) para no dar por hecho que un espacio está disponible 24 h. Solo cuentan reservas `CONFIRMED` y `COMPLETED`; se recortan al horario y al rango (una reserva que cruza la medianoche se reparte entre los días). Máximo 366 días por consulta.

### 5.8 Por qué no Chain of Responsibility

El PDF lo menciona como una opción de patrón. No se usó porque las validaciones previas a confirmar son pocas (propiedad, estado, pago) y ya están separadas con claridad: acceso en `ReservationAccess`, estado en el State y pago en `PaymentGateway`. Una cadena habría añadido indirección sin reducir complejidad.

### 5.9 Idempotencia: reintentos seguros de `POST` (crear y confirmar)

Si la respuesta de un `POST` se pierde por un timeout de red, el cliente reintenta; sin protección, repetir una confirmación podría cobrar dos veces. Ambos endpoints aceptan el header opcional `Idempotency-Key` (1-64 caracteres: letras, dígitos, `.`, `_`, `:`, `-`; un UUID por intención):

| Situación | Resultado |
|---|---|
| Primera petición con la llave | Se ejecuta; si cambió algo, se guarda su respuesta |
| Reintento con la misma llave y la misma petición | Se devuelve la **respuesta guardada** (header `Idempotent-Replayed: true`), sin volver a ejecutar ni llamar al proveedor de pago |
| Misma llave con otra petición (otro horario, otro método de pago, otra reserva) | `422` |
| Reintento mientras la primera aún se procesa | `409`: nunca se ejecuta dos veces |
| Llave de otro usuario | Es independiente: las llaves se acotan por usuario, así nadie puede leer la respuesta de otro |

**Cómo funciona**
- Tabla `idempotency_keys` con clave primaria `(user_id, idem_key)`: la **base de datos** arbitra (`INSERT … ON CONFLICT DO NOTHING`), no un "consultar si existe" que tendría una condición de carrera. Guarda un hash SHA-256 de la operación y sus parámetros para detectar el reuso con otra petición.
- La llave se **reclama** (con commit inmediato) antes de ejecutar y la respuesta se **registra dentro de la misma transacción** que el cambio de negocio: en `POST /reservations` la reserva y la respuesta guardada se confirman juntas o no se confirma nada; en `confirm` la respuesta se guarda en la transacción corta que confirma la reserva (el pago sigue fuera de toda transacción, §5.5).
- Solo se guardan las respuestas de peticiones que **sí cambiaron algo**. Un error, un pago rechazado (`402`) o un `202` "pago no disponible" **libera la llave**: reintentar con la misma llave vuelve a ejecutar de verdad (p. ej. cuando el proveedor se recupera) en vez de repetir el fallo.
- Si el proceso muere con una llave en `PROCESSING`, tras `app.idempotency.processing-timeout` (2 min) un reintento puede tomarla. Los datos no se duplican: el reintento chocaría con el constraint de solapamiento o con la comprobación de estado.
- Las llaves expiran (`app.idempotency.ttl`, 24 h) y un job periódico (`IdempotencyCleanup`) las purga. Se puede ejecutar en todas las réplicas a la vez sin lock distribuido, porque borrar filas expiradas es idempotente.
- Sin el header todo funciona como antes (la llave es opcional para no romper clientes existentes; un banco la exigiría).

### 5.10 Outbox transaccional: notificar sin perder eventos

**El problema (dual write).** Confirmar una reserva escribe en la base de datos *y* debe avisar a un sistema externo (el correo). Son dos sistemas sin transacción común: si el proceso muere entre el commit y el envío, la reserva queda confirmada y el aviso se pierde; con `AFTER_COMMIT` + `@Async` pasaba justo eso. Una transacción distribuida (2PC) acoplaría los sistemas, así que se usa el patrón *transactional outbox*.

**Cómo funciona**
1. **Escritura atómica:** al confirmar, `OutboxRecorder` inserta una fila en `outbox_events` **dentro de la misma transacción** que cambia la reserva. Existe si y solo si la confirmación se confirmó; si hay rollback, desaparece con ella. Insertar fuera de una transacción es un error de programación y se rechaza.
2. **Publicación aparte:** `OutboxPublisher` consulta cada 2 s (`app.outbox.poll-interval`) los eventos pendientes y los entrega con `OutboxDispatcher` (`@Async`, un virtual thread por mensaje, para que un destinatario lento no retrase al resto).
3. **Varias réplicas sin duplicar:** los eventos se reclaman con `SELECT … FOR UPDATE SKIP LOCKED` más un **lease** (`locked_until`). Se usa un lease y no una transacción abierta para **no retener una conexión de la base de datos mientras se llama al exterior** (§5.5). Si el worker muere a mitad de una entrega, el lease vence y otro retoma el evento.
4. **Reintentos y dead letter:** si la entrega falla se reprograma con *backoff* exponencial con jitter (5 s, 10 s, 20 s… hasta 10 min). Tras 8 intentos pasa a `FAILED` (dead letter): no se reintenta más y se conserva para revisarlo.
5. **Observabilidad:** la métrica `cowork.outbox.backlog{status=pending|failed}` (visible en `/actuator/metrics`) y los contadores `cowork.outbox.delivered` y `cowork.outbox.failures`. Un `pending` creciente indica una entrega atascada; cualquier `failed` requiere atención.
6. **Limpieza:** los eventos entregados se purgan a los 7 días; los `FAILED` no se purgan.

**Garantía: *at-least-once*.** Un evento puede entregarse dos veces (por ejemplo, un worker más lento que su lease). Por eso el id del evento viaja como `messageId` en `EmailSender.send(...)`: un proveedor real lo usa para deduplicar, igual que un consumidor idempotente. La implementación simulada solo lo registra en el log.

**Verificado**
- Atomicidad: si la confirmación hace rollback tras escribir el evento, no queda fila ni se envía nada.
- Caída del proceso: se mató la aplicación justo tras el commit; la fila quedó `PENDING` con 0 envíos y, al reiniciar, el correo salió y el evento quedó `SENT`.
- Reintentos con backoff, dead letter, lease de un worker caído, lease de un worker vivo (se respeta), y 4 publicadores concurrentes sobre 60 eventos: cada uno se entrega exactamente una vez.

## 6. Seguridad: lo que hay que saber

- Las credenciales y el secreto JWT del `docker-compose.yml` y de `application-dev.yml` son **de demostración**. En un despliegue real irían en un gestor de secretos; en `prod` fuera de Compose son obligatorios por variable de entorno (`APP_JWT_SECRET`, base de datos) y no tienen valor por defecto.
- El rol viaja dentro del token: un cambio de rol se aplica cuando el token expira (1 h). No hay *refresh tokens* ni revocación.
- `/mock/**` es público porque la aplicación se llama a sí misma sin token (ver §8). `/actuator/health`, `/actuator/info` y `/actuator/circuitbreakers` son públicos; `metrics` y el detalle de `health` no.
- El registro público siempre crea `USER`. El primer ADMIN se crea al arrancar desde configuración (`app.admin.*`, idempotente).

## 7. Pruebas

162 pruebas, todas contra PostgreSQL real cuando tocan la base de datos.

- **Unitarias (Mockito):** servicios de reservas, confirmación, reporte, espacios y autenticación; State; Strategy; JWT; manejo de errores; enmascarado de correos. Destaca que el orden pago-entre-transacciones se verifica con `InOrder`.
- **Integración (Testcontainers):** autenticación y roles, CRUD, reservas y permisos, el constraint de solapamiento directamente sobre la BD, reporte (cálculo, medianoche, caché que se invalida), actuator, OpenAPI y errores HTTP.
- **Resiliencia con un proveedor real:** `ReservationConfirmationIntegrationTest` levanta un servidor HTTP falso y controlable y comprueba aprobado, rechazado, error 503, proveedor lento cortado por el timeout, apertura y recuperación del circuito, límite del bulkhead, ausencia de conexiones retenidas y notificación asíncrona.
- **Idempotencia:** misma llave → misma respuesta y una sola reserva; otra petición con la misma llave → 422; llaves por usuario; fallos no almacenados; 20 reintentos simultáneos → una reserva; 8 confirmaciones simultáneas con la misma llave → el proveedor se llama **una** vez; toma de llaves abandonadas y purga de expiradas.
- **Outbox:** atomicidad con la confirmación, caída del proceso, reintentos con backoff, dead letter, leases, 4 publicadores concurrentes sin duplicados, purga y métricas.
- **Concurrencia:** 50 hilos reservando el mismo horario (solo uno entra), 20 hilos con horarios distintos (todos entran) y 10 cancelaciones simultáneas (solo una transiciona).

## 8. Compromisos por el límite de tiempo

- **El proveedor de pago es un mock dentro de la misma aplicación** (`/mock/payments/validate`, mismo puerto). Se llama por HTTP real con timeouts, pero no es un servicio independiente: si la aplicación cae, el "proveedor" cae con ella. Una alternativa más fiel sería WireMock como tercer contenedor; se prefirió tener menos piezas que puedan fallar al levantar. Se desactiva con `APP_PAYMENT_MOCK_ENABLED=false` al apuntar a un proveedor real.
- **El outbox entrega *at-least-once*, no exactly-once:** un correo puede llegar duplicado en casos límite; se mitiga con el `messageId`, que un proveedor real debe respetar. Además, la entrega no es inmediata: hay hasta ~2 s (el intervalo de sondeo) entre la confirmación y el envío. El invalidador de la caché del reporte sigue usando `AFTER_COMMIT`, porque la caché es local y se pierde con el proceso: no necesita durabilidad.
- **Idempotencia opcional:** sin `Idempotency-Key`, dos confirmaciones simultáneas del mismo id aún pueden llamar ambas al proveedor (la segunda falla después por `@Version`, `409`); con la llave, solo una llega al proveedor. También se envía `Idempotency-Key` (el id de la reserva) al proveedor de pago, que debe respetarla para deduplicar. `cancel` no usa llave: repetirlo devuelve `409` porque la reserva ya está cancelada.
- **La caché es local a cada instancia.** Con varias réplicas, cada una tendría la suya.
- **El lock del espacio serializa las reservas de ese espacio.** Es lo adecuado para este dominio; un espacio con miles de reservas por segundo sería un cuello de botella.
- **Horario único:** igual para todos los días y todos los espacios.
- Cualquier usuario autenticado (USER o ADMIN) puede crear reservas, siempre para sí mismo.

## 9. Fuera de alcance y qué se haría con más tiempo

- **Envío real de correo** (el `EmailSender` actual solo lo simula) y un destino de eventos hacia un broker (Kafka/RabbitMQ) reutilizando el mismo outbox; un consumidor con deduplicación por `messageId`.
- **Caché distribuida (Redis)** y observabilidad (Prometheus, trazas distribuidas).
- **Refresh tokens**, revocación y *rate limiting* (Bucket4j) en login y en la API.
- **WireMock** como proveedor independiente en el Compose y reintentos con *backoff* (solo con idempotencia garantizada).
- Horarios por día/espacio y días de cierre; endpoint para marcar reservas como `COMPLETED` (hoy sería un job).
- Pruebas de carga (k6/Gatling) y de contrato; mínimo de cobertura exigido en el build.
- Despliegue: *manifests* de Kubernetes, gestor de secretos y *pipeline* de CI.
