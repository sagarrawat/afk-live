-- liquibase formatted sql

-- changeset sagarrawat:004-transaction-ledger
CREATE TABLE transaction_ledger (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount NUMERIC(19, 4) NOT NULL,
    balance_after NUMERIC(19, 4) NOT NULL,
    transaction_type VARCHAR(50) NOT NULL,
    reference_id VARCHAR(255),
    description VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_ledger_user_id ON transaction_ledger(user_id);
CREATE INDEX idx_ledger_created_at ON transaction_ledger(created_at DESC);

ALTER TABLE stream_jobs ADD COLUMN ledger_recorded BOOLEAN DEFAULT FALSE NOT NULL;
