# 📈 Futures EOS CDC Demo

**Futures EOS CDC Demo** is a comprehensive demonstration platform showcasing **Exactly-Once Semantics (EOS)** and **Change Data Capture (CDC)** patterns for high-reliability trading systems, running on a local Kubernetes cluster.

## System Architecture

```ascii
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                           Kubernetes Cluster (Kind)                                    │
├────────────────────────────────────────────────────────────────────────────────────────┤
│                         Signal Generation Layer                                        │
│                                                                                        │
│   ┌──────────────┐     ┌──────────────┐     ┌──────────────┐                           │
│   │  CronJob 1m  │     │  CronJob 5m  │     │ CronJob 15m  │                           │
│   │   (*/1 * *)  │     │  (*/5 * *)   │     │ (*/15 * *)   │                           │
│   └──────┬───────┘     └──────┬───────┘     └──────┬───────┘                           │
│          │                    │                    │                                   │
│          └────────────────────┴────────────────────┘                                   │
│                                    │                                                   │
│                                    ▼                                                   │
│                     ┌───────────────────────────────┐                                  │
│                     │      Signal Generator         │                                  │
│                     │       (Python/Kotlin)         │                                  │
│                     └──────────────┬────────────────┘                                  │
│                                    │                                                   │
│                                    ▼                                                   │
├────────────────────────────────────────────────────────────────────────────────────────┤
│                           Kafka Message Bus                                            │
│                                                                                        │
│   ┌────────────────────────────────────────────────────────────────────┐               │
│   │                    Kafka Cluster (3 brokers)                       │               │ 
│   │                                                                    │               │
│   │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │               │
│   │  │trading.signal│  │trading.signal│  │trading.signal│              │               │
│   │  │     .1m      │  │     .5m      │  │     .15m     │              │               │
│   │  │ partitions:3 │  │ partitions:3 │  │ partitions:3 │              │               │
│   │  │ replicas:3   │  │ replicas:3   │  │ replicas:3   │              │               │
│   │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘              │               │
│   │         │                 │                 │                      │               │
│   │         └─────────────────┴─────────────────┘                      │               │
│   │                           │                                        │               │
│   │                           ▼                                        │               │
│   │  ┌──────────────────────────────────────────────────────────┐      │               │
│   │  │                  Signal Processor                        │      │               │  
│   │  │               (Kafka Streams App)                        │      │               │
│   │  ├──────────────────────────────────────────────────────────┤      │               │
│   │  │ • EOS: EXACTLY_ONCE_V2                                   │      │               │
│   │  │ • isolation.level = read_committed                       │      │               │
│   │  │ • enable.idempotence = true                              │      │               │
│   │  │ • acks = all                                             │      │               │
│   │  │ • transactional.id = signal-processor-{uuid}             │      │               │
│   │  └──────────────────────────────────────────────────────────┘      │               │
│   │         │                                 │                        │               │
│   │   ┌─────▼────────┐                ┌───────▼───────┐                │               │
│   │   │ Consumer     │                │  Producer     │                │               │
│   │   │(Transactional)◄───┐     ┌────►|(Transactional)│                │               │
│   │   └─────┬────────┘    │     │     └───────┬───────┘                │               │
│   │         │             │     │             │                        │               │
│   │         ▼             │     │             ▼                        │               │
│   │  ┌─────────────┐      │     │    ┌───────────────────┐             │               │
│   │  │ State Store │      │     │    │ trading.decisions │             │               │
│   │  │  (RocksDB)  │      │     │    │   partitions:3    │             │               │
│   │  │ - Windowed  │      │     │    │   replicas:3      │             │               │
│   │  │ - Changelog │      │     │    └───────────────────┘             │               │
│   │  └─────────────┘      │     │                                      │               │
│   │                       │     │                                      │               │
│   │        ┌──────────────┴─────┴───────────────────┐                  │               │
│   │        │ Processing (Stateful Aggregation)      │                  │               │
│   │        └────────────────────┬───────────────────┘                  │               │
│   │                             │                                      │               │
│   │                             ▼                                      │               │
│   │         ┌──────────────────────────────────┐                       │               │
│   │         │   __transaction_state (internal) │                       │               │
│   │         │   (Stores transaction metadata)  │                       │               │
│   │         └──────────────────────────────────┘                       │               │
│   └────────────────────────────────────────────────────────────────────┘               │
│                                    │                                                   │
│                                    ▼                                                   │
├────────────────────────────────────────────────────────────────────────────────────────┤
│                         Order Management & CDC Pipeline                                │
│                                                                                        │
│   ┌──────────────────────┐       ┌──────────────────────┐       ┌───────────────────┐  │
│   │   Order Manager      │       │   Debezium Connector │       │    Kafka Topic    │  │
│   │ (Kafka Consumer)     ├──────►│   (Kafka Connect)    ├──────►│  trading.orders   │  │
│   │                      │       │                      │       │                   │  │
│   │ ┌──────────────────┐ │       │ • Outbox Pattern     │       │ ┌───────────────┐ │  │
│   │ │ BEGIN            │ │       │ • Reads WAL          │       │ │ Event Records │ │  │
│   │ │   INSERT orders  │ │       │ • Extracts outbox    │       │ └───────────────┘ │  │
│   │ │   INSERT outbox  │ │       │   records            │       │                   │  │
│   │ │ COMMIT           │ │       │ • Routes to topic    │       │                   │  │
│   │ └──────────────────┘ │       └──────────────────────┘       └───────────────────┘  │
│   │                      │                                                             │
│   │ ┌──────────────────┐ │                                                             │
│   │ │ PostgreSQL       │ │                                                             │
│   │ │ ┌──────────────┐ │ │                                                             │
│   │ │ │  app.orders  │ │ │                                                             │
│   │ │ └──────────────┘ │ │                                                             │
│   │ │ ┌──────────────┐ │ │                                                             │
│   │ │ │  app.outbox  │ │ │                                                             │
│   │ │ └──────────────┘ │ │                                                             │
│   │ └──────────────────┘ │                                                             │
│   └──────────────────────┘                                                             │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

## How EOS (Exactly-Once Semantics) Works

### 1. **Signal Generation Layer**

- **CronJobs** trigger signal generation at different intervals (1m, 5m, 15m)
- **Python/Kotlin generators** create synthetic trading signals
- Signals are published to Kafka with **idempotent producers**:
  ```properties
  enable.idempotence=true
  acks=all
  retries=3
  ```

### 2. **Kafka Infrastructure**

- **3-broker cluster** with replication factor 3 for fault tolerance
- **Transaction coordinator** manages distributed transactions
- **\_\_transaction_state** topic stores transaction metadata
- **Min in-sync replicas = 2** ensures durability

### 3. **Stream Processing Layer (Core EOS Implementation)**

The Signal Processor achieves EOS through multiple mechanisms:

#### **Configuration (EXACTLY_ONCE_V2)**

```kotlin
// libs/common-kafka/src/main/kotlin/com/trading/kafka/config/KafkaConfig.kt
put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2)
put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3)
put(StreamsConfig.producerPrefix(ProducerConfig.ACKS_CONFIG), "all")
put(StreamsConfig.producerPrefix(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG), true)
```

#### **Transaction Flow**

```
1. BEGIN TRANSACTION
   ├─> Read from input topics (isolation.level=read_committed)
   ├─> Process signals (aggregate, analyze)
   ├─> Update state store (RocksDB)
   ├─> Write to output topic
   └─> COMMIT/ABORT TRANSACTION (atomic)
```

#### **Key Components**:

**a. Transactional Consumer**

- Reads only committed messages
- Maintains consumer group offsets in transactions
- Automatic offset management

**b. Stateful Processing**

- **RocksDB state store** for aggregations
- **Changelog topics** for state recovery
- **Windowed operations** (20-signal windows)

**c. Transactional Producer**

- Atomic writes to output topics
- Unique `transactional.id` per instance
- Automatic transaction recovery on failure

### 4. **CDC Pipeline (Outbox Pattern)**

For order processing (future implementation):

```sql
BEGIN;
  INSERT INTO app.orders (id, client_order_id, symbol, side, qty, price, status, created_at, updated_at)
  VALUES (...);

  INSERT INTO app.outbox (event_id, aggregate_type, aggregate_id, type, payload, occurred_at)
  VALUES (...);
COMMIT;
```

**Debezium Configuration**:

```yaml
transforms: outbox
transforms.outbox.type: io.debezium.transforms.outbox.EventRouter

# Field mappings (match app.outbox schema)
transforms.outbox.table.field.event.id: event_id
transforms.outbox.table.field.event.key: aggregate_id
transforms.outbox.table.field.event.payload: payload
transforms.outbox.table.field.event.type: type
transforms.outbox.table.field.event.aggregate.id: aggregate_id
transforms.outbox.table.field.event.aggregate.type: aggregate_type

# Route to a single topic
transforms.outbox.route.by.field: aggregate_type
transforms.outbox.route.topic.replacement: trading.orders

# Add selected columns as headers
transforms.outbox.table.fields.additional.placement: |
  aggregate_id:header:aggregate_id,
  type:header:event_type,
  event_id:header:event_id,
  occurred_at:header:occurred_at
```

Note: We omit an explicit outbox event timestamp mapping. Debezium will use the record `ts_ms` as the event timestamp. If you prefer mapping from a column, add an `INT64` epoch-millis column (e.g., `occurred_at_ms`) and set `transforms.outbox.table.field.event.timestamp` accordingly.

## EOS Configuration Details

### **Kafka Broker Settings**

```yaml
# deploy/strimzi/kafka/kafka.yaml
config:
  transaction.state.log.replication.factor: 3
  transaction.state.log.min.isr: 2
  min.insync.replicas: 2
  unclean.leader.election.enable: false
```

### **Signal Processor Settings**

```kotlin
// Exactly-Once v2 (Better performance than v1)
processing.guarantee: exactly_once_v2
// Read only committed records
isolation.level: read_committed
// State store replication
replication.factor: 3
// Commit interval for transactions
commit.interval.ms: 1000
// Enable idempotent writes
enable.idempotence: true
```

### **Producer Settings**

```kotlin
// All replicas must acknowledge
acks: all
// Enable idempotent producer
enable.idempotence: true
// Retry configuration
retries: 3
max.in.flight.requests.per.connection: 5
```

## Prerequisites

- Docker, `kubectl`, `helm`, `kind`, `just`, and JDK 21
- Internet access to pull base images
- Optional: NGINX Ingress and local DNS entries for `connect.local`, `kafka-ui.local` (or use port-forwarding)

## Quick Start

### **0. Local Registry (first time only)**

```bash
just reg-up
```

Ensures a local registry at `localhost:9001` used by Kind as a mirror.

### **1. Bootstrap the Cluster**

```bash
just up
```

Creates Kind cluster, deploys Kafka, PostgreSQL, and all components.

### **2. Build & Deploy Apps**

```bash
just signal-generator    # builds image with Jib and applies CronJobs
just signal-processor    # builds image with Jib and deploys processor
just order-manager       # builds image with Jib and deploys order manager
```

### **3. Monitor the Pipeline**

```bash
# Watch signals being generated
just tail-sig-1m

# Watch decisions being made (If failed, retry after waiting for a while)
just tail-decisions

# Access Kafka UI
just kafka-ui-pf
  # Open http://localhost:8080
  ```

### **4. Verify Connect + Outbox**

```bash
# Ensure Connect and the outbox connector are applied (already done by `just up`)
just connect
just connector

# Option A: If using Ingress and hosts entry
curl http://connect.local/connector-plugins
curl http://connect.local/connectors/pg-outbox/status

# Option B: Port-forward for REST access
kubectl -n trading port-forward svc/trading-connect-connect-api 8083:8083 &
curl http://localhost:8083/connector-plugins
curl http://localhost:8083/connectors/pg-outbox/status
```

### **5. Verify Orders and Outbox**

- DB tables:
  - `just pg-orders`
  - `just pg-outbox`
- Kafka topic:
  - `kubectl -n trading exec -it kcat -- sh -lc "kcat -b trading-kafka-bootstrap:9092 -C -t trading.orders -q -o -10 -e"`

## Demonstrating EOS

### **Simple Demo**

```bash
just eos-demo
```

This will:

1. Inject 5 identical signals
2. Verify only 1 decision is produced
3. Demonstrate deduplication

### **Check for Duplicates**

```bash
just check-duplicates
```

Shows duplicate count in topics.

### **Monitor EOS Metrics**

```bash
just eos-logs
```

Shows transaction commits and EOS operations.

## EOS Guarantees in Action

### **Without EOS:**

```
Signal → Processor → Crash → Signal Reprocessed → Duplicate Decision ❌
Signal → Processor → Decision → Crash before ACK → Signal Lost ❌
```

### **With EOS:**

```
Signal → Begin Txn → Process → Commit Txn → Decision (Exactly Once) ✅
Signal → Begin Txn → Process → Crash → Txn Aborted → Retry → Decision (Exactly Once) ✅
```

## Project Structure

```
futures-eos-cdc/
├── apps/
│   ├── signal-generator/        # Kotlin signal generator
│   ├── signal-processor/        # Kafka Streams EOS processor
│   └── order-manager/           # Consumes decisions, writes Orders + Outbox
├── libs/
│   ├── common-model/           # Shared DTOs
│   └── common-kafka/           # Kafka config with EOS settings
├── deploy/
│   ├── strimzi/               # Kafka infrastructure
│   │   ├── kafka/             # Kafka cluster & topics
│   │   ├── connects/          # Kafka Connect configs
│   │   ├── connectors/        # Connector definitions
│   │   ├── monitoring/        # Kafka UI
│   │   └── tools/             # Debug tools (kcat)
│   ├── postgres/              # PostgreSQL with outbox
│   ├── signal-generator/      # K8s manifests
│   ├── signal-processor/      # K8s manifests
│   └── eos-demo/             # EOS testing tools
├── scripts/
│   ├── eos-demo.sh           # Simple EOS demonstration
│   └── verify-eos.sh         # Comprehensive EOS verification
└── justfile                   # Task automation
```

## Key Takeaways

1. **EOS prevents data loss and duplicates** in distributed systems
2. **Transactional processing** ensures atomic read-process-write
3. **State stores** maintain consistency across failures
4. **Idempotent producers** handle retries safely
5. **CDC with outbox pattern** extends EOS to databases and emits `trading.orders`

## Trade-offs

- **Latency**: EOS adds ~10-100ms latency due to transactions
- **Throughput**: ~20-30% lower than at-least-once delivery
- **Complexity**: Requires careful configuration and monitoring
- **Storage**: Transaction logs and state stores increase storage needs

## Order Manager

- Purpose: consumes `trading.decisions` and atomically persists domain state and outbox events.
- Atomic writes: one transaction writes `app.orders` and `app.outbox` to guarantee consistency.
- Confidence threshold: env `CONFIDENCE_THRESHOLD` controls when orders are created; default `0.65`.
- Idempotency: unique `client_order_id` on `app.orders`; downstream idempotency via outbox `event_id`.
- Configuration: `KAFKA_BOOTSTRAP`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `ORDER_MANAGER_GROUP_ID`.

## Outbox Pattern

- Schema: `app.outbox(event_id UUID PK, aggregate_type TEXT, aggregate_id UUID, type TEXT, payload JSONB, occurred_at TIMESTAMPTZ, occurred_at_ms BIGINT GENERATED)`.
- EventRouter: maps columns to logical fields; routes by `aggregate_type` to `trading.${routedByValue,,}s`.
- Message key: `aggregate_id` ensures per-aggregate ordering in Kafka.
- Headers: `event_id`, `aggregate_id`, `event_type`, `occurred_at` added for easy consumption.

## Troubleshooting

- Topics not created:
  - Ensure `deploy/strimzi/kafka/topics.yaml` labels use `strimzi.io/cluster: trading` (matches the Kafka CR name).
  - Reapply: `just topics`.
- Connect REST not reachable:
  - Use port-forward: `kubectl -n trading port-forward svc/trading-connect-connect-api 8083:8083`.
  - Or add `connect.local` entry and ensure NGINX Ingress is installed.
- Outbox connector task failing:
  - Timestamp mapping error: omit `transforms.outbox.table.field.event.timestamp` or use an `INT64` epoch-millis column (e.g., `occurred_at_ms`).
  - Topic routing error (RegexRouter): use a constant replacement (e.g., `trading.orders`) or a valid regex replacement.
- Outbox timestamp column:
  - `occurred_at_ms` is a generated column; the application must not insert it.
  - If upgrading an existing DB, add it: `ALTER TABLE app.outbox ADD COLUMN occurred_at_ms BIGINT GENERATED ALWAYS AS (((EXTRACT(EPOCH FROM occurred_at) * 1000))::BIGINT) STORED;`
- Images won’t pull:
  - Run `just reg-up`, then rebuild with `just signal-generator` / `just signal-processor`.

## Security Notes

- The Connect NetworkPolicy and Ingress are permissive for local demos.
- For production, restrict IP ranges, add auth (basic/OAuth) or mTLS, or keep Connect internal and access via Kafka UI/port-forwarding.

## Future Plans

- Order Executor service that consumes `trading.orders` and calls an exchange; idempotent via `event_id` header.
- Observability: add JMX Exporter and Grafana dashboards for transactions/commits/aborts.
- Hardening: publish Connect image to local registry; add NetworkPolicies for least privilege.
- CI/CD: Gradle wrapper + Actions to build, push, validate YAML, and run `just eos-verify` smoke tests.

## References

- [Kafka Exactly Once Semantics](https://kafka.apache.org/documentation/#semantics)
- [Kafka Streams EOS](https://docs.confluent.io/platform/current/streams/concepts.html#exactly-once-semantics-eos)
- [Debezium Outbox Pattern](https://debezium.io/documentation/reference/transformations/outbox-event-router.html)

## License

MIT - Use freely for learning and prototyping.
