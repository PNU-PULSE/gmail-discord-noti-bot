CREATE TABLE IF NOT EXISTS gmail_checkpoint (
    mailbox TEXT PRIMARY KEY,
    history_id TEXT NOT NULL,
    checked_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS discord_outbox (
    mailbox TEXT NOT NULL,
    message_id TEXT NOT NULL,
    subject TEXT NOT NULL,
    snippet TEXT NOT NULL,
    link TEXT NOT NULL,
    sent BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (mailbox, message_id)
);

CREATE TABLE IF NOT EXISTS bot_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    encrypted_config TEXT NOT NULL
);
