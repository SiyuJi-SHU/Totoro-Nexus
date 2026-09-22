ALTER TABLE evaluation_sets ADD COLUMN knowledge_base_id VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE evaluation_sets ADD COLUMN target_agent_id VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE evaluation_sets ADD COLUMN target_strategy VARCHAR(40) NOT NULL DEFAULT '';

CREATE INDEX evaluation_sets_target_idx
    ON evaluation_sets(kind, knowledge_base_id, target_agent_id, target_strategy);

