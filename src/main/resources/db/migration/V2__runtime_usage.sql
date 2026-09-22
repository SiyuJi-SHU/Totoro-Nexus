CREATE TABLE model_calls (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64),
    purpose VARCHAR(100) NOT NULL,
    kind VARCHAR(30) NOT NULL,
    model VARCHAR(160) NOT NULL,
    input_tokens INTEGER,
    output_tokens INTEGER,
    total_tokens INTEGER,
    elapsed_ms BIGINT NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX model_calls_run_idx ON model_calls(run_id);
CREATE INDEX model_calls_created_idx ON model_calls(created_at);
CREATE UNIQUE INDEX agent_runs_one_active_session ON agent_runs(session_id)
    WHERE status IN ('queued','running','reviewing');
