-- =============================================================================
-- EAKP Production Database Schema (384-dim embeddings: all-MiniLM-L6-v2)
-- Run this on Neon.tech / Supabase after creating the database
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ── USERS & WORKSPACES ──────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS workspaces (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL UNIQUE,
    default_model   VARCHAR(100) DEFAULT 'llama-3.3-70b-versatile',
    chunk_size      INT          DEFAULT 512,
    chunk_overlap   INT          DEFAULT 128,
    embedding_model VARCHAR(100) DEFAULT 'all-MiniLM-L6-v2',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS users (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password      VARCHAR(255),
    full_name     VARCHAR(255),
    role          VARCHAR(50)  NOT NULL DEFAULT 'USER',
    auth_provider VARCHAR(50)  NOT NULL DEFAULT 'LOCAL',
    workspace_id  UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_workspace_id ON users(workspace_id);

-- ── DOCUMENTS ───────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS documents (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    uploaded_by  UUID         NOT NULL REFERENCES users(id),
    filename     VARCHAR(500) NOT NULL,
    file_type    VARCHAR(50)  NOT NULL,
    file_size    BIGINT       NOT NULL,
    storage_key  VARCHAR(1000) NOT NULL,
    status       VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    chunk_count  INT,
    error_msg    TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_documents_workspace ON documents(workspace_id);
CREATE INDEX IF NOT EXISTS idx_documents_status ON documents(status);

-- ── DOCUMENT CHUNKS (384 dims for all-MiniLM-L6-v2) ────────────────────────

CREATE TABLE IF NOT EXISTS document_chunks (
    id           UUID    PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id  UUID    NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    workspace_id UUID    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    content      TEXT    NOT NULL,
    embedding    vector(384),
    chunk_index  INT     NOT NULL,
    token_count  INT,
    metadata     JSONB   NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw
    ON document_chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
CREATE INDEX IF NOT EXISTS idx_chunks_workspace ON document_chunks(workspace_id);
CREATE INDEX IF NOT EXISTS idx_chunks_document ON document_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_chunks_fts ON document_chunks USING gin(to_tsvector('english', content));
CREATE INDEX IF NOT EXISTS idx_chunks_trgm ON document_chunks USING gin(content gin_trgm_ops);

-- ── CONVERSATIONS & MESSAGES ────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS conversations (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id      UUID         NOT NULL REFERENCES users(id),
    title        VARCHAR(500),
    summary      TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_conversations_user ON conversations(user_id);
CREATE INDEX IF NOT EXISTS idx_conversations_workspace ON conversations(workspace_id);

CREATE TABLE IF NOT EXISTS messages (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,
    content         TEXT        NOT NULL,
    token_count     INT,
    sources         JSONB       DEFAULT '[]',
    faithfulness    DECIMAL(4,3),
    feedback        VARCHAR(20),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id);
CREATE INDEX IF NOT EXISTS idx_messages_created ON messages(conversation_id, created_at);

-- ── SEMANTIC CACHE (384 dims) ───────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS semantic_cache (
    id           UUID    PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    query        TEXT    NOT NULL,
    answer       TEXT    NOT NULL,
    embedding    vector(384),
    hit_count    INT     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_hit_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '24 hours'
);

CREATE INDEX IF NOT EXISTS idx_cache_workspace ON semantic_cache(workspace_id);
CREATE INDEX IF NOT EXISTS idx_cache_embedding ON semantic_cache USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_cache_expires ON semantic_cache(expires_at);

-- ── AUDIT LOG ───────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS audit_log (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    admin_email VARCHAR(255) NOT NULL,
    action      VARCHAR(100) NOT NULL,
    target_id   UUID,
    details     TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_admin ON audit_log(admin_email);
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log(action);
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log(created_at DESC);

