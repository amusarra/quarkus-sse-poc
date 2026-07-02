# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Fixed
### Added
### Changed
### Removed
### Deprecated
### Security

## [1.3.1-SNAPSHOT] - Unreleased

### Added

- **Micrometer Extension**:  
  Added the Metrics extension to expose the `/q/metrics` endpoint for Prometheus
  scraping. The following metrics are now available:
  - PdfEventProcessor 
    - `pdf_generation_total` (counter) — total number of successful PDF generations, labeled by `status` (`success`, `error`).
    - `pdf_generation_duration_seconds` (summary) — time taken to generate and upload PDF
      files.
    - `pdf_file_size_bytes` (summary) — distribution of PDF file sizes in bytes.
    - `pdf_generation_active` (gauge) — current number of active PDF generation processes.
  - SseBroadcaster
    - `sse_active_streams` (gauge) — current number of active SSE streams (connected clients).
    - `sse_events_delivered_total` (counter) — total number of SSE events successfully delivered to clients.
    - `sse_pending_buffer_hits_total` (counter) — total number of events retrieved from Redis pending buffer.
    - `sse_pending_buffer_writes_total` (counter) — total number of events written to Redis pending buffer.

- **Application OpenShift Configuration**: added new configuration to deploy application on OpenShift via command `mvn clean package -DskipTests=true -Dquarkus.openshift.deploy=true`.
- **Kubernetes Configuration**: yaml files for MinIO and Redis deployment

### Changed

- add complex document FreeMarker template and update related configurations
- update Redis image reference and add authentication instructions in README

## [1.3.0-SNAPSHOT] - Unreleased

### Added

- **Redis Pub/Sub as cross-instance SSE event transport** (`quarkus-redis-client`):  
  `SseBroadcaster` now subscribes to the Redis channels `pdf-generation-completed` and
  `pdf-generation-errors` at application startup. This allows any Quarkus instance in the
  cluster to deliver SSE events to its own connected clients, regardless of which instance
  originally processed the PDF generation request.

- **Pending-event buffer for late SSE connections** (`SseBroadcaster`):  
  If a Redis Pub/Sub message arrives before the SSE client has opened its connection, the
  raw JSON payload is stored in Redis with a configurable TTL (default 300 s) under the
  keys `pending:completed:{processId}` and `pending:error:{processId}`.  
  When `createStream` is subsequently called the buffered event is consumed atomically via
  `GETDEL`, guaranteeing exactly-once delivery even under concurrent `createStream` calls.

- **`GET /api/pdf/download/{processId}` endpoint** (`PdfResource`):  
  Allows clients to download the generated PDF directly from MinIO as a binary stream
  (`application/octet-stream`). The handler is annotated `@Blocking` to avoid stalling the
  Vert.x event loop during the synchronous MinIO I/O call. Returns `404 Not Found` when
  the requested object does not exist in the bucket.

- **Docker Compose full-stack deployment** (`src/main/docker/docker-compose.yml`):  
  Complete stack composed of Redis 7, MinIO, two Quarkus application instances (`app-1`,
  `app-2`), and an Nginx reverse-proxy / load balancer. Highlights:
  - `src/main/docker/nginx/nginx.conf` — Nginx configuration with SSE-aware proxy
    settings and reverse-proxy to the MinIO web console.
  - `src/main/docker/.env` — default environment variables for the Compose stack.
  - `minio-init` one-shot init container that automatically creates the PDF bucket on
    first run using the MinIO client (`mc`).
  - Health checks for every service: Quarkus `/q/health/live`, Redis `PING`,
    MinIO `/minio/health/live`.
  - Compatible with both `podman-compose` ≥ 1.0 and `podman compose` (Podman ≥ 4.7).

- **SmallRye Health** (`quarkus-smallrye-health`):  
  Added the SmallRye Health extension to expose the `/q/health/live` liveness probe
  consumed by the Docker Compose health check and by container orchestrators such as
  Kubernetes and Podman.

- **PlantUML cross-instance architecture diagram**
  (`src/docs/blog/article/resources/diagrams/cross-instance-async-flow.puml`):  
  Illustrates the asynchronous event flow between Quarkus instances via Redis Pub/Sub,
  including the pending-event buffer race-condition fix.

- **"Download PDF" request in the Postman Collection**
  (`src/main/postman/collection/postman_collection.json`):  
  Added a `GET /api/pdf/download/{processId}` request to verify end-to-end PDF download
  from MinIO.

- **`xml-apis` exclusions for fj-doc dependencies** (`pom.xml`):  
  Explicit exclusions of `xml-apis:xml-apis` added to `fj-doc-base`, `fj-doc-freemarker`,
  and `fj-doc-mod-fop` to prevent classpath conflicts with the XML APIs bundled in JDK 21.

### Changed

- **`PdfEventProcessor` — notification mechanism migrated to Redis Pub/Sub**:  
  Completion and error results are now published directly to the Redis channels
  (`completedDestination`, `errorsDestination`) via `ReactivePubSubCommands.publish()`.
  The `ReactivePubSubCommands` publisher is initialized once at startup
  (`@Observes StartupEvent`) and reused for all subsequent publishes. The Vert.x
  `EventBus` is no longer used for outbound notifications.

- **`SseBroadcaster` — redesigned for horizontal scaling**:  
  The broadcaster no longer listens on Vert.x EventBus addresses. It subscribes to Redis
  Pub/Sub channels at startup and unsubscribes cleanly at `@Observes ShutdownEvent`. The
  internal `ConcurrentHashMap` of `BroadcastProcessor` instances remains JVM-local;
  cross-instance delivery is fully delegated to Redis. Processor entries are cleaned up on
  stream cancellation (client disconnect), after successful event delivery, and on
  application shutdown to prevent resource leaks.

- **`README.md`** — updated with multi-instance architecture documentation, Docker Compose
  startup instructions for both Podman and Docker, and new entry-point URLs.

- **Blog article** (`src/docs/blog/article/come-gestire-task-asincroni-con-sse-quarkus.md`):  
  Updated with the advantages of Redis Pub/Sub over Kafka for SSE use cases, the
  horizontal-scaling architecture, the new cross-instance sequence diagram, and refreshed
  Postman Collection screenshots.

