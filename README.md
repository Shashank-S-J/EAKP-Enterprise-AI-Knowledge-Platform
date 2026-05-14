# Enterprise AI Knowledge Platform (EAKP)

<p align="center">
  <strong>Production-grade, multi-tenant Retrieval-Augmented Generation (RAG) platform — ChatGPT for your company's private data, with citations you can trust.</strong>
</p>

<p align="center">
  <a href="https://eakp.vercel.app"><strong>🚀 Live Demo → eakp.vercel.app</strong></a>
</p>

<p align="center">
  <a href="https://eakp.vercel.app"><img src="https://img.shields.io/badge/Live%20Demo-eakp.vercel.app-black?logo=vercel" alt="Live Demo" /></a>
  <img src="https://img.shields.io/badge/Java-21-orange?logo=openjdk" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.3.5-green?logo=spring-boot" alt="Spring Boot" />
  <img src="https://img.shields.io/badge/React-19-blue?logo=react" alt="React 19" />
  <img src="https://img.shields.io/badge/PostgreSQL-16%2Bpgvector-blue?logo=postgresql" alt="PostgreSQL" />
  <img src="https://img.shields.io/badge/Microservices-5-purple" alt="5 services" />
  <img src="https://img.shields.io/badge/Deployed-Render%20%2B%20Vercel-black" alt="Deployed" />
</p>

---

## TL;DR for Reviewers

A full-stack, **production-deployed** RAG platform showcasing modern distributed-systems and applied-AI engineering. Built end-to-end — architecture, backend microservices, frontend, infra, deployment, and evals.

- **5 microservices** (Spring Boot 3 / Java 21, virtual threads) behind a JWT-secured API gateway
- **Hybrid retrieval** (pgvector HNSW + Postgres GIN full-text search) fused via **Reciprocal Rank Fusion**, then **LLM re-ranked**
- **Hallucination guard** — every answer is scored for faithfulness against retrieved context before it’s cached or returned
- **Async ingestion pipeline** (RabbitMQ → Tika → chunker → Mistral embeddings → pgvector) with DLQ + idempotent retries
- **Multi-tenant isolation** enforced at the SQL layer on every read
- **Streaming UX** — SSE token-by-token, ChatGPT-style attachments pinned to the message that sent them
- **Live deployment** on Render (backend) + Vercel (frontend) + Neon (Postgres) + Upstash (Redis) + CloudAMQP + Supabase Storage — zero credit cards
- **25-question eval harness** to regress retrieval and answer quality

---

## Engineering Highlights

| # | Capability | Why it matters |
|---|---|---|
| 1 | **Hybrid search + RRF + LLM re-rank** | Semantic recall (vectors) + lexical precision (BM25-style FTS), then an LLM scores 0–10 to surface the *most relevant* chunks within an 8K-char budget. |
| 2 | **Semantic cache w/ pgvector** | Cosine-similarity lookup at ≥ 0.92 returns cached answers in ms; auto-evicted on new ingestion to prevent stale results. |
| 3 | **Faithfulness-gated caching** | Only answers with grounding score ≥ 0.6 are cached — hallucinations don’t poison the cache. |
| 4 | **Conversation memory** | Sliding window + LLM-summarized older turns + cross-conversation FTS recall (GIN index on `messages.content`). |
| 5 | **Async, resilient ingestion** | RabbitMQ work queue, 3× retry with exponential backoff, dead-letter queue, idempotent doc IDs — a flaky parser never breaks the pipeline. |
| 6 | **Resilience4j circuit breakers** | Every external LLM/embedding call is wrapped; the system degrades gracefully when Groq or Mistral is rate-limited. |
| 7 | **Multi-tenant by design** | Every repository method takes `workspace_id`; no cross-tenant leak is even *possible* at the query layer. |
| 8 | **JWT + refresh + Redis blacklist** | Logout actually logs you out across all services; tokens are validated on every request. |
| 9 | **ChatGPT-style UX** | SSE streaming, instant uploads with progress, attachments pinned to the user message, follow-ups carry context server-side. |
| 10 | **Observability** | Micrometer → Prometheus → Grafana, correlation IDs propagated across services, structured logs. |

---

## Problem Statement

Enterprises sit on vast repositories of internal documents — policy manuals, product specs, HR handbooks, compliance guides — yet employees still spend **hours searching for answers** buried across PDFs, DOCXs, and wikis. Generic LLMs (ChatGPT, etc.) cannot answer questions about private company data, and naively feeding entire documents into an LLM prompt doesn't scale (context window limits, no source attribution, hallucinated answers).

**EAKP solves this** by providing a self-hosted, production-grade **Retrieval-Augmented Generation (RAG)** platform where teams can:

1. **Upload** internal documents (PDF, DOCX, TXT, PPTX, XLSX, CSV, HTML, Markdown)
2. **Ask questions** in natural language and receive accurate, **cited answers** streamed in real time
3. **Trust the answers** — every response cites its source document, and a secondary LLM validates each answer against the retrieved context to detect hallucinations
4. **Maintain data isolation** — multi-tenant workspaces ensure one team never sees another team's documents

---

## Live Demo

> **🚀 Try it now: [https://eakp.vercel.app](https://eakp.vercel.app)**
>
> **Frontend**: Vercel — React 19 SPA  
> **Backend**: Render.com — 4 Dockerized Spring Boot services  
> **Data**: Neon (Postgres + pgvector) · Upstash (Redis) · Supabase (S3 storage) · CloudAMQP (RabbitMQ)  
> See [DEPLOYMENT.md](./DEPLOYMENT.md) for the full free-tier playbook.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                    FRONTEND (React 19 + Vite)                      │
│         Vercel / Netlify · SSE Streaming · Zustand State          │
└───────────────────────────────┬──────────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────────┐
│                    API GATEWAY  :8080                              │
│          JWT Auth · Rate Limiting · OAuth (Google/GitHub)          │
└──────────┬──────────────┬────────────────┬───────────────────────┘
           │              │                │
┌──────────▼────┐  ┌──────▼──────────┐  ┌──▼───────────────────┐
│ Chat Service  │  │ Ingestion       │  │ Admin Service        │
│ :8081         │  │ Service :8082   │  │ :8083                │
│ Spring AI     │  │ Apache Tika     │  │ Analytics +          │
│ SSE Streaming │  │ RabbitMQ        │  │ Workspace Mgmt       │
└──────┬────────┘  └──────┬──────────┘  └──────────────────────┘
       │                   │
┌──────▼───────────────────▼───────────────────────────────────────┐
│                      RAG PIPELINE                                  │
│  Query Rewrite → Semantic Cache → Hybrid Search (BM25 + ANN)      │
│     → Reciprocal Rank Fusion → LLM Re-ranking → Context Trim     │
│     → LLM Streaming → Hallucination Guard → Cache Write           │
└──────┬──────────────┬───────────────┬────────────────────────────┘
       │              │               │
┌──────▼──────┐ ┌─────▼──────┐ ┌─────▼──────┐
│ PostgreSQL  │ │   Redis    │ │   MinIO    │
│ + pgvector  │ │  JWT Black │ │  Document  │
│ (vectors +  │ │  list +    │ │  Storage   │
│  BM25 FTS)  │ │  Sessions  │ │  (S3)     │
└─────────────┘ └────────────┘ └────────────┘
```

### Service Breakdown

| Service | Port | Responsibility |
|---|---|---|
| **Frontend** | 5173 | React 19 SPA, dark/light theme, SSE streaming chat, document management, admin dashboard |
| **API Gateway** | 8080 | JWT authentication, OAuth (Google/GitHub), token blacklisting (Redis), Bucket4j rate limiting, password reset |
| **Chat Service** | 8081 | RAG pipeline orchestration, query rewriting, SSE streaming, semantic cache, conversation memory with LLM summarisation, hallucination guard, circuit breakers |
| **Ingestion Service** | 8082 | Document upload to MinIO, text extraction (Apache Tika), recursive text chunking, batch embedding, pgvector storage, async processing via RabbitMQ |
| **Admin Service** | 8083 | Workspace analytics, RAG quality metrics (faithfulness distribution), document status monitoring, user management, audit logging |

---

## Key Features

### RAG Pipeline (the interesting bit)
- **Hybrid Search** — pgvector ANN (semantic) + PostgreSQL FTS (BM25/keyword) merged via **Reciprocal Rank Fusion**
- **Multi-Turn Query Rewriting** — resolves pronouns (“it”, “that doc”, “summarize this”) using conversation history + currently-attached documents
- **LLM-Based Re-ranking** — cross-encoder-style 0–10 relevance scoring on the candidate set
- **Context Trimming** — keeps only highest-relevance chunks within an 8K-char budget
- **Semantic Cache** — pgvector cosine lookup (threshold ≥ 0.92), evicted on new doc ingestion
- **Hallucination Guard** — NLI-style grounding validation with a faithfulness score
- **Confidence-Gated Caching** — only answers with faithfulness ≥ 0.6 are cached
- **Conversation Memory** — sliding window + LLM summarisation; GIN-FTS recall across other chats
- **Per-conversation document attachments** — docs uploaded in a chat are auto-boosted on every future turn in that chat, no re-attach needed

### Security
- JWT access + refresh tokens with **Redis blacklist enforced on every service** (not just the gateway)
- OAuth 2.0 (Google, GitHub) with server-side token verification
- Brute-force protection (account lockout), password complexity rules, BCrypt hashing
- Bucket4j rate limiting (per-user + per-IP)
- CORS, CSP, HSTS, X-Frame-Options
- **Multi-tenant workspace isolation** — every SQL query scoped by `workspace_id` at the repository layer
- GDPR-compliant cookie consent

### Frontend (React 19)
- Real-time **SSE streaming** with token-by-token display and graceful auto-reconnect
- **ChatGPT-style attachments** — upload runs in the background with live progress, chips pin to the user message on send, follow-ups still carry context
- Dark/light theme with subtle 3D motion
- Drag-and-drop uploads with progress tracking and client-side validation
- Conversation management (create, rename, delete, export to Markdown)
- Message feedback (👍 / 👎) feeding analytics
- Admin dashboard with RAG quality metrics (faithfulness distribution)
- Keyboard shortcuts, network-status awareness, error boundaries
- Cross-tab logout via `BroadcastChannel`
- Lazy-loaded routes, code splitting, Lighthouse-friendly

### Resilience
- **Resilience4j circuit breakers** on all LLM and embedding calls
- RabbitMQ **dead-letter queue** with 3× retry + exponential backoff
- Frontend: exponential backoff on 5xx, SSE auto-reconnect, offline detection
- Graceful shutdown on every service (in-flight requests drain)

### Observability
- Custom Micrometer metrics → Prometheus → Grafana (retrieval latency, faithfulness, cache hit ratio)
- **Correlation IDs** propagated across all services and into logs
- Structured JSON logging with thread + correlation context
- Audit log for admin actions

---

## Design Decisions Worth Calling Out

- **Hybrid search beats pure vector search.** Vector recall alone misses exact-match keyword queries (model names, error codes, acronyms). FTS alone misses paraphrases. RRF gives us both, and the LLM re-rank cleans up the merged set.
- **The LLM is not the source of truth — the retriever is.** The hallucination guard is a separate LLM call that scores how well the answer is supported by the retrieved context. Below 0.6 we don’t cache.
- **Async ingestion was non-negotiable.** Parsing a 50 MB PDF can take 30+ seconds. Putting that on the request thread would block uploads and kill UX. RabbitMQ + a worker decouples cleanly.
- **Java 21 virtual threads** let each service handle thousands of concurrent SSE streams without a reactive paradigm shift — plain `@RestController` code, massive concurrency.
- **Postgres + pgvector over a dedicated vector DB.** One database, one backup story, transactional consistency between docs/chunks/messages, free tier on Neon. The ergonomics outweigh the marginal performance gap at this scale.
- **Mistral `mistral-embed` (1024-dim) over local nomic-embed.** Higher recall on enterprise-style prose, free tier sufficient for a demo, no GPU needed in production.
- **Per-conversation attachments are server-authoritative.** The UI is just a presentation of `documents.conversation_id`. This means follow-up questions work even if the user clears the composer chips — there’s no client-side state the server depends on.

---

## Tech Stack

| Layer | Technology | Purpose |
|---|---|---|
| **Frontend** | React 19, Vite 8, Zustand, React Router 7 | SPA with SSE streaming |
| **Language** | Java 21 | Virtual threads, records, pattern matching, text blocks |
| **Framework** | Spring Boot 3.3.5 | Microservice foundation |
| **AI** | Spring AI 1.0.0-M6 | ChatClient, EmbeddingModel, VectorStore |
| **LLM (local)** | Ollama (deepseek-r1:7b) | Free local inference |
| **LLM (cloud)** | Groq (llama-3.3-70b-versatile) | Free-tier production LLM |
| **Embeddings** | nomic-embed-text (768d) / all-MiniLM-L6-v2 (384d) | Local / Cloud |
| **Vector DB** | PostgreSQL 16 + pgvector | HNSW index, cosine distance |
| **Full-Text Search** | PostgreSQL FTS + pg_trgm | BM25 ranking, trigram fuzzy match |
| **Cache** | Redis 7 | JWT blacklist, semantic cache, sessions |
| **Storage** | MinIO / Cloudflare R2 | S3-compatible document storage |
| **Queue** | RabbitMQ 3.13 | Async ingestion pipeline, DLQ |
| **Parsing** | Apache Tika 2.9.2 | PDF, DOCX, PPTX, XLSX, HTML, CSV, TXT, MD |
| **Security** | Spring Security + JJWT 0.12.6 | JWT, BCrypt, OAuth, RBAC |
| **Rate Limiting** | Bucket4j 8.10.1 | Per-user/IP token bucket |
| **Resilience** | Resilience4j | Circuit breakers, time limiters |
| **Monitoring** | Micrometer → Prometheus → Grafana | RAG metrics, SLIs |
| **Testing** | JUnit 5, Testcontainers 1.20.1 | Integration tests |
| **Container** | Docker Compose | Full local stack |

---

## Quick Start (Local Development)

### Prerequisites

```bash
# Java 21
sdk install java 21.0.5-tem

# Docker (for infrastructure)
# https://docs.docker.com/get-docker/

# Ollama (free local LLM)
curl -fsSL https://ollama.ai/install.sh | sh
ollama pull deepseek-r1:7b
ollama pull nomic-embed-text

# Node.js 20+ (for frontend)
# https://nodejs.org/
```

### 1. Start infrastructure

```bash
cd infra
docker compose up -d
docker compose ps   # all should show "healthy"
```

### 2. Start backend services

```bash
# From project root — each in a separate terminal
./mvnw spring-boot:run -pl api-gateway -Dspring-boot.run.profiles=local
./mvnw spring-boot:run -pl chat-service -Dspring-boot.run.profiles=local
./mvnw spring-boot:run -pl ingestion-service -Dspring-boot.run.profiles=local
./mvnw spring-boot:run -pl admin-service -Dspring-boot.run.profiles=local
```

### 3. Start frontend

```bash
cd frontend
npm install
npm run dev    # → http://localhost:5173
```

### 4. Login

| Email | Password | Role |
|---|---|---|
| `admin@eakp.local` | `password` | ADMIN |
| `user@eakp.local` | `password` | USER |

---

## Free Cloud Deployment (No Credit Card Required)

Deploy the full stack for **$0/month** — no credit card needed on any service:

| Component | Service | Free Tier |
|-----------|---------|-----------|
| Frontend | Vercel | Unlimited |
| Backend (4 services) | Render.com | 512MB each, sleeps after 15min |
| Database | Neon.tech | PostgreSQL + pgvector, 0.5GB |
| Redis | Upstash | 10K commands/day |
| Storage | Supabase Storage | 1GB (S3-compatible) |
| Queue | CloudAMQP | 1M msgs/month |
| LLM | Groq | 30 req/min |
| Embeddings | HuggingFace | Free inference API |

**👉 See [DEPLOYMENT.md](./DEPLOYMENT.md) for step-by-step instructions (all GitHub sign-in, zero credit cards).**

```bash
# Generate JWT secret
openssl rand -base64 32
```

---

## API Reference

### Authentication (API Gateway — :8080)

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Public | Register user + create workspace |
| `POST` | `/api/v1/auth/login` | Public | Login, returns access + refresh tokens |
| `POST` | `/api/v1/auth/refresh` | Public | Exchange refresh token for new pair |
| `POST` | `/api/v1/auth/logout` | Bearer | Blacklist tokens in Redis |
| `GET` | `/api/v1/auth/me` | Bearer | Current user profile |
| `PATCH` | `/api/v1/auth/profile` | Bearer | Update profile (fullName) |
| `POST` | `/api/v1/auth/change-password` | Bearer | Change password |
| `POST` | `/api/v1/auth/forgot-password` | Public | Request password reset email |
| `POST` | `/api/v1/auth/reset-password` | Public | Reset password with token |
| `POST` | `/api/v1/auth/oauth/github/callback` | Public | GitHub OAuth code exchange |
| `POST` | `/api/v1/auth/oauth/google/callback` | Public | Google ID token verification |

### Chat (Chat Service — :8081)

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/chat/stream` | Bearer | SSE streaming answer (JSON body) |
| `POST` | `/api/v1/chat/conversations` | Bearer | Create conversation |
| `GET` | `/api/v1/chat/conversations` | Bearer | List user's conversations |
| `GET` | `/api/v1/chat/conversations/{id}/messages` | Bearer | Get conversation history |
| `PATCH` | `/api/v1/chat/conversations/{id}` | Bearer | Rename conversation |
| `DELETE` | `/api/v1/chat/conversations/{id}` | Bearer | Delete conversation |
| `GET` | `/api/v1/chat/conversations/{id}/export` | Bearer | Export conversation |
| `POST` | `/api/v1/chat/conversations/{convId}/messages/{msgId}/feedback` | Bearer | Submit feedback |

### Documents (Ingestion Service — :8082)

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/documents/upload` | Bearer | Upload document (multipart) |
| `GET` | `/api/v1/documents` | Bearer | List workspace documents |
| `GET` | `/api/v1/documents/{id}` | Bearer | Get document status |
| `DELETE` | `/api/v1/documents/{id}` | Bearer | Delete document + vectors + storage |
| `GET` | `/api/v1/documents/{id}/download-url` | Bearer | Presigned download URL (1hr) |
| `POST` | `/api/v1/documents/{id}/reingest` | Bearer | Force re-ingestion |

### Admin (Admin Service — :8083)

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `GET` | `/api/v1/admin/health` | ADMIN | System-wide health |
| `GET` | `/api/v1/admin/workspaces` | ADMIN | All workspaces with stats |
| `GET` | `/api/v1/admin/workspaces/{id}/users` | ADMIN | List workspace users |
| `POST` | `/api/v1/admin/workspaces/{id}/cache/evict` | ADMIN | Force-clear semantic cache |
| `POST` | `/api/v1/admin/workspaces/{id}/users/{uid}/deactivate` | ADMIN | Deactivate user |
| `POST` | `/api/v1/admin/workspaces/{id}/users/{uid}/activate` | ADMIN | Activate user |
| `GET` | `/api/v1/admin/workspaces/{id}/rag-quality` | ADMIN | Workspace RAG quality |
| `GET` | `/api/v1/analytics/overview` | Bearer | Workspace stats |
| `GET` | `/api/v1/analytics/rag-quality` | Bearer | Faithfulness scores |
| `GET` | `/api/v1/analytics/usage?days=30` | Bearer | Usage analytics |
| `GET` | `/api/v1/analytics/documents` | Bearer | Document status breakdown |

### Workspace Config

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `GET` | `/api/v1/workspaces/current/config` | Bearer | Get workspace RAG settings |
| `PUT` | `/api/v1/workspaces/current/config` | Bearer | Update workspace RAG settings |

---

## Project Structure

```
eakp/
├── pom.xml                          Parent POM (Spring Boot 3.3.5 BOM)
├── render.yaml                      Render.com deployment blueprint
├── DEPLOYMENT.md                    Free deployment guide
├── CLAUDE.md                        AI coding instructions
│
├── frontend/                        React 19 + Vite 8 SPA
│   ├── vercel.json                  Vercel deployment config
│   ├── nginx.conf                   Production nginx (Docker)
│   ├── src/
│   │   ├── api/client.js            API client (auth, retry, SSE)
│   │   ├── store/                   Zustand stores (auth, chat, sidebar, toast)
│   │   ├── pages/                   Login, Register, Chat, Dashboard, Admin, Settings
│   │   ├── components/              Chat, Documents, Sidebar, Shared
│   │   ├── hooks/                   useNetworkStatus, useSessionExpiry, useAnimations
│   │   └── utils/                   cookies, sanitize, fileValidation, exportConversation
│   └── Dockerfile                   Multi-stage (node → nginx)
│
├── eakp-common/                     Shared library (JWT validator, models, filters)
│
├── api-gateway/                     :8080 — Auth, JWT, Rate Limiting, OAuth
├── chat-service/                    :8081 — RAG Pipeline, Streaming, Cache
├── ingestion-service/               :8082 — Document Upload, Parsing, Embedding
├── admin-service/                   :8083 — Analytics, Workspace Management
│
├── evals/                           RAG Quality Evaluation Suite
│   ├── eval_dataset.json            25 questions across 7 categories
│   └── run_evals.py                 Automated evaluation runner
│
└── infra/                           Infrastructure-as-Code
    ├── docker-compose.yml           Full local stack (8 services)
    ├── sql/init.sql                 Dev schema (768-dim vectors)
    ├── sql/init-prod.sql            Prod schema (384-dim vectors)
    └── monitoring/                  Prometheus + Grafana configs
```

---

## Environment Variables

| Variable | Default | Used By | Description |
|---|---|---|---|
| `PORT` | `8080-8083` | All | Server port (cloud platforms set this) |
| `DATABASE_URL` | — | All (prod) | Full JDBC connection string |
| `DB_HOST` | `localhost` | All | PostgreSQL host |
| `DB_NAME` | `eakp` | All | Database name |
| `DB_USER` | `eakp` | All | DB username |
| `DB_PASS` | `eakp_local_pass` | All | DB password |
| `REDIS_HOST` | `localhost` | All | Redis host |
| `REDIS_PASS` | — | All | Redis password |
| `JWT_SECRET` | (dev default) | All | Base64 HMAC-SHA256 key (≥32 bytes) |
| `CORS_ALLOWED_ORIGINS` | `localhost:5173` | All | Comma-separated origins |
| `GROQ_API_KEY` | — | Chat | Groq API key (prod) |
| `HF_API_KEY` | — | Chat, Ingestion | HuggingFace API token (prod) |
| `MINIO_ENDPOINT` | `http://localhost:9000` | Ingestion | S3-compatible endpoint |
| `MINIO_ACCESS_KEY` | `minioadmin` | Ingestion | Storage access key |
| `MINIO_SECRET_KEY` | `minioadmin123` | Ingestion | Storage secret key |
| `RABBITMQ_HOST` | `localhost` | Ingestion, Admin | RabbitMQ host |
| `RABBITMQ_USER` | `eakp` | Ingestion, Admin | RabbitMQ username |
| `RABBITMQ_PASS` | `eakp_local_pass` | Ingestion, Admin | RabbitMQ password |
| `GITHUB_CLIENT_ID` | — | Gateway | GitHub OAuth app ID |
| `GITHUB_CLIENT_SECRET` | — | Gateway | GitHub OAuth secret |
| `GOOGLE_CLIENT_ID` | — | Gateway | Google OAuth client ID |
| `VITE_API_BASE_URL` | — | Frontend | Backend URL (prod only) |

---

## Testing

```bash
# All modules (uses Testcontainers — requires Docker)
./mvnw verify

# Single module
./mvnw verify -pl chat-service

# Build only (skip tests)
./mvnw package -DskipTests

# Frontend linting
cd frontend && npm run lint
```

---

## RAG Evaluation

```bash
cd evals
pip install requests rich
python run_evals.py              # runs against local stack
python run_evals.py --max 5      # quick smoke test
```

| Metric | Target |
|---|---|
| Faithfulness | ≥ 0.75 |
| Answer Relevancy | ≥ 0.70 |
| Latency P50 | ≤ 2000ms |
| Latency P95 | ≤ 5000ms |
| Cache Hit Rate (2nd run) | ≥ 0.20 |

---

## Observability

With the local stack running:

- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000 (admin/admin)

```promql
# Cache hit rate
rate(rag_cache_hits_total[5m]) / (rate(rag_cache_hits_total[5m]) + rate(rag_cache_misses_total[5m]))

# P95 pipeline latency
histogram_quantile(0.95, rate(rag_pipeline_latency_seconds_bucket[5m]))

# Faithfulness score
rate(rag_faithfulness_score_sum[5m]) / rate(rag_faithfulness_score_count[5m])
```