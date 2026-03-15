-- V2: Add runs table for the unified Run-based streaming model.
-- A Run represents a single unit of execution within a session,
-- encapsulating the full lifecycle from user message to final response.

CREATE TABLE IF NOT EXISTS runs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       UUID         NOT NULL REFERENCES conversation_sessions(id) ON DELETE CASCADE,
    tenant_id        UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    status           VARCHAR(32)  NOT NULL DEFAULT 'queued',
    route_type       VARCHAR(32)  NOT NULL,
    task_id          UUID,
    input_message_id UUID         NOT NULL,
    output_message_id UUID,
    model_hint       VARCHAR(16),
    token_usage      JSONB        DEFAULT '{}',
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    last_event_id    VARCHAR(128),
    error            JSONB,
    metadata         JSONB        DEFAULT '{}',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_runs_session FOREIGN KEY (session_id) REFERENCES conversation_sessions(id),
    CONSTRAINT chk_run_status CHECK (status IN ('queued','running','waiting_for_input','completed','failed','cancelled','expired')),
    CONSTRAINT chk_run_route_type CHECK (route_type IN ('simple_chat','agent_task','workflow'))
);

CREATE INDEX idx_runs_session_id ON runs(session_id);
CREATE INDEX idx_runs_tenant_id ON runs(tenant_id);
CREATE INDEX idx_runs_status ON runs(status);
CREATE INDEX idx_runs_created_at ON runs(created_at DESC);
