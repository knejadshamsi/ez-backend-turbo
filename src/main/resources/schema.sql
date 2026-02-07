CREATE TABLE IF NOT EXISTS scenarios (
    request_id   UUID PRIMARY KEY,
    status       VARCHAR(50) NOT NULL,
    input_data   JSONB,
    session_data JSONB,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);