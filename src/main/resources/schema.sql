CREATE TABLE IF NOT EXISTS chat_message (
    conversation_id VARCHAR(255) NOT NULL,
    message_order INT NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    message_json CLOB NOT NULL,
    PRIMARY KEY (conversation_id, message_order)
);

CREATE TABLE IF NOT EXISTS session (
    session_id VARCHAR(255) PRIMARY KEY,
    display_name VARCHAR(255),
    session_started_at TIMESTAMP NOT NULL,
    last_interaction_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

ALTER TABLE session ADD COLUMN IF NOT EXISTS session_group VARCHAR(255);

CREATE TABLE IF NOT EXISTS api_key (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_api_key_token_hash ON api_key(token_hash);

CREATE TABLE IF NOT EXISTS cron_job (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    cron_expression VARCHAR(255) NOT NULL,
    prompt CLOB NOT NULL,
    context_id VARCHAR(255),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_at TIMESTAMP,
    next_run_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
