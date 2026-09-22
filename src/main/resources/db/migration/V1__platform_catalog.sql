CREATE TABLE platform_users (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(120) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN', 'MEMBER')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE datasets (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    parent_id VARCHAR(64) REFERENCES datasets(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE knowledge_bases (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    retrieval_mode VARCHAR(20) NOT NULL DEFAULT 'semantic',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE knowledge_base_datasets (
    knowledge_base_id VARCHAR(64) NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    dataset_id VARCHAR(64) NOT NULL REFERENCES datasets(id),
    PRIMARY KEY (knowledge_base_id, dataset_id)
);
CREATE TABLE documents (
    id VARCHAR(64) PRIMARY KEY,
    dataset_id VARCHAR(64) NOT NULL REFERENCES datasets(id),
    path VARCHAR(1024) NOT NULL,
    active_version VARCHAR(64),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(dataset_id, path)
);
CREATE TABLE document_versions (
    id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL REFERENCES documents(id),
    content_hash VARCHAR(64) NOT NULL,
    source_path VARCHAR(1200) NOT NULL,
    content_length INTEGER NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    index_collection VARCHAR(120) NOT NULL,
    status VARCHAR(30) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX document_versions_document_idx ON document_versions(document_id);
CREATE TABLE tool_sets (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    tool_ids TEXT NOT NULL DEFAULT '[]',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE mcp_connections (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    endpoint VARCHAR(2000) NOT NULL,
    credential_env VARCHAR(120),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(30) NOT NULL DEFAULT 'UNTESTED',
    tools_json TEXT NOT NULL DEFAULT '[]',
    error_message TEXT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE agents (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    current_version INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE agent_versions (
    agent_id VARCHAR(64) NOT NULL REFERENCES agents(id),
    version INTEGER NOT NULL,
    config_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(agent_id, version)
);
CREATE TABLE agent_sessions (
    id VARCHAR(64) PRIMARY KEY,
    agent_id VARCHAR(64) NOT NULL,
    agent_version INTEGER NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(agent_id, agent_version) REFERENCES agent_versions(agent_id, version)
);
CREATE INDEX agent_sessions_owner_idx ON agent_sessions(owner_id, agent_id);
CREATE TABLE agent_runs (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL REFERENCES agent_sessions(id),
    agent_id VARCHAR(64) NOT NULL,
    agent_version INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    input_json TEXT NOT NULL,
    context_json TEXT NOT NULL,
    result_json TEXT,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITH TIME ZONE,
    FOREIGN KEY(agent_id, agent_version) REFERENCES agent_versions(agent_id, version)
);
CREATE INDEX agent_runs_session_idx ON agent_runs(session_id, created_at);
CREATE TABLE run_events (
    run_id VARCHAR(64) NOT NULL REFERENCES agent_runs(id),
    sequence INTEGER NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(run_id, sequence)
);
CREATE TABLE evaluation_sets (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK(kind IN ('retrieval', 'agent')),
    cases_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE evaluation_jobs (
    id VARCHAR(64) PRIMARY KEY,
    set_id VARCHAR(64) NOT NULL REFERENCES evaluation_sets(id),
    config_json TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    result_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITH TIME ZONE
);
