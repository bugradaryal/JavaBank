# bank-platform

An event-driven core banking backend built as three microservices. Money moves between accounts through an orchestrated saga over Kafka, with a transactional outbox, idempotent processing, reconciliation, observability, load tests and chaos tests.

**Stack:** Java 21, Spring Boot 3.3, Spring Security (JWT), Spring Data JPA, Flyway, PostgreSQL 16, Apache Kafka, Micrometer / Prometheus / Grafana, OpenTelemetry / Tempo, Testcontainers, k6, Docker.

| Service | Port | Database | Responsibility |
|---|---|---|---|
| account-service | 8081 | accountdb | customers, accounts, balances, ledger, login (JWT) |
| transfer-service | 8082 | transferdb | transfer API, saga orchestrator, cross-service reconciliation |
| notification-service | 8083 | notificationdb | consumes transfer results and creates notifications |
| common | - | - | shared contracts (commands/replies/events), outbox, JWT, error model |

Each service owns its database: no shared tables, no foreign keys across services. Services talk to each other only through Kafka (plus one admin-only HTTP call used by reconciliation).

## Transfer saga

transfer-service is the orchestrator:

```
POST /api/transfers -> transfer row (STARTED) + DEBIT command        (one transaction, outbox)
account-service:      source account -> CLEARING account  -> reply
transfer-service:     DEBITED  + CREDIT command
account-service:      CLEARING account -> destination     -> reply
transfer-service:     COMPLETED + TransferEvent -> notification-service
```

If the destination rejects the credit (closed account, currency mismatch, ...): `COMPENSATING` -> REFUND command (CLEARING -> source) -> `COMPENSATED`.
If the first step is rejected (insufficient funds): `FAILED`, no money has moved.

**Why orchestration instead of choreography:** step order and compensation rules live in one place, and "where is this transfer right now?" is answered by a single column (`saga_state`). Stuck sagas can be found and recovered. The cost is a central component, but its state is in the database, so after a crash it resumes where it stopped.

**Money is never "in the air":** a clearing (suspense) account per currency means the sum of all balances is 0 at every commit. When all sagas finish, the clearing balance is 0 as well. `GET /api/admin/ledger-summary` (account-service) shows both.

## Reliability mechanisms

- **Transactional outbox:** the state change and the outgoing message are written in the same DB transaction (no dual-write problem). A relay publishes them to Kafka with at-least-once delivery.
- **Idempotent command handling:** account-service applies each command once, keyed by `operation_log` (`transferId:STEP`); a duplicate gets the recorded reply again.
- **Idempotent consumers** (notification-service) and an HTTP **`Idempotency-Key`** for transfer creation.
- **Recovery job:** if a step gets no reply within `app.saga.step-timeout-ms`, the orchestrator re-sends that step's command.
- **State machine:** unexpected, duplicate or stale replies are ignored.
- **Retry + dead letter topics:** failing messages are retried with exponential backoff (1 s up to 32 s, about a minute in total), then published to `<topic>.DLT`.
- **Pessimistic locking** with accounts locked in id order, so concurrent transfers cannot deadlock.

## Running

```
docker compose up -d                 # postgres (3 databases), kafka, prometheus, grafana, tempo
mvn clean verify                     # unit tests + integration tests (Docker required)
mvn -pl account-service spring-boot:run
mvn -pl transfer-service spring-boot:run
mvn -pl notification-service spring-boot:run
```

Swagger UI: `http://localhost:8081/swagger-ui.html` (account), `8082` (transfer), `8083` (notification).
Login: `POST /api/auth/login` (admin `Admin123!`, teller `Teller123!`; override via environment in any real deployment). The token is valid on all three services.
Transfers: `POST /api/transfers` (requires an `Idempotency-Key` header; `?wait=10000` waits for the outcome); read the result with `GET /api/transfers/{id}`.

To run the services themselves as containers (used by the chaos tests):

```
docker compose --profile apps up -d --build
# optional, so Prometheus scrapes the containers:  PROM_CONFIG=./docker/prometheus/prometheus-containers.yml
```

The root `Dockerfile` is a single multi-stage file (`--build-arg SERVICE=account-service|transfer-service|notification-service`). Kafka exposes two listeners: `kafka:19092` for containers and `localhost:9092` for the host.

## Tests

`integration-tests` runs all three services in one JVM against real PostgreSQL and Kafka (Testcontainers).

`SagaSystemIT`:
- happy path and notifications
- insufficient funds (FAILED, no money moves)
- closed destination account -> compensation (COMPENSATED, money returns)
- **account-service crashes mid-saga** -> money is safe in clearing, saga completes after restart
- **transfer-service crashes between steps** -> resumes from the persisted state
- a stuck step is re-sent; duplicate commands are applied **once**
- the same `Idempotency-Key` does not start a second saga
- spending from someone else's account is rejected

`ReconciliationIT`: a healthy system has no findings; deliberately tampered data is detected.

After every test: sum of balances = 0 and clearing = 0. Unit tests cover the saga state machine, the outbox relay and the reconciliation evaluator.

## Metrics

Prometheus (`http://localhost:9090`) and Grafana (`http://localhost:3000`, admin/admin, "Bank Platform" dashboard provisioned) start with `docker compose up -d`. Prometheus scrapes `/actuator/prometheus` of each service every 10 s.

| Metric | Meaning |
|---|---|
| `ledger_total_balance` | Sum of all balances. Must always be 0 |
| `ledger_clearing_balance{currency}` | Money in flight. 0 once sagas finish |
| `saga_in_flight{state}` | Transfers per state |
| `saga_completed_total{outcome,type}` | completed / failed / compensated |
| `saga_duration_seconds` | Time from transfer start to outcome (histogram) |
| `saga_step_retries_total{step}` | Steps re-sent by the recovery job |
| `account_commands_total{step,result}` | success / rejected / duplicate / error |
| `outbox_pending`, `outbox_oldest_pending_age_seconds` | Outbox backlog |
| `outbox_published_total`, `outbox_publish_failures_total` | Relay counters |
| `kafka_dlt_published_total{source_topic}` | Messages sent to a dead letter topic |
| `notification_events_total{result}` | processed / duplicate |
| `reconciliation_*` | Reconciliation violations and open findings |

Also available: HTTP histograms, JVM, Hikari pool, Kafka consumer lag.

**Alert rules** (`docker/prometheus/alerts.yml`): LedgerInvariantBroken, ClearingNotDrained, SagaStuck, SagaRetriesHigh, SagaCompensationSpike, DeadLetterMessages, OutboxBacklog, OutboxPublishFailures, ReconciliationMismatch, ServiceDown, HighServerErrorRate, SlowRequests, DbConnectionPoolSaturated. They are visible in the Prometheus UI (no notification channel is wired up).

## Distributed tracing

One transfer starts as an HTTP request and travels through three services over several Kafka hops, yet shows up as a single trace:

```
POST /api/transfers (transfer-service) -> outbox publish -> account-service: debit -> outbox publish
  -> transfer-service: reply -> outbox publish -> account-service: credit -> ... -> notification-service
```

- **Stack:** Micrometer Tracing -> OpenTelemetry -> OTLP/HTTP -> Tempo -> Grafana (OTLP on 4318, Tempo API on 3200).
- **Across Kafka:** producers write the W3C `traceparent` into message headers; consumers continue the same trace.
- **Across the outbox:** messages are published later by a different thread, which would normally break the trace. The `outbox_event.traceparent` column stores the context with the message, and the relay opens its "outbox publish" span as a child of it.
- **Business tags:** spans carry `transfer.id`, `saga.state`, `saga.step`, `command.result`. In Grafana -> Explore -> Tempo: `{ span.transfer.id = "42" }` finds the whole trace of one transfer.
- **Log correlation:** every log line carries `[service, traceId, spanId]`.
- **Sampling:** `TRACING_SAMPLING=1.0` in dev, `0.05-0.1` suggested for production, `0.0` in integration tests.

## Reconciliation

Tests show that no money is lost in known scenarios; reconciliation keeps asking, on a live system, whether the money is still right. Two layers:

**1) account-service, its own books** (`LedgerReconciliationService`, every 5 minutes and `GET /api/admin/reconciliation`), inside one REPEATABLE READ snapshot:

| Check | Meaning |
|---|---|
| `TOTAL_NOT_ZERO` | Sum of all balances is not 0 |
| `BALANCE_LEDGER_MISMATCH` | An account balance differs from its ledger CREDIT - DEBIT total |
| `ENTRY_WITHOUT_OPERATION` | Ledger entry without a successful operation record |
| `OPERATION_ENTRY_COUNT` | A successful operation did not produce exactly 2 ledger entries |
| `OPERATION_NOT_BALANCED` | An operation's ledger entries do not sum to zero |

**2) Cross-service** (`ReconciliationService` in transfer-service, every minute): the outcome of each finished transfer is compared with account-service's operation records.

| Transfer outcome | Expected in account-service |
|---|---|
| COMPLETED | DEBIT succeeded, CREDIT succeeded, no REFUND |
| COMPENSATED | DEBIT succeeded, CREDIT rejected, REFUND succeeded |
| FAILED | DEBIT rejected, no CREDIT, no REFUND |

Mismatches are written to `reconciliation_finding` and stay open until a human calls `POST /api/admin/reconciliation/findings/{id}/resolve`. The most dangerous kind is `FAILED_BUT_DEBITED`. Each transfer is checked once (`reconciled_at`); if account-service is unreachable the round is skipped and retried. `POST /api/admin/reconciliation/run` triggers a round manually.

The service-to-service call uses a short-lived ADMIN token minted from the shared JWT key (a demo shortcut; use mTLS or a service account in reality).

## Load tests

k6 scenarios live in `load-tests/` (steady, ramp, spike, hot account, idempotency). At the end of every run the test verifies automatically that the sum of balances is 0, the clearing balance is 0, no sagas are pending, the ledger checks pass and reconciliation is clean. If any of these fails, the test fails. See `load-tests/README.md`.

## Chaos tests

The chaos scripts run a steady k6 load against the containerized services and kill or freeze something in the middle; the k6 teardown then verifies the money invariants.

```
docker compose --profile apps up -d --build
cd chaos
.\chaos.ps1 -Fault kill-account        # Linux/macOS: ./chaos.sh kill-account
```

Scenarios: `kill-account`, `kill-transfer`, `kill-notification`, `kill-all`, `rolling`, `pause-kafka`, `stop-kafka`, `restart-postgres`. Expected behavior per scenario: [chaos/README.md](chaos/README.md).
With `CHAOS=1` the latency and throughput thresholds are disabled; `invariant_violations`, `reconciliation_findings` and `idempotency_violations` stay enforced.

## Known limitations

- If a reply never arrives, the saga retries forever (no abort, no fencing of late replies).
- The clearing account is a single row per currency, so it is a hot row under heavy load.
- Compensation (refund) credits the source account even if it has been closed since.
- JWT uses a shared HMAC key; use RS256 and a secret manager in production.
- Actuator endpoints are unauthenticated; keep them on an internal network.
- Alerts only appear in Prometheus (no Alertmanager/Slack); no central log aggregation, only traceId correlation.
- Testcontainers may have API incompatibilities with very new Docker versions (29+); upgrade Testcontainers if that happens.
