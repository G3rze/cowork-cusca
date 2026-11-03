# Cowork API

API REST para reservar espacios de coworking: espacios, usuarios con roles, reservas sin solapamiento, validación de pago contra un servicio externo (simulado), notificación por correo (simulada) y reporte de ocupación.

Java 21 · Spring Boot 3.5 · Spring Data JPA · Spring Security (JWT) · PostgreSQL 16 · Flyway · Resilience4j · Caffeine · springdoc-openapi

## Ejecución

### Docker Compose

Requiere Docker con Compose.

```bash
docker compose up --build
```

Levanta PostgreSQL y la aplicación en `http://localhost:8080`.

Detener sin perder datos: `docker compose down`.

Reiniciar desde cero (borra el volumen `cowork_postgres_data` y, con él, toda la base de datos):

```bash
docker compose down -v
docker compose up --build
```

Al arrancar, Flyway recrea el esquema y la aplicación crea el administrador de demo.

| Recurso | URL |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui/index.html |
| OpenAPI (JSON) | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health |
| Circuit breaker | http://localhost:8080/actuator/circuitbreakers |

Credenciales de demo: `admin@cowork.local` / `Admin12345!` (ADMIN). Un USER se crea con `POST /api/v1/auth/register`.

### Desarrollo local

Requiere JDK 21 y Docker (para la base de datos).

```bash
docker compose up -d postgres
./mvnw spring-boot:run
```

### Pruebas

```bash
./mvnw clean verify
```

Requiere Docker: las pruebas de integración usan Testcontainers con PostgreSQL. El informe de cobertura queda en `target/site/jacoco/index.html`.

### Ejemplos

`api-examples.http` contiene el flujo completo para la extensión **REST Client** de VS Code (`humao.rest-client`). Se ejecuta en orden, de arriba abajo; los tokens y los ids se toman de las respuestas anteriores. Las fechas de reserva deben ser futuras.

### Simular fallos del pago

El mock de pago responde según el `paymentMethod` de `POST /api/v1/reservations/{id}/confirm`:

| `paymentMethod` | Respuesta | Estado de la reserva |
|---|---|---|
| `CARD_OK` (o cualquier otro) | `200` | `CONFIRMED` |
| `CARD_DECLINED` | `402` | `PENDING_PAYMENT` |
| `CARD_ERROR` (el proveedor responde 503) | `202` | `PENDING_PAYMENT` |
| `CARD_SLOW` (el proveedor tarda 5 s) | `202` a los ~2 s | `PENDING_PAYMENT` |

Con ≥ 50 % de fallos en 5 llamadas el circuito se abre y responde `202` sin llamar al proveedor durante 30 s.

## API

| Endpoint | Acceso | Descripción |
|---|---|---|
| `POST /api/v1/auth/register` | público | Registro (siempre rol `USER`) |
| `POST /api/v1/auth/login` | público | Devuelve un JWT |
| `GET /api/v1/spaces`, `GET /api/v1/spaces/{id}` | ADMIN, USER | Listado paginado y detalle |
| `POST`, `PUT`, `DELETE /api/v1/spaces[/{id}]` | ADMIN | Alta, edición y baja |
| `POST /api/v1/reservations` | ADMIN, USER | Crea una reserva (`PENDING_PAYMENT`). Admite `Idempotency-Key` |
| `GET /api/v1/reservations`, `GET /{id}` | USER: propias · ADMIN: todas | Consulta |
| `POST /api/v1/reservations/{id}/confirm` | dueño o ADMIN | Valida el pago y confirma. Admite `Idempotency-Key` |
| `POST /api/v1/reservations/{id}/cancel` | dueño o ADMIN | Cancela |
| `GET /api/v1/reports/occupancy?from=&to=` | ADMIN | Ocupación por espacio (cacheado) |

Errores (cuerpo JSON `ErrorResponse`): `400` validación · `401` sin token o inválido · `402` pago rechazado · `403` sin permisos · `404` no existe · `409` conflicto (solapamiento, transición inválida, modificación concurrente, idempotencia en proceso) · `422` misma `Idempotency-Key` con otra petición.

## Arquitectura

Capas `controller` → `service` → `repository`. Las entidades no salen por la API: se usan DTOs (`record`) y mappers.

```
controller/    endpoints REST
service/       reservas, confirmación, reporte, autenticación, idempotencia
repository/    Spring Data JPA y consultas nativas
entity/        User, Space, Reservation
state/         State: ciclo de vida de la reserva
pricing/       Strategy: tarifas
payment/       cliente del servicio de pago y mock interno
event/         eventos de dominio (Observer)
outbox/        outbox transaccional
notification/  EmailSender (simulado por log)
security/      JWT y manejo de 401/403
config/        propiedades tipadas, seguridad, caché, async, OpenAPI
exception/     excepciones de negocio y @RestControllerAdvice
```

Migraciones Flyway: `V1` extensión `btree_gist`, `V2` esquema, `V3` constraint de solapamiento, `V4` llaves de idempotencia, `V5` outbox. Hibernate solo valida el esquema (`ddl-auto: validate`).

## Decisiones técnicas

### Patrones

- **State** (`state/`): `PENDING_PAYMENT → CONFIRMED → COMPLETED`; `CANCELLED` desde `PENDING_PAYMENT` o `CONFIRMED`. Cada estado declara solo las transiciones que permite; el resto lanza `InvalidReservationTransitionException` (`409`). `COMPLETED` no tiene endpoint.
- **Strategy** (`pricing/`): tarifa estándar por minuto y 10 % de descuento desde 4 horas. `PriceCalculator` aplica la primera estrategia que corresponda según `@Order`.
- **Observer** (`event/`): `ReservationConfirmedEvent` y `ReservationCancelledEvent` son records con valores simples, sin entidades JPA. Los consume el outbox y el invalidador de caché.

### Reservas sin solapamiento

| Riesgo | Solución |
|---|---|
| Dos reservas para el mismo horario | Constraint `EXCLUDE USING gist` sobre `space_id` y `tstzrange(start_at, end_at)`, solo para estados activos. El rango es semiabierto: 10-11 y 11-12 no chocan |
| Deadlocks al insertar contra el constraint | `SELECT … FOR UPDATE` sobre la fila del espacio: las reservas del mismo espacio se encolan. `@Transactional(timeout = 5)` limita la espera |
| Dos transiciones de estado simultáneas | `@Version` en `Reservation` (`409` para la que pierde) |

El deadlock apareció en la prueba concurrente; el lock del espacio lo elimina. Consecuencia: las reservas de un mismo espacio se serializan.

### Confirmación y pago

`ReservationConfirmationService` no es `@Transactional`. Se divide en tres pasos para no retener una conexión durante la llamada al proveedor:

1. Transacción de lectura: valida acceso y estado.
2. Llamada al pago, sin transacción.
3. Transacción de escritura: recarga la reserva, aplica el resultado y guarda el evento del outbox.

`PaymentGateway` combina circuit breaker, bulkhead y timeouts (Resilience4j, configurados en `application.yml`):

- Timeouts: conexión 1 s, lectura 2 s.
- Circuit breaker: ventana de 10 llamadas, mínimo 5, abre con ≥ 50 % de fallos o ≥ 80 % de llamadas lentas (> 1 s); 30 s abierto.
- Bulkhead: 10 llamadas simultáneas. Un rechazo por bulkhead no cuenta como fallo del proveedor.
- Fallback: `UNAVAILABLE`. Nunca aprueba por defecto; la reserva queda `PENDING_PAYMENT` y la API responde `202`. Un pago rechazado responde `402`.
- Se envía `Idempotency-Key` (id de la reserva) al proveedor.

Con virtual threads (`spring.threads.virtual.enabled=true`) no se reservan hilos para pagos; se limita la concurrencia con el bulkhead.

### Idempotencia

`POST /reservations` y `POST /{id}/confirm` aceptan el header opcional `Idempotency-Key` (1-64 caracteres).

| Situación | Resultado |
|---|---|
| Primera petición | Se ejecuta y se guarda la respuesta |
| Reintento con la misma llave y petición | Respuesta guardada, con `Idempotent-Replayed: true` |
| Misma llave con otra petición | `422` |
| Reintento mientras la primera sigue en proceso | `409` |

Ejemplo (`-i` muestra los headers de la respuesta):

```bash
curl -i -X POST http://localhost:8080/api/v1/reservations/$RESERVATION_ID/confirm \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: confirm-0001" \
  -H "Content-Type: application/json" \
  -d '{"paymentMethod":"CARD_OK"}'
```

La primera llamada responde `200`. Repetir el mismo comando devuelve `200` con la misma respuesta y el header `Idempotent-Replayed: true`, sin volver a llamar al proveedor de pago. Cambiar el cuerpo con la misma llave devuelve `422`.

- Tabla `idempotency_keys`, clave `(user_id, idem_key)`; la llave se reclama con `INSERT … ON CONFLICT DO NOTHING` y se guarda un hash SHA-256 de la petición.
- La respuesta se guarda en la misma transacción que el cambio de negocio.
- Solo se guardan los éxitos. Un error, un `402` o un `202` liberan la llave.
- Una llave en `PROCESSING` puede ser tomada por otro reintento tras 2 minutos. Las llaves se purgan a las 24 h.

### Outbox

La confirmación escribe un evento en `outbox_events` dentro de su misma transacción; `OutboxPublisher` lo entrega después, de modo que un fallo del proceso no pierde la notificación.

- Consulta cada 2 s. Los eventos se reclaman con `FOR UPDATE SKIP LOCKED` y un lease (`locked_until`), por lo que varias réplicas no se duplican y no se retiene una conexión durante la entrega.
- Reintentos con backoff exponencial y jitter (de 5 s a 10 min). Tras 8 intentos el evento pasa a `FAILED`.
- Métricas: `cowork.outbox.backlog{status}`, `cowork.outbox.delivered`, `cowork.outbox.failures`.
- Entrega *at-least-once*: el id del evento viaja como `messageId` para que el destino pueda deduplicar.
- Los eventos entregados se purgan a los 7 días.

### Reporte de ocupación

Ocupación = horas reservadas dentro del horario / horas disponibles. Cuentan las reservas `CONFIRMED` y `COMPLETED`, recortadas al horario y al rango. Se calcula en la base de datos con una consulta nativa agregada. Horario configurable en `app.business-hours` (08:00–18:00, `America/El_Salvador`). Máximo 366 días por consulta.

Se cachea con Caffeine (`@Cacheable(sync = true)`, 10 min). La invalidación es un listener `AFTER_COMMIT` (`OccupancyCacheInvalidator`) y no `@CacheEvict`, para no borrar la entrada antes del commit.

### Seguridad

- JWT HS256 (1 h) con id, correo y rol; el filtro no consulta la base de datos. Contraseñas con BCrypt. API sin estado.
- Un USER que consulta la reserva de otro recibe `404`, no `403`.
- Los `401` y `403` se escriben como JSON desde el filtro.
- En `prod`, el secreto JWT y la base de datos son obligatorios por variable de entorno. Los valores de `docker-compose.yml` y `application-dev.yml` son de demostración.
- `/mock/**` es público porque la aplicación se llama a sí misma sin token.

### Otros

- JPA: relaciones `LAZY` con `@EntityGraph` en las consultas de reservas, listados paginados, `open-in-view=false`, `default_batch_fetch_size=20`.
- Hikari: pool de 10, espera máxima de 3 s, `leak-detection-threshold` de 20 s. Las métricas `hikaricp.*` están en Actuator.
- Actuator: `health`, `info`, `metrics` y `circuitbreakers`. `health` público sin detalle; el detalle y `metrics` requieren ADMIN o autenticación.
- Docker: imagen multi-stage, usuario no-root, `-XX:MaxRAMPercentage=70`, volcado de heap ante OOM y log de GC. PostgreSQL con `log_lock_waits=on`.
- Configuración propia en `@ConfigurationProperties` validados (`AppProperties`, `IdempotencyProperties`, `OutboxProperties`).

## Pruebas

163 pruebas; las que tocan la base de datos usan PostgreSQL real con Testcontainers.

- Unitarias (Mockito): servicios, State, Strategy, JWT, manejo de errores.
- Integración: autenticación y roles, CRUD, reservas, constraint de solapamiento, reporte, Actuator, OpenAPI.
- Concurrencia: 50 hilos sobre el mismo horario (entra una reserva y las otras 49 reciben `409`), 20 hilos con horarios distintos y 10 cancelaciones simultáneas.
- Pago: servidor HTTP falso que cubre aprobado, rechazado, 503, timeout, apertura y recuperación del circuito, bulkhead y ausencia de conexiones retenidas durante el pago.
- Idempotencia y outbox: reintentos simultáneos, llaves abandonadas, atomicidad, reintentos, dead letter y varios publicadores concurrentes.

## Limitaciones

- El proveedor de pago es un mock dentro de la misma aplicación (`APP_PAYMENT_MOCK_ENABLED=false` al usar uno real).
- El correo se simula con un log. La entrega del outbox puede tardar ~2 s.
- La caché es local a cada instancia.
- Sin `Idempotency-Key`, dos confirmaciones simultáneas pueden llamar ambas al proveedor.
- El horario de ocupación es único para todos los espacios y días.
- El rol viaja en el token: un cambio de rol se aplica al expirar el token. No hay refresh tokens ni revocación.
- Sin rate limiting, trazas distribuidas ni pruebas de carga.
- `.github/workflows/ci.yml` (build, pruebas y escaneo de imagen con Trivy) no se ha ejecutado en GitHub.
