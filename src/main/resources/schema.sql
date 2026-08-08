CREATE TABLE IF NOT EXISTS chat_message (
    conversation_id VARCHAR(255) NOT NULL,
    message_order INT NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    message_json CLOB NOT NULL,
    PRIMARY KEY (conversation_id, message_order)
);
