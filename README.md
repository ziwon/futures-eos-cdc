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

## Quick Start

### **1. Bootstrap the Cluster**

```bash
just up
```

Creates Kind cluster, deploys Kafka, PostgreSQL, and all components.

### **2. Deploy Signal Generator & Processor**

```bash
just signal-generator
just signal-processor
```

### **3. Monitor the Pipeline**

```bash
# Watch signals being generated
just tail-sig-1m

# Watch decisions being made
just tail-decisions

# Access Kafka UI
just kafka-ui-pf
# Open http://localhost:8080
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

## References

- [Kafka Exactly Once Semantics](https://kafka.apache.org/documentation/#semantics)
- [Kafka Streams EOS](https://docs.confluent.io/platform/current/streams/concepts.html#exactly-once-semantics-eos)
- [Debezium Outbox Pattern](https://debezium.io/documentation/reference/transformations/outbox-event-router.html)

## License

MIT - Use freely for learning and prototyping.

