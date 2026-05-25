# Inference Token Gateway

A distributed backend system that simulates an LLM inference gateway with token-based rate limiting, caching, and usage tracking under concurrent load.

## Why this exists

Modern LLM APIs require strict control over:
- token consumption per user
- request throttling under high concurrency
- expensive duplicate inference calls

This system models those constraints using a backend gateway architecture.

---

## System Overview

Request flow:
```bash
Client → API Gateway → Rate Limiter(Redis) → Cache Layer(Redis) → LLM Backend(Simulated) → Usage Tracker(PostgreSQL)
```
---

## Core Features

### 1. Token-based Rate Limiting
- Redis-based sliding window implementation
- Enforces per-user token limits
- Handles concurrent requests safely

### 2. Response Caching
- Deduplicates identical prompts
- Reduces simulated LLM cost
- TTL-based cache expiry

### 3. Usage Tracking
- Stores per-user usage metrics in PostgreSQL
- Tracks token consumption + request counts

### 4. Idempotency Support
- Prevents duplicate processing on retries
- Uses persistent idempotency keys

---

## Tech Stack

- Java / Spring Boot (or Python if applicable — adjust this honestly)
- Redis (rate limiting + caching)
- PostgreSQL (usage tracking)
- Docker Compose (local orchestration)

---

## Design Decisions

- Sliding window chosen for precise rate control over token buckets
- Redis used for distributed consistency under concurrency
- Fail-open strategy used to preserve availability under cache failure
- Stateless API layer for horizontal scalability

---

## How to Run

```bash
docker-compose up --build
```

Run load test:
```bash
python3 load_test.py
```

---

## Limitations

- Token estimation is approximate
- No real LLM integration (simulated backend)
- Single-region setup

---

## Future Improvements

- Kafka-based event pipeline for usage analytics
- Distributed multi-region rate limiting
- Circuit breaker pattern for backend isolation

---

## Copyright
Copyright (c) 2026 Meghna Ravikumar. All rights reserved. No part of this software may be reproduced or distributed without permission.
