ALTER TABLE agent_versions ADD COLUMN release_state VARCHAR(20) NOT NULL DEFAULT 'published';
ALTER TABLE agent_versions ADD COLUMN evaluation_job_id VARCHAR(64);
