-- ============================================================
-- V1__conversation_sessions.sql
-- Purpose: Create conversation session and message tables.
-- These tables were migrated from agentos-agent-runtime to
-- the dedicated agentos-conversation orchestration service.
-- ============================================================

CREATE TABLE IF NOT EXISTS conversation_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    session_type    VARCHAR(32) NOT NULL DEFAULT 'CHAT',
    interaction_mode VARCHAR(32) NOT NULL DEFAULT 'INTERACTIVE',
    bound_entity_type VARCHAR(32),
    bound_entity_id UUID,
    title           VARCHAR(512),
    status          VARCHAR(16) NOT NULL DEFAULT 'active',
    active_context  JSONB,
    mcp_tool_config JSONB,
    conversation_summary TEXT,
    message_count   INTEGER     NOT NULL DEFAULT 0,
    task_count      INTEGER     NOT NULL DEFAULT 0,
    total_tokens    BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_conv_sessions_tenant_user
    ON conversation_sessions (tenant_id, user_id);

CREATE INDEX IF NOT EXISTS idx_conv_sessions_tenant_status
    ON conversation_sessions (tenant_id, status);

CREATE INDEX IF NOT EXISTS idx_conv_sessions_last_activity
    ON conversation_sessions (last_activity_at);

CREATE INDEX IF NOT EXISTS idx_conv_sessions_bound_entity
    ON conversation_sessions (bound_entity_type, bound_entity_id)
    WHERE bound_entity_id IS NOT NULL;

-- ────────────────────────────────────────────────────────────
-- conversation_messages: individual messages within a session
-- ────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS conversation_messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID        NOT NULL REFERENCES conversation_sessions(id) ON DELETE CASCADE,
    role            VARCHAR(16) NOT NULL,
    content         TEXT,
    content_blocks  JSONB,
    tool_calls      JSONB,
    tool_call_id    VARCHAR(128),
    task_id         UUID,
    step_id         VARCHAR(128),
    token_count     INTEGER,
    pinned          BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_conv_messages_session_created
    ON conversation_messages (session_id, created_at);

CREATE INDEX IF NOT EXISTS idx_conv_messages_session_pinned
    ON conversation_messages (session_id)
    WHERE pinned = TRUE;

CREATE INDEX IF NOT EXISTS idx_conv_messages_task
    ON conversation_messages (task_id)
    WHERE task_id IS NOT NULL;
