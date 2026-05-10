-- =============================================================================
-- EAKP Database Initialisation
-- Run automatically by Docker on first start
-- =============================================================================

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;  -- For fuzzy text search

-- =============================================================================
-- USERS & WORKSPACES
-- =============================================================================

CREATE TABLE workspaces (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL UNIQUE,
    default_model   VARCHAR(100) DEFAULT 'gpt-4o-mini',
    chunk_size      INT          DEFAULT 512,
    chunk_overlap   INT          DEFAULT 128,
    embedding_model VARCHAR(100) DEFAULT 'text-embedding-3-small',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password      VARCHAR(255),              -- BCrypt hash (NULL for OAuth-only users)
    full_name     VARCHAR(255),
    role          VARCHAR(50)  NOT NULL DEFAULT 'USER', -- USER | ADMIN
    auth_provider VARCHAR(50)  NOT NULL DEFAULT 'LOCAL', -- LOCAL | GOOGLE | GITHUB
    workspace_id  UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email        ON users(email);
CREATE INDEX idx_users_workspace_id ON users(workspace_id);

-- =============================================================================
-- DOCUMENTS
-- =============================================================================

CREATE TABLE documents (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    uploaded_by  UUID         NOT NULL REFERENCES users(id),
    filename     VARCHAR(500) NOT NULL,
    file_type    VARCHAR(50)  NOT NULL,  -- pdf | docx | txt | md
    file_size    BIGINT       NOT NULL,
    storage_key  VARCHAR(1000) NOT NULL, -- MinIO object key
    status       VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
                                         -- PENDING | PROCESSING | READY | FAILED
    chunk_count  INT,
    error_msg    TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_documents_workspace ON documents(workspace_id);
CREATE INDEX idx_documents_status    ON documents(status);

-- =============================================================================
-- DOCUMENT CHUNKS + VECTOR EMBEDDINGS (core RAG table)
-- =============================================================================

CREATE TABLE document_chunks (
    id           UUID    PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id  UUID    NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    workspace_id UUID    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    content      TEXT    NOT NULL,
    -- IMPORTANT: Change dimension to match your embedding model:
    --   nomic-embed-text      = 768 dims (local profile)
    --   all-MiniLM-L6-v2      = 384 dims (prod profile)
    --   text-embedding-3-small = 1536 dims (OpenAI)
    -- For local development with Ollama nomic-embed-text, use 768.
    -- For prod with all-MiniLM-L6-v2, recreate with 384.
    embedding    vector(768),
    chunk_index  INT     NOT NULL,
    token_count  INT,
    metadata     JSONB   NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Vector similarity search index (HNSW - best for < 10M vectors)
CREATE INDEX idx_chunks_embedding_hnsw
    ON document_chunks
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- For workspace-scoped queries
CREATE INDEX idx_chunks_workspace     ON document_chunks(workspace_id);
CREATE INDEX idx_chunks_document      ON document_chunks(document_id);

-- Full-text search index for BM25/keyword hybrid search
CREATE INDEX idx_chunks_fts
    ON document_chunks
    USING gin(to_tsvector('english', content));

-- Trigram index for fuzzy match
CREATE INDEX idx_chunks_trgm
    ON document_chunks
    USING gin(content gin_trgm_ops);

-- =============================================================================
-- CONVERSATIONS & MESSAGES
-- =============================================================================

CREATE TABLE conversations (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id      UUID         NOT NULL REFERENCES users(id),
    title        VARCHAR(500),
    summary      TEXT,        -- Compressed older messages summary
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conversations_user      ON conversations(user_id);
CREATE INDEX idx_conversations_workspace ON conversations(workspace_id);

CREATE TABLE messages (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,  -- USER | ASSISTANT | SYSTEM
    content         TEXT        NOT NULL,
    token_count     INT,
    sources         JSONB       DEFAULT '[]', -- cited chunk IDs + snippets
    faithfulness    DECIMAL(4,3),             -- 0.000 to 1.000
    feedback        VARCHAR(20),              -- positive | negative | NULL
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id);
CREATE INDEX idx_messages_created      ON messages(conversation_id, created_at);

-- =============================================================================
-- SEMANTIC CACHE
-- =============================================================================

CREATE TABLE semantic_cache (
    id           UUID    PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID    NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    query        TEXT    NOT NULL,
    answer       TEXT    NOT NULL,
    embedding    vector(768),
    hit_count    INT     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_hit_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '24 hours'
);

CREATE INDEX idx_cache_workspace   ON semantic_cache(workspace_id);
CREATE INDEX idx_cache_embedding   ON semantic_cache USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_cache_expires     ON semantic_cache(expires_at);

-- =============================================================================
-- SEED DATA (local dev only)
-- =============================================================================

INSERT INTO workspaces (id, name, slug) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Demo Workspace', 'demo');

-- Password: 'password' (BCrypt hash)
INSERT INTO users (id, email, password, full_name, role, workspace_id) VALUES
    ('22222222-2222-2222-2222-222222222222',
     'admin@eakp.local',
     '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4J/jvTtMle',
     'Admin User',
     'ADMIN',
     '11111111-1111-1111-1111-111111111111'),
    ('33333333-3333-3333-3333-333333333333',
     'user@eakp.local',
     '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4J/jvTtMle',
     'Regular User',
     'USER',
     '11111111-1111-1111-1111-111111111111');

-- =============================================================================
-- AUDIT LOG (admin action tracking for security compliance)
-- =============================================================================

CREATE TABLE audit_log (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    admin_email VARCHAR(255) NOT NULL,
    action      VARCHAR(100) NOT NULL,
    target_id   UUID,
    details     TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_admin   ON audit_log(admin_email);
CREATE INDEX idx_audit_log_action  ON audit_log(action);
CREATE INDEX idx_audit_log_created ON audit_log(created_at DESC);

