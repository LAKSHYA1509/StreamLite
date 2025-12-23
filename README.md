# StreamLite

**A Hybrid Live + VOD Streaming Platform for Resource-Constrained Systems**

StreamLite is a research-driven hybrid video streaming platform that unifies **Live Streaming** and **Video-on-Demand (VOD)** within a single, scalable architecture designed to operate under **severely constrained hardware resources**.
The project explores how modern backend engineering principles—event-driven design, caching, and modular services—can mitigate infrastructure limitations while maintaining system stability and performance.

This repository contains the **architecture, implementation, and research artefacts** developed as part of an academic exploration into scalable streaming systems.

---

## 🚀 Key Objectives

* Design a **unified Live + VOD streaming pipeline** without fragmented workflows
* Simulate **high-concurrency streaming** on a single, laptop-grade machine
* Apply **distributed systems principles** (decoupling, async processing, caching)
* Study **performance bottlenecks** in constrained environments
* Produce a **research-backed reference architecture** for low-cost streaming platforms

---

## 🧠 Core Features

* **Hybrid Streaming Support**
  Supports both pre-segmented VOD playback and live streaming using a single delivery pipeline.

* **Event-Driven Backend Architecture**
  Uses Apache Kafka to asynchronously process analytics and interaction events without blocking video delivery.

* **Low-Latency Live Ingestion**
  Live streams are ingested via RTMP and segmented in near real time using FFmpeg without expensive transcoding.

* **Redis-Based CDN Simulation**
  Frequently accessed video segments are cached in Redis to minimize disk I/O and improve throughput.

* **Real-Time Interaction**
  WebSocket-based chat with Redis Pub/Sub ensures low-latency viewer interaction during streams.

* **Research-Oriented Observability**
  Designed for performance measurement and scalability analysis under simulated load.

---

## 🏗️ System Architecture (High Level)

StreamLite is built around a **decoupled architecture**:

* **Data Plane**
  Handles high-bandwidth video delivery (HLS playlists and segments)

* **Control / Event Plane**
  Manages authentication, metadata, chat, and analytics asynchronously

This separation ensures that spikes in chat or analytics traffic do **not** degrade streaming performance.

---

## 🛠️ Technology Stack

### Backend

* **Java 17**
* **Spring Boot** (REST APIs, WebSockets, Security)
* **Apache Kafka** (event streaming & analytics)
* **Redis** (caching + Pub/Sub)
* **PostgreSQL** (metadata & user data)

### Streaming

* **NGINX RTMP Module** (live ingestion)
* **FFmpeg** (HLS segmentation)
* **HLS** (Live + VOD delivery)

### Infrastructure & Tooling

* **Docker & Docker Compose**
* **Prometheus & Grafana** (monitoring)
* **Locust** (load testing)

### Frontend

* **React**
* **HLS.js**

---

## 📊 Research Focus

This project is not just an application—it is a **systems research prototype**.

Key research questions explored include:

* How far can **resource-constrained hardware** be pushed using modern backend design?
* What bottlenecks emerge first: CPU, disk I/O, or networking?
* How effective is **Redis caching** in simulating CDN behavior?
* How does **event decoupling via Kafka** improve system stability under load?

The answers are documented in the accompanying research paper.

---

## 📁 Repository Structure

```text
streamlite/
├── backend/
│   ├── api-gateway/
│   ├── chat-service/
│   ├── analytics-service/
│
├── streaming/
│   ├── nginx-rtmp/
│   ├── ffmpeg-scripts/
│
├── frontend/
│   ├── react-app/
│
├── infra/
│   ├── docker-compose.yml
│   ├── prometheus/
│   └── grafana/
│
├── research/
│   ├── StreamLite_Paper.pdf
│   └── Architecture_Diagrams/
│
└── README.md
```

---

## ⚙️ How It Works (Simplified Flow)

1. Live stream is ingested via **RTMP**
2. FFmpeg segments stream into **HLS chunks**
3. Video chunks are served via Spring Boot APIs
4. Redis caches hot segments (CDN simulation)
5. Chat messages flow through WebSockets + Redis Pub/Sub
6. Playback & interaction events are sent to Kafka
7. Analytics consumer processes events asynchronously

---

## 🧪 Performance & Testing

* Simulated **high-concurrency workloads** using Locust
* Observed CPU, memory, latency, cache hit ratio, and Kafka lag
* Identified system bottlenecks and stability thresholds
* Evaluated Quality of Experience (startup time, buffering inference)

---

## 🚧 Limitations

* No multi-bitrate adaptive streaming (ABR)
* Single-node deployment (no horizontal scaling yet)
* HLS-based live streaming introduces inherent latency
* Kafka performance constrained by single-broker setup

These are intentional constraints to preserve research focus.

---

## 🔮 Future Enhancements

* Adaptive Bitrate Streaming (ABR)
* Edge-level caching and fan-out
* Reactive WebSocket implementation (WebFlux/Netty)
* Kubernetes-based horizontal scaling
* Advanced real-time analytics dashboards
* ML-driven content recommendations

---

## 📄 Research Paper

A detailed academic paper analyzing the architecture, methodology, experiments, and findings is included in the repository under `/research`.

---

## 👤 Author

**Lakshya Bhardwaj**
Computer Science Engineering
Backend & Distributed Systems Enthusiast

---

## ⭐ Why This Project Matters

StreamLite demonstrates that **scalability is not only about hardware—it is about architecture**.
It serves as a blueprint for building **cost-effective, resilient streaming systems** in environments where traditional cloud infrastructure is not feasible.

---
