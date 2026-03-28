# 📨 Apache Kafka — Event Streaming Deep Dive

> Kafka is integrated for **async analytics** decoupled from the video delivery pipeline. This document covers Kafka fundamentals, its role in StreamLite, and every interview question you may face.

---

## 1. What Is Apache Kafka?

**Apache Kafka** is a distributed, fault-tolerant **event streaming platform**. Originally built by LinkedIn, it's used by companies like Netflix, Uber, Twitter, and Airbnb for:
- Real-time event streaming (analytics, logging)
- Message queueing between microservices
- Change data capture (CDC)
- Stream processing pipelines

### Kafka vs Traditional Message Queues (RabbitMQ, ActiveMQ)

| Feature | Kafka | RabbitMQ |
|---|---|---|
| Storage | **Persistent on disk** — messages survive consumer failure | In-memory by default |
| Consumer model | **Pull-based** — consumers control their offset | Push-based |
| Replay | **Yes** — re-read past messages | No — messages deleted after consumption |
| Throughput | Millions of msg/sec | Thousands of msg/sec |
| Ordering | Per-partition ordering | Per-queue |
| Use case | Event streaming, analytics | Task queues, RPC |

---

## 2. Kafka Core Concepts

### Topics
A **Topic** is a named category/feed of messages. Like a database table but for events.
- Topics are **append-only logs** — messages are never deleted (until retention policy kicks in)
- Topics are divided into **Partitions** for scalability

### Partitions
A topic can be split into multiple **partitions** for parallelism:
```
Topic: "video-analytics"
    ├─ Partition 0: [msg1, msg4, msg7, ...]
    ├─ Partition 1: [msg2, msg5, msg8, ...]
    └─ Partition 2: [msg3, msg6, msg9, ...]
```
- Messages within a partition are **ordered**
- Different partitions can be processed by different consumers simultaneously

### Producers
Producers **push messages** to Kafka topics:
```java
// In StreamLite (conceptual — the Kafka producer is configured but 
// the actual sendMessage call would be in StreamingController):
kafkaTemplate.send("video-analytics", eventPayload);
```

### Consumers & Consumer Groups
Consumers **pull messages** from topics at their own pace:
- Each consumer tracks its **offset** (how far it has read)
- Multiple consumers in a **Consumer Group** share the load
- If a consumer fails, another in the group picks up from the last committed offset

### Brokers
A Kafka **Broker** is a server that stores and serves messages. A Kafka **cluster** has multiple brokers for fault tolerance.

### Offsets
An **offset** is a unique sequential ID for each message within a partition. Consumers commit their offset to track progress.

---

## 3. Why Kafka in StreamLite — The Restaurant Analogy

*From the existing `docs/whykafka.md`:*

**The Problem Without Kafka:**

```
Browser → GET /stream/{id}/playlist0.ts
                │
                ├─ Check Redis cache (fast)
                ├─ Read file from disk (if miss)
                ├─ LOG ANALYTICS TO DATABASE ← ⚠️ This slows everything down
                └─ Return video bytes
```

The analytics write (recording "user X watched segment Y at time Z") is a database operation. On every segment request, this would add 5-50ms of database latency to the video delivery path.

**The Solution With Kafka:**

```
Browser → GET /stream/{id}/playlist0.ts
                │
                ├─ Check Redis cache (fast)
                ├─ Read file from disk (if miss)
                ├─ kafkaTemplate.send("analytics", event)  ← INSTANT (~0.1ms, async)
                └─ Return video bytes
                
         ... (separately, at its own pace) ...
         
[Analytics Consumer App]
    ← polls Kafka topic "analytics"
    → processes each event
    → writes to database/data warehouse
```

The chef (StreamingController) shouts the order (fires Kafka event) and immediately goes back to cooking. The manager (consumer) processes it at their own pace.

---

## 4. Kafka Configuration in StreamLite

### `application.properties`
```properties
# Kafka Producer Settings
spring.kafka.producer.bootstrap-servers=localhost:9092
```

This single property registers a Kafka producer connected to the local Kafka broker on port 9092.

### Spring Kafka — How It Works

Spring Boot's `spring-kafka` dependency provides:
1. **`KafkaTemplate<K, V>`** — For sending messages (auto-configured based on `application.properties`)
2. **`@KafkaListener`** — For consuming messages (would be in a separate consumer)
3. **Auto-configuration** — Kafka producer is created automatically from properties

### The `pom.xml` Dependency
```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

Spring Kafka version is managed by Spring Boot's dependency management (compatible with Kafka 3.x).

---

## 5. What Kafka Events Would Be Emitted in StreamLite

Although the analytics consumer is not fully implemented in the current codebase, the design intent is:

### When a segment is served:
```json
{
  "event_type": "SEGMENT_SERVED",
  "video_id": "d3b4c5f0-abc1-4def-8901-234567890abc",
  "segment_name": "playlist3.ts",
  "timestamp": "2025-06-15T10:30:45Z",
  "cache_hit": true,
  "response_time_ms": 2
}
```

### When a video is uploaded:
```json
{
  "event_type": "VIDEO_UPLOADED",
  "video_id": "new-video-uuid",
  "title": "Tutorial Part 1",
  "uploader_id": "uploader123",
  "hls_path": "videos/output/playlist.m3u8",
  "timestamp": "2025-06-15T10:25:00Z"
}
```

### Consumer Application (separate service):
```java
@KafkaListener(topics = "video-analytics", groupId = "analytics-group")
public void consumeAnalyticsEvent(String event) {
    AnalyticsEvent parsed = objectMapper.readValue(event, AnalyticsEvent.class);
    analyticsRepository.save(parsed);  // Write to analytics DB / data warehouse
}
```

---

## 6. Kafka vs Redis Pub/Sub — Why Two Different Technologies?

| Aspect | Redis Pub/Sub (used for chat) | Kafka (used for analytics) |
|---|---|---|
| **Message persistence** | None — if no subscriber, message lost | Yes — stored on disk for days/weeks |
| **Replay** | No | Yes — replay from any offset |
| **Latency** | Sub-millisecond | ~5-10ms |
| **Use in StreamLite** | Live chat (ephemeral, low latency) | Analytics (must not lose data) |
| **Consumer state** | Stateless | Offset tracking (fault tolerant) |

**Why is chat on Redis and analytics on Kafka?**

For **live chat**: If a user sends a message and no other viewer is connected, it's OK if the message isn't delivered live — it's stored in Redis List (history). We want sub-millisecond delivery for the live experience. Redis Pub/Sub is perfect.

For **analytics**: If the analytics service crashes while processing, we need to replay the missed events. We cannot lose "who watched what" data. Kafka's persistent log is perfect — the consumer can restart and resume from where it crashed.

---

## 7. Kafka Architecture in Production Context

```
StreamLite App (Producer)
    │
    │ spring.kafka.producer.bootstrap-servers=localhost:9092
    ▼
[Kafka Broker - localhost:9092]
    │
    ├─ Topic: "video-analytics"
    │   ├─ Partition 0 → [events...]
    │   └─ Partition 1 → [events...]
    │
    └─ Topic: "video-uploads"
        └─ Partition 0 → [upload events...]

[Analytics Consumer App]        [Notification Service]
    │                                   │
    │ @KafkaListener                   │ @KafkaListener
    │ processes analytics              │ sends email on new upload
    └─ writes to PostgreSQL/BigQuery   └─ calls email API
```

---

## 8. Interview Questions on Kafka — Prepared Answers

**Q: What is Apache Kafka and what problem does it solve?**
> Kafka is a distributed event streaming platform. It solves the problem of **coupling** between producers and consumers of events. In StreamLite, the video delivery service (producer) fires an analytics event and immediately returns — it doesn't wait for the analytics database write. This keeps video delivery fast and means analytics processing failures don't affect streaming.

**Q: What is the difference between Kafka and a database?**
> A database stores the **current state** — you overwrite/update records. Kafka stores an **immutable log of events** — you append only, and every event is retained for the configured retention period. Kafka is like a write-ahead log (WAL) you can re-read.

**Q: What is a Kafka partition and why does it matter?**
> A partition is a shard of a topic. Messages within a partition are ordered. Multiple partitions allow: (1) parallel processing by multiple consumer instances, (2) throughput scaling — one partition can be processed per consumer in a group. More partitions = more parallelism but more overhead.

**Q: How does Kafka ensure messages aren't lost?**
> Producers can configure acknowledgment levels:
> - `acks=0`: Fire and forget (possible loss)
> - `acks=1`: Leader broker acknowledges (loss if leader crashes before replication)
> - `acks=all`: All replicas acknowledge (no loss, slower)
> For analytics in StreamLite, `acks=1` is acceptable; for financial data, `acks=all`.

**Q: What is Consumer Group and why does it matter?**
> A Consumer Group is a set of consumers that collectively consume a topic. Each partition is consumed by exactly one consumer in the group at a time. If you have 3 partitions and 3 consumers, each consumes one partition. If one consumer fails, Kafka rebalances — another consumer takes over the failed partition. Enables both parallelism and fault tolerance.

**Q: What is the difference between Kafka and RabbitMQ?**
> Key differences: (1) Kafka **retains messages** on disk (replay capability); RabbitMQ deletes after consumption. (2) Kafka is **pull-based** (consumers control pace); RabbitMQ is push-based. (3) Kafka offers much higher **throughput** (millions/sec vs thousands/sec). (4) Kafka is better for **event streaming/analytics**; RabbitMQ is better for **task queues/RPC**.

**Q: Why not just write analytics directly to PostgreSQL?**
> Direct writes create **tight coupling** and latency. If the analytics database is slow/down, video requests would also fail or be slow. Kafka decouples them: video delivery is always fast, and analytics writes happen asynchronously. This is the **CQRS + event-driven** pattern.
