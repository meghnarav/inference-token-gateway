-- V1__initial_schema.sql
-- Inference Token Gateway - Initial Schema

-- Users table (lightweight, tracks existence)
CREATE TABLE IF NOT EXISTS gateway_users (
    user_id        VARCHAR(128) PRIMARY KEY,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    tier           VARCHAR(32)  NOT NULL DEFAULT 'standard',
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Per-request usage log (append-only, source of truth)
CREATE TABLE IF NOT EXISTS usage_events (
    id               BIGSERIAL    PRIMARY KEY,
    request_id       VARCHAR(64)  NOT NULL UNIQUE,
    user_id          VARCHAR(128) NOT NULL REFERENCES gateway_users(user_id),
    prompt_hash      VARCHAR(64)  NOT NULL,
    prompt_length    INT          NOT NULL,
    tokens_consumed  INT          NOT NULL,
    cache_hit        BOOLEAN      NOT NULL DEFAULT FALSE,
    latency_ms       INT          NOT NULL,
    model_id         VARCHAR(64)  NOT NULL DEFAULT 'simulated-v1',
    status           VARCHAR(32)  NOT NULL DEFAULT 'SUCCESS',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Aggregate usage per user per day (materialized for fast GET /usage queries)
CREATE TABLE IF NOT EXISTS user_usage_summary (
    user_id           VARCHAR(128) NOT NULL REFERENCES gateway_users(user_id),
    usage_date        DATE         NOT NULL DEFAULT CURRENT_DATE,
    total_tokens      BIGINT       NOT NULL DEFAULT 0,
    total_requests    INT          NOT NULL DEFAULT 0,
    cache_hit_count   INT          NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, usage_date)
);

-- Idempotency keys table (prevents duplicate request processing)
CREATE TABLE IF NOT EXISTS idempotency_keys (
    idempotency_key  VARCHAR(256) PRIMARY KEY,
    request_id       VARCHAR(64)  NOT NULL,
    user_id          VARCHAR(128) NOT NULL,
    response_body    TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at       TIMESTAMPTZ  NOT NULL
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_usage_events_user_id      ON usage_events(user_id);
CREATE INDEX IF NOT EXISTS idx_usage_events_created_at   ON usage_events(created_at);
CREATE INDEX IF NOT EXISTS idx_usage_events_prompt_hash  ON usage_events(prompt_hash);
CREATE INDEX IF NOT EXISTS idx_idempotency_expires_at    ON idempotency_keys(expires_at);

-- Cleanup function for expired idempotency keys (called via scheduled job)
CREATE OR REPLACE FUNCTION cleanup_expired_idempotency_keys()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM idempotency_keys WHERE expires_at < NOW();
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Seed standard users for testing
INSERT INTO gateway_users (user_id, tier) VALUES
    ('user-alice', 'premium'),
    ('user-bob',   'standard'),
    ('user-carol', 'standard'),
    ('user-dave',  'free')
ON CONFLICT DO NOTHING;
