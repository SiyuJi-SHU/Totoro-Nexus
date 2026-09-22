CREATE TABLE session_attachments (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL REFERENCES agent_sessions(id) ON DELETE CASCADE,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size INTEGER NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    uploaded_by VARCHAR(64) NOT NULL REFERENCES platform_users(id)
);

CREATE INDEX session_attachments_session_idx ON session_attachments(session_id, uploaded_at);

ALTER TABLE agent_sessions ADD COLUMN context_revision INTEGER NOT NULL DEFAULT 1;

COMMENT ON TABLE session_attachments IS '会话附件：用户上传的诊断材料（日志、配置文件等）';
COMMENT ON COLUMN session_attachments.storage_path IS '文件系统存储路径，使用随机UUID命名防止路径遍历';
COMMENT ON COLUMN agent_sessions.context_revision IS '上下文版本号，每次添加附件或更新现场时递增';
