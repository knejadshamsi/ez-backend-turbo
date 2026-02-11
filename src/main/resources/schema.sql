CREATE TABLE IF NOT EXISTS scenarios (
    request_id   UUID PRIMARY KEY,
    status       VARCHAR(50) NOT NULL,
    input_data   CLOB,
    session_data CLOB,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
