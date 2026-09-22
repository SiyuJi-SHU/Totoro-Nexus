CREATE TABLE legacy_session_aliases (
    owner_id VARCHAR(64) NOT NULL,
    alias VARCHAR(100) NOT NULL,
    session_id VARCHAR(64) NOT NULL REFERENCES agent_sessions(id),
    PRIMARY KEY(owner_id, alias)
);
