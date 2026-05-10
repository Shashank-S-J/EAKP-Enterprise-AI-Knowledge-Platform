-- =============================================================================
-- V2: Add audit log table for security events & password reset tracking
-- =============================================================================

CREATE TABLE IF NOT EXISTS audit_log (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    event_type  VARCHAR(100) NOT NULL,   -- LOGIN, LOGOUT, PASSWORD_RESET, FAILED_LOGIN, etc.
    ip_address  VARCHAR(45),
    user_agent  TEXT,
    metadata    JSONB DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_user    ON audit_log(user_id);
CREATE INDEX idx_audit_log_event   ON audit_log(event_type);
CREATE INDEX idx_audit_log_created ON audit_log(created_at);

-- Partition-friendly index for time-based queries
CREATE INDEX idx_audit_log_user_time ON audit_log(user_id, created_at DESC);

-- =============================================================================
-- Add 'last_login_at' to users for session tracking
-- =============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;

-- =============================================================================
-- Add workspace-level settings for max upload size & allowed file types
-- =============================================================================

ALTER TABLE workspaces ADD COLUMN IF NOT EXISTS max_upload_size_mb INT DEFAULT 50;
ALTER TABLE workspaces ADD COLUMN IF NOT EXISTS allowed_file_types VARCHAR(500) DEFAULT 'pdf,docx,doc,txt,md,pptx,xlsx,csv,html,rtf,json,xml';
ALTER TABLE workspaces ADD COLUMN IF NOT EXISTS max_documents INT DEFAULT 1000;

