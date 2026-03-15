# agentos-conversation

**Conversation Orchestration Layer** for the AgentOS platform.

## Overview

`agentos-conversation` is the central orchestration service that manages conversation sessions, message routing, context assembly, and SSE event aggregation. It acts as the brain of the platform's request processing pipeline — classifying user intent and routing to the appropriate execution path.

**Port:** 8011 | **DB:** PostgreSQL (R2DBC) + Redis

## Architecture Position

```
Channel Layer → [Channel Gateway :8012]
                       ↓
Orchestration Layer → [Conversation :8011] ← API Gateway :8080
                       ↓
Execution Layer → [Agent Runtime :8004] [LLM Gateway :8003] [MCP Runtime :8005] [RAG :8010]
```

This service was extracted from Agent Runtime (ADR-041) to prevent a "god service" anti-pattern and enable independent scaling of orchestration vs execution workloads.

## Key Responsibilities

| Component | Responsibility |
|-----------|---------------|
| **ConversationSessionService** | Session lifecycle (CRUD), message persistence, token tracking |
| **IntentRouter** | Classifies user intent → SIMPLE_CHAT / TOOL_CALL / AGENT_TASK / WORKFLOW |
| **MessageOrchestrator** | Central orchestration — routes to LLM Gateway or Agent Runtime |
| **SessionContextBuilder** | Assembles prompt-cache-optimized LLM context (cached prefix + dynamic suffix) |
| **ConversationSummarizer** | Auto-summarizes old messages when session exceeds sliding window |
| **SseAggregator** | Enriches Agent Runtime SSE events with session context |

## Session Types

| Type | Description | Routing |
|------|-------------|---------|
| `CHAT` | General chat window | Intent-based routing |
| `AGENT_CHAT` | Chat bound to a specific agent | Always → Agent Runtime |
| `WORKFLOW_CHAT` | Chat bound to a workflow | Always → Agent Runtime (workflow mode) |
| `WORKFLOW_SILENT` | Background workflow execution | Silent → Agent Runtime |
| `SCHEDULED` | Scheduled task trigger | Silent → Agent Runtime |

## Routing Logic

```
User Message → IntentRouter
  ├── SIMPLE_CHAT → LLM Gateway (direct, no Task created)
  ├── TOOL_CALL   → MCP Runtime (lightweight)
  ├── AGENT_TASK  → Agent Runtime (full agentic loop)
  └── WORKFLOW    → Agent Runtime (workflow execution)
```

## Tech Stack

- Java 21 + Spring Boot 3.3.5 + WebFlux
- R2DBC + PostgreSQL (conversation_sessions, conversation_messages)
- Redis (session cache, event pub/sub)
- Flyway migrations

## API

See `repos/agentos-spec/api/conversation/conversation.yaml` for the full OpenAPI spec.

Key endpoints:
- `POST /api/v1/sessions` — Create session
- `GET /api/v1/sessions` — List sessions
- `POST /api/v1/sessions/{id}/messages` — Send message (triggers orchestration)
- `GET /api/v1/sessions/{id}/tasks/{taskId}/events` — SSE stream

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
```

## Running Locally

```bash
# Requires PostgreSQL (port 5435, db: agentos_conversation) and Redis
mvn spring-boot:run
```

## Related

- [ADR-041: Conversation Orchestrator Separation](../../project/decision-log.md)
- [REQ-conversation](../../docs/sdd/requirements/REQ-conversation.md)
- [Architecture Design](../../docs/architecture/architecture-design.md)
