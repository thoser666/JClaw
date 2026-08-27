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

CREATE TABLE IF NOT EXISTS channel (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    config_json CLOB,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS channel_binding (
    id VARCHAR(255) PRIMARY KEY,
    channel_id VARCHAR(255) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    binding_type VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_channel_binding_channel ON channel_binding(channel_id);
CREATE INDEX IF NOT EXISTS idx_channel_binding_external ON channel_binding(channel_id, external_id);

CREATE TABLE IF NOT EXISTS channel_message (
    id VARCHAR(255) PRIMARY KEY,
    channel_id VARCHAR(255) NOT NULL,
    external_id VARCHAR(255),
    direction VARCHAR(50) NOT NULL,
    content CLOB,
    sender_id VARCHAR(255),
    sender_name VARCHAR(255),
    thread_id VARCHAR(255),
    session_id VARCHAR(255),
    timestamp TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_channel_message_session ON channel_message(session_id);
CREATE INDEX IF NOT EXISTS idx_channel_message_channel ON channel_message(channel_id);
