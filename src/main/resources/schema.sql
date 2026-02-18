CREATE TABLE IF NOT EXISTS scenarios (
    request_id   UUID PRIMARY KEY,
    status       VARCHAR(50) NOT NULL,
    input_data   CLOB,
    session_data CLOB,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS trip_legs (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id               UUID NOT NULL,
    leg_id                   VARCHAR(255) NOT NULL,
    person_id                VARCHAR(255) NOT NULL,
    origin_activity_type     VARCHAR(100) NOT NULL,
    destination_activity_type VARCHAR(100) NOT NULL,
    co2_delta_grams          DECIMAL(10, 2) NOT NULL,
    time_delta_minutes       DECIMAL(10, 2) NOT NULL,
    impact                   VARCHAR(50) NOT NULL,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (request_id) REFERENCES scenarios(request_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_trip_legs_request_id ON trip_legs(request_id);
CREATE INDEX IF NOT EXISTS idx_trip_legs_request_id_id ON trip_legs(request_id, id);
