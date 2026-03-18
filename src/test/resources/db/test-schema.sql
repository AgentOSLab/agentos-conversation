-- H2-compatible schema for agentos-conversation tests
-- Mirrors V1__conversation_sessions.sql and V2__runs.sql but removes PostgreSQL-specific syntax

CREATE TABLE IF NOT EXISTS conversation_sessions (
    id               UUID DEFAULT RANDOM_UUID() NOT NULL PRIMARY KEY,
    tenant_id        UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    session_type     VARCHAR(32)  NOT NULL DEFAULT 'CHAT',
    interaction_mode VARCHAR(32)  NOT NULL DEFAULT 'INTERACTIVE',
    bound_entity_type VARCHAR(32),
    bound_entity_id  UUID,
    title            VARCHAR(512),
    status           VARCHAR(16)  NOT NULL DEFAULT 'active',
    active_context   VARCHAR(65536),
    mcp_tool_config  VARCHAR(65536),
    conversation_summary TEXT,
    message_count    INTEGER      NOT NULL DEFAULT 0,
    task_count       INTEGER      NOT NULL DEFAULT 0,
    total_tokens     BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS conversation_messages (
    id              UUID DEFAULT RANDOM_UUID() NOT NULL PRIMARY KEY,
    session_id      UUID        NOT NULL REFERENCES conversation_sessions(id) ON DELETE CASCADE,
    role            VARCHAR(16) NOT NULL,
    content         TEXT,
    content_blocks  VARCHAR(65536),
    tool_calls      VARCHAR(65536),
    tool_call_id    VARCHAR(128),
    task_id         UUID,
    step_id         VARCHAR(128),
    token_count     INTEGER,
    pinned          BOOLEAN     NOT NULL DEFAULT FALSE,
    metadata        VARCHAR(65536),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS runs (
    id              UUID DEFAULT RANDOM_UUID() NOT NULL PRIMARY KEY,
    session_id      UUID        NOT NULL REFERENCES conversation_sessions(id) ON DELETE CASCADE,
    tenant_id       UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'created',
    route_type      VARCHAR(32),
    interaction_mode VARCHAR(32),
    task_id         UUID,
    input_message_id UUID,
    output_message_id UUID,
    error_code      VARCHAR(64),
    error_message   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP WITH TIME ZONE
);
