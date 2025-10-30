DROP TABLE IF EXISTS routes CASCADE;
DROP TABLE IF EXISTS legs CASCADE;
DROP TABLE IF EXISTS activities CASCADE;
DROP TABLE IF EXISTS agents CASCADE;
DROP TABLE IF EXISTS status CASCADE;

CREATE TABLE agents (
    request_id TEXT,
    agent_id TEXT,
    plan_id TEXT,
    vehicles TEXT[],
    plan_score DOUBLE PRECISION,
    selected BOOLEAN,
    stuck BOOLEAN DEFAULT FALSE,
    total_legs INTEGER DEFAULT 0,
    total_activities INTEGER DEFAULT 0,
    PRIMARY KEY (agent_id, plan_id)
);

CREATE TABLE activities (
    activity_id TEXT PRIMARY KEY,
    agent_id TEXT,
    plan_id TEXT,
    request_id TEXT,
    order_num INTEGER,
    type TEXT,
    link TEXT,
    end_time TIME,
    FOREIGN KEY (agent_id, plan_id) REFERENCES agents ON DELETE CASCADE
);

CREATE TABLE legs (
    leg_id TEXT PRIMARY KEY,
    agent_id TEXT,
    plan_id TEXT,
    request_id TEXT,
    order_num INTEGER,
    mode TEXT,
    routing_mode TEXT,
    route_id TEXT,
    FOREIGN KEY (agent_id, plan_id) REFERENCES agents ON DELETE CASCADE
);

CREATE TABLE routes (
    route_id TEXT PRIMARY KEY,
    agent_id TEXT,
    plan_id TEXT,
    request_id TEXT,
    type TEXT,
    start_link TEXT,
    end_link TEXT,
    trav_time INTERVAL,
    distance DOUBLE PRECISION,
    vehicle_ref_id TEXT,
    link_sequence TEXT[],
    FOREIGN KEY (agent_id, plan_id) REFERENCES agents ON DELETE CASCADE
);

CREATE TABLE status (
    request_id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    message TEXT
);

CREATE INDEX IF NOT EXISTS idx_agents_request_id ON agents(request_id);
CREATE INDEX IF NOT EXISTS idx_activities_request_id ON activities(request_id);
CREATE INDEX IF NOT EXISTS idx_legs_request_id ON legs(request_id);
CREATE INDEX IF NOT EXISTS idx_routes_request_id ON routes(request_id);
