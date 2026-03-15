# agentos-conversation

**Conversation Orchestration Layer** for the AgentOS platform.

## Overview

`agentos-conversation` is the central orchestration service that manages conversation sessions, message routing, context assembly, and SSE event aggregation. It acts as the brain of the platform's request processing pipeline — classifying user intent and routing to the appropriate execution path.

**Port:** 8011 | **DB:** PostgreSQL (R2DBC) + Redis | **Multi-instance:** Yes (stateless, Redis-backed)

## Architecture Position

```
Channel Layer → [Channel Gateway :8012]
                       ↓
Orchestration Layer → [Conversation :8011 ×N] ← API Gateway :8080
                       ↓
Execution Layer → [Agent Runtime :8004] [LLM Gateway :8003] [MCP Runtime :8005] [RAG :8010]
```

This service was extracted from Agent Runtime (ADR-041) to prevent a "god service" anti-pattern and enable independent scaling of orchestration vs execution workloads.

## Multi-Instance Architecture (ADR-043)

All runtime state lives in Redis or PostgreSQL — no in-memory sinks or counters:

| Redis Key | Type | TTL | Purpose |
|-----------|------|-----|---------|
| `run_events:{runId}` | Sorted Set | 15min | Event buffer for SSE replay |
| `run_seq:{runId}` | Atomic String | 15min | Monotonic sequence counter |
| `run_channel:{runId}` | Pub/Sub | — | Real-time event distribution |
| `run_meta:{runId}` | String | 15min | Run→Session mapping |
| `ctx:recent:{sessionId}` | String (JSON) | 5min | L2 cache: recent messages |
| `ctx:pinned:{sessionId}` | String (JSON) | 5min | L2 cache: pinned messages |
| `ctx:summary:{sessionId}` | String | 5min | L2 cache: conversation summary |
| `lock:summarize:{sessionId}` | String | 60s | Distributed summarization lock |

**Scaling guarantees:**
- Any instance can publish events (INCR + ZADD + PUBLISH to Redis)
- Any instance can serve SSE streams (SUBSCRIBE + ZRANGEBYSCORE for replay)
- HITL cancel/input works regardless of which instance handles the request
- Summarization protected by distributed lock (lock-or-skip)

## Key Responsibilities

| Component | Responsibility |
|-----------|---------------|
| **ConversationSessionService** | Session lifecycle (CRUD), message persistence, token tracking |
| **RunService** | Run lifecycle — create, update status, complete, fail, cancel |
| **IntentRouter** | Classifies user intent → SIMPLE_CHAT / TOOL_CALL / AGENT_TASK / WORKFLOW |
| **MessageOrchestrator** | Central orchestration — Run creation, execution dispatch, streaming |
| **RunController** | Unified Run API — `/runs` endpoints (create, events, cancel, input, retry) |
| **SseAggregator** | Redis-backed event normalization, Pub/Sub distribution, Sorted Set buffering, reconnect replay |
| **SessionContextBuilder** | Assembles prompt-cache-optimized LLM context with L2 Redis cache |
| **ConversationSummarizer** | Auto-summarizes old messages with distributed lock protection |

## Session Types

| Type | Description | Routing |
|------|-------------|---------|
| `CHAT` | General chat window | Intent-based routing |
| `AGENT_CHAT` | Chat bound to a specific agent | Always → Agent Runtime |
| `WORKFLOW_CHAT` | Chat bound to a workflow | Always → Agent Runtime (workflow mode) |
| `WORKFLOW_SILENT` | Background workflow execution | Silent → Agent Runtime |
| `SCHEDULED` | Scheduled task trigger | Silent → Agent Runtime |

## Communication Model

Every user message creates a **Run** — a unified execution unit.

```
Client → Server: HTTP requests (POST /runs, POST /runs/{id}/cancel, POST /runs/{id}/input)
Server → Client: SSE event stream (GET /runs/{id}/events)
```

**Run lifecycle:** queued → running → waiting_for_input → completed / failed / cancelled / expired

## Event Flow (Multi-Instance)

```
Agent Runtime → Redis Pub/Sub (events:{tenantId}:{taskId})
                        ↓
Conversation Instance A subscribes → normalizes → Redis ZADD + PUBLISH (run_channel:{runId})
                                                            ↓
Conversation Instance B serves SSE → Redis SUBSCRIBE (run_channel:{runId}) → Client
```

## Context Assembly (Three-Level Cache)

```
L1: JVM heap (within request scope)
L2: Redis (TTL=5min) — recent messages, pinned messages, summary
L3: PostgreSQL (authoritative source)
```

Context optimizations:
- Thinking/reasoning messages excluded from LLM context (saves ~15-30% tokens)
- Tool output compressed (head+tail for outputs > 2000 chars)
- Summarizer skips thinking messages

## Routing Logic

```
POST /runs → Create Run → IntentRouter
  ├── SIMPLE_CHAT → LLM Gateway (streaming) → token events via Redis → SSE
  ├── TOOL_CALL   → MCP Runtime (lightweight)
  ├── AGENT_TASK  → Agent Runtime → Redis Pub/Sub → normalized events → SSE
  └── WORKFLOW    → Agent Runtime → Redis Pub/Sub → normalized events → SSE
```

## Tech Stack

- Java 21 + Spring Boot 3.3.5 + WebFlux
- R2DBC + PostgreSQL (conversation_sessions, conversation_messages, runs)
- Redis (event buffer, Pub/Sub, sequence counters, context cache, distributed locks)
- Flyway migrations

## API

See `repos/agentos-spec/api/conversation/conversation.yaml` for the full OpenAPI spec.

Key endpoints:
- `POST /api/v1/sessions` — Create session
- `GET /api/v1/sessions` — List sessions
- `POST /api/v1/sessions/{id}/runs` — Send message, create a Run
- `GET /api/v1/sessions/{id}/runs/{runId}/events` — SSE event stream (supports Last-Event-ID reconnect)
- `POST /api/v1/sessions/{id}/runs/{runId}/cancel` — Cancel a run
- `POST /api/v1/sessions/{id}/runs/{runId}/input` — Provide HITL input
- `POST /api/v1/sessions/{id}/runs/{runId}/retry` — Retry a failed run
- `GET /api/v1/sessions/{id}/runs/{runId}` — Poll run status (non-SSE fallback)

## Configuration

```yaml
agentos:
  services:
    agent-runtime-url: http://localhost:8004
    hub-url: http://localhost:8001
    llm-gateway-url: http://localhost:8003
    rag-url: http://localhost:8010
    user-system-url: http://localhost:8006
  context:
    default-window-size: 20
    max-token-budget: 100000
    summarization-trigger: 30
    tool-output-max-chars: 2000
```

## Running Locally

```bash
# Requires PostgreSQL (port 5435, db: agentos_conversation) and Redis
mvn spring-boot:run
```

## Related

- [ADR-041: Conversation Orchestrator Separation](../../project/decision-log.md)
- [ADR-042: Run-Based Streaming Model](../../project/decision-log.md)
- [ADR-043: Multi-Instance Safe Architecture](../../project/decision-log.md)
- [REQ-conversation](../../docs/sdd/requirements/REQ-conversation.md)
- [Architecture Design](../../docs/architecture/architecture-design.md)
