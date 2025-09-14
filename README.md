# 📈 Futures EOS CDC Demo

**Futures EOS CDC Demo** is a comprehensive demonstration platform showcasing **Exactly-Once Semantics (EOS)** and **Change Data Capture (CDC)** patterns for high-reliability trading systems, running on a local Kubernetes cluster.

## System Architecture

```ascii
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           Kubernetes Cluster (Kind)                                 │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                         Signal Generation Layer                                     │
│                                                                                     │
│   ┌──────────────┐     ┌──────────────┐     ┌──────────────┐                        │
│   │  CronJob 1m  │     │  CronJob 5m  │     │ CronJob 15m  │                        │
│   │   (*/1 * *)  │     │  (*/5 * *)   │     │ (*/15 * *)   │                        │
│   └──────┬───────┘     └──────┬───────┘     └──────-┬──────┘                        │
│          │                    │                     │                               │
│          └────────────────────┴─────────────────────┘                               │
│                                    │                                                │
│                                    ▼                                                │
│                     ┌───────────────────────────────┐                               │
│                     │      Signal Generator         │                               │
│                     │       (Python/Kotlin)         │                               │
│                     └──────────────┬────────────────┘                               │
├────────────────────────────────────┼──────────────────────────────────────────────--┤
│                           Kafka Message Bus                                         │
│                                                                                     │
│   ┌────────────────────────────────────────────────────────────────────┐            │
│   │                    Kafka Cluster (3 brokers)                       │            │
│   │                                                                    │            │
│   │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │            │
│   │  │trading.signal│  │trading.signal│  │trading.signal│              │            │
│   │  │     .1m      │  │     .5m      │  │     .15m     │              │            │
│   │  │ partitions:3 │  │ partitions:3 │  │ partitions:3 │              │            │
│   │  │ replicas:3   │  │ replicas:3   │  │ replicas:3   │              │            │
│   │  └──────┬───────┘  └─────-─┬──────┘  └──────┬───────┘              │            │
│   │         │                  │                │                      │            │
│   │         └──────────────────┴────────────────┘                      │            │
│   │                            │                                       │            │
│   │                            ▼                                       │            │
│   │         ┌──────────────────────────────────┐                       │            │
│   │         │   __transaction_state (internal) │                       │            │
│   │         │   (Stores transaction metadata)  │                       │            │
│   │         └──────────────────────────────────┘                       │            │
│   └────────────────────────────────────────────────────────────────────┘            │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                        Stream Processing Layer                                      │
│                                                                                     │
│   ┌────────────────────────────────────────────────────────────────────┐            │
│   │                   Signal Processor (Kafka Streams)                 │            │
│   │                                                                    │            │
│   │  ┌──────────────────────────────────────────────────────────┐      │            │
│   │  │              EOS Configuration (EXACTLY_ONCE_V2)         │      │            │
│   │  ├──────────────────────────────────────────────────────────┤      │            │
│   │  │ • processing.guarantee = exactly_once_v2                 │      │            │
│   │  │ • isolation.level = read_committed                       │      │            │
│   │  │ • enable.idempotence = true                              │      │            │
│   │  │ • acks = all                                             │      │            │
│   │  │ • transactional.id = signal-processor-{uuid}             │      │            │
│   │  └──────────────────────────────────────────────────────────┘      │            │
│   │                                                                    │            │
│   │  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐          │            │
│   │  │   Consumer   │───▶│  Processing  │───▶│   Producer   │          │            │
│   │  │  (Transact.) │    │   (Stateful) │    │  (Transact.) │          │            │
│   │  └──────────────┘    └──────┬───────┘    └──────┬───────┘          │            │
│   │                             │                   │                  │            │
│   │                             ▼                   ▼                  │            │
│   │                   ┌──────────────────┐  ┌────────────────┐         │            │
│   │                   │   State Store    │  │  trading.      │         │            │
│   │                   │   (RocksDB)      │  │  decisions     │         │            │
│   │                   │   - Windowed     │  │  partitions: 3 │         │            │
│   │                   │   - Changelog    │  │  replicas: 3   │         │            │
│   │                   └──────────────────┘  └────────────────┘         │            │
│   └────────────────────────────────────────────────────────────────────┘            │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                         CDC Pipeline (Future Extension)                             │
│                                                                                     │
│   ┌──────────────┐         ┌──────────────┐         ┌──────────────┐                │
│   │  PostgreSQL  │────────▶│   Debezium   │────────▶│    Kafka     │                │
│   │              │  WAL    │   Connector  │         │              │                │
│   │ ┌──────────┐ │         │              │         │ ┌──────────┐ │                │
│   │ │  orders  │ │         │ • Outbox     │         │ │ trading. │ │                │
│   │ └──────────┘ │         │   Pattern    │         │ │  orders  │ │                │
│   │ ┌──────────┐ │         │ • CDC via    │         │ └──────────┘ │                │
│   │ │  outbox  │ │         │   WAL        │         │              │                │
│   │ └──────────┘ │         │              │         │              │                │
│   └──────────────┘         └──────────────┘         └──────────────┘                │
└─────────────────────────────────────────────────────────────────────────────────────┘
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
  INSERT INTO orders (client_order_id, ...) VALUES (...);
  INSERT INTO outbox (aggregate_id, event_type, payload) VALUES (...);
COMMIT;
```

**Debezium Configuration**:

```yaml
transforms: outbox
transforms.outbox.type: io.debezium.transforms.outbox.EventRouter
transforms.outbox.table.field.event.id: id
transforms.outbox.table.field.event.key: aggregate_id
transforms.outbox.table.field.event.payload: payload
transforms.outbox.route.topic.replacement: trading.${routedByValue}
```

Note: This repo maps the Outbox SMT timestamp to a generated epoch-millis column `occurred_at_ms`. The database init script creates it as a stored generated column from `occurred_at`.
If your database is already running without this column, apply the `ALTER TABLE` manually or recreate the cluster.

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

### **2. Build & Deploy Signal Generator & Processor**

```bash
just signal-generator    # builds image with Jib and applies CronJobs
just signal-processor    # builds image with Jib and deploys processor
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
│   └── signal-processor/        # Kafka Streams EOS processor
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
5. **CDC with outbox pattern** extends EOS to databases

## Trade-offs

- **Latency**: EOS adds ~10-100ms latency due to transactions
- **Throughput**: ~20-30% lower than at-least-once delivery
- **Complexity**: Requires careful configuration and monitoring
- **Storage**: Transaction logs and state stores increase storage needs

## Troubleshooting

- Topics not created:
  - Ensure `deploy/strimzi/kafka/topics.yaml` labels use `strimzi.io/cluster: trading` (matches the Kafka CR name).
  - Reapply: `just topics`.
- Connect REST not reachable:
  - Use port-forward: `kubectl -n trading port-forward svc/trading-connect-connect-api 8083:8083`.
  - Or add `connect.local` entry and ensure NGINX Ingress is installed.
- Outbox SMT timestamp error:
  - Add the generated column on a running DB: `ALTER TABLE app.outbox ADD COLUMN occurred_at_ms BIGINT GENERATED ALWAYS AS (((EXTRACT(EPOCH FROM occurred_at) * 1000))::BIGINT) STORED;`
- Images won’t pull:
  - Run `just reg-up`, then rebuild with `just signal-generator` / `just signal-processor`.

## Security Notes

- The Connect NetworkPolicy and Ingress are permissive for local demos.
- For production, restrict IP ranges, add auth (basic/OAuth) or mTLS, or keep Connect internal and access via Kafka UI/port-forwarding.

## Future Plans

- End-to-end Orders CDC using the outbox pattern publishing to `trading.orders`.
- Observability: add JMX Exporter and Grafana dashboards for transactions/commits/aborts.
- Hardening: publish Connect image to local registry instead of `ttl.sh`; add NetworkPolicies for least privilege.
- CI/CD: Gradle wrapper + Actions to build, push, validate YAML, and run `just eos-verify` smoke tests.

## References

- [Kafka Exactly Once Semantics](https://kafka.apache.org/documentation/#semantics)
- [Kafka Streams EOS](https://docs.confluent.io/platform/current/streams/concepts.html#exactly-once-semantics-eos)
- [Debezium Outbox Pattern](https://debezium.io/documentation/reference/transformations/outbox-event-router.html)

## License

MIT - Use freely for learning and prototyping.

