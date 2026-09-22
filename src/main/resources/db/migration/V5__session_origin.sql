ALTER TABLE agent_sessions ADD COLUMN origin VARCHAR(20) NOT NULL DEFAULT 'user';
CREATE INDEX agent_sessions_origin_idx ON agent_sessions(owner_id, agent_id, origin, updated_at);
