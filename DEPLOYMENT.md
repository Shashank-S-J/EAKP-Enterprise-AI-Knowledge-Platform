# EAKP Free Deployment Guide (No Credit Card Required)

## Architecture (100% Free — No Credit Card)

| Component | Service | Free Tier | Credit Card? |
|-----------|---------|-----------|:---:|
| **Frontend** | Vercel | Unlimited static hosting | ❌ No |
| **Backend (4 services)** | Render.com | 750 hrs/month, 512MB RAM | ❌ No |
| **Database (PostgreSQL + pgvector)** | Neon.tech | 0.5GB, pgvector included | ❌ No |
| **Redis** | Upstash | 10K commands/day | ❌ No |
| **Object Storage** | Supabase Storage | 1GB free | ❌ No |
| **Message Queue** | CloudAMQP | Lemur plan (1M msgs/month) | ❌ No |
| **LLM** | Groq | 30 req/min, llama-3.3-70b | ❌ No |
| **Embeddings** | HuggingFace Inference API | Free (rate limited) | ❌ No |

**Total cost: $0/month — No credit card needed anywhere.**

---

## Step 1: Create Free Accounts (No Credit Card)

Sign up with just email/GitHub on all of these:

1. **Neon.tech** → https://neon.tech (sign in with GitHub)
2. **Upstash** → https://upstash.com (sign in with GitHub)
3. **Render.com** → https://render.com (sign in with GitHub)
4. **Vercel** → https://vercel.com (sign in with GitHub)
5. **Supabase** → https://supabase.com (sign in with GitHub — free storage)
6. **CloudAMQP** → https://www.cloudamqp.com (sign in with Google/GitHub)
7. **Groq** → https://console.groq.com (sign up with email)
8. **HuggingFace** → https://huggingface.co/join (sign up with email)

> 💡 **Tip**: Sign up with GitHub on all services — it's fastest and requires no email verification.

---

## Step 2: Set Up Database (Neon.tech)

Neon provides free PostgreSQL **with pgvector extension pre-installed**.

1. Go to https://neon.tech → Sign in with GitHub
2. Create a new project:
   - **Name**: `eakp`
   - **Region**: Pick closest to you (e.g., `us-east-2`)
   - **PostgreSQL version**: 16
3. Copy the **connection string** from the dashboard:
   ```
   postgresql://neondb_owner:abc123@ep-cool-name-12345.us-east-2.aws.neon.tech/neondb?sslmode=require
   ```
4. Go to **SQL Editor** and paste the entire contents of [`infra/sql/init-prod.sql`](./infra/sql/init-prod.sql)
5. Click **Run** — this creates all tables with 384-dimension vectors

> ⚠️ **Important**: Add `?pgbouncer=true&sslmode=require` to the URL for connection pooling.

---

## Step 3: Set Up Redis (Upstash)

1. Go to https://console.upstash.com → Sign in with GitHub
2. Click **Create Database**:
   - **Name**: `eakp-redis`
   - **Region**: Same as Neon (e.g., `us-east-1`)
   - **Type**: Regional
3. From the database details page, note:
   - **Endpoint**: `upstash-redis-xxx.upstash.io` → this is `REDIS_HOST`
   - **Port**: `6379` → this is `REDIS_PORT`
   - **Password**: `xxxxxxxxx` → this is `REDIS_PASS`

---

## Step 4: Set Up Object Storage (Supabase Storage)

Supabase gives you **1GB free S3-compatible storage** — no credit card.

1. Go to https://supabase.com → Sign in with GitHub
2. Create a new project:
   - **Name**: `eakp-storage`
   - **Region**: Same as above
   - **Database password**: (anything — we only use Storage)
3. Go to **Settings** → **API** and note:
   - **Project URL**: `https://xxxxx.supabase.co`
4. Go to **Storage** → Create a new **bucket** named `eakp-documents` (set to **private**)
5. Go to **Settings** → **Storage** → **S3 Connection**:
   - **Endpoint**: `https://xxxxx.supabase.co/storage/v1/s3`
   - **Access Key**: (generate from Storage Settings → S3 access keys)
   - **Secret Key**: (shown once on generation)
   - **Region**: your project region

These map to:
```
MINIO_ENDPOINT=https://xxxxx.supabase.co/storage/v1/s3
MINIO_ACCESS_KEY=<supabase-s3-access-key>
MINIO_SECRET_KEY=<supabase-s3-secret-key>
MINIO_BUCKET=eakp-documents
MINIO_REGION=<your-region>
```

---

## Step 5: Set Up RabbitMQ (CloudAMQP)

1. Go to https://www.cloudamqp.com → Sign in with GitHub
2. Create a new instance:
   - **Plan**: Lemur (Free)
   - **Name**: `eakp-queue`
   - **Region**: Same as above
3. From the instance details, get the **AMQP URL**:
   ```
   amqps://username:password@rattlesnake.rmq.cloudamqp.com/username
   ```
4. Extract these values:
   - `RABBITMQ_HOST=rattlesnake.rmq.cloudamqp.com`
   - `RABBITMQ_PORT=5672`
   - `RABBITMQ_USER=username`
   - `RABBITMQ_PASS=password`

> Note: CloudAMQP uses the username as the vhost. You may need to set `RABBITMQ_VHOST` to the same value as the username.

---

## Step 6: Get API Keys

### Groq (LLM — free, no credit card)
1. Go to https://console.groq.com → Sign up
2. Go to **API Keys** → Create new key
3. Copy it → this is `GROQ_API_KEY`

### HuggingFace (Embeddings — free, no credit card)
1. Go to https://huggingface.co/settings/tokens
2. Create a new token with **Read** access
3. Copy it → this is `HF_API_KEY`

---

## Step 7: Deploy Backend (Render.com)

### Option A: Blueprint (Easiest)
1. Push your code to GitHub
2. Go to https://dashboard.render.com → **New** → **Blueprint**
3. Connect your GitHub repo → Render finds `render.yaml`
4. Fill in the environment variables (see below)
5. Click **Apply** — all 4 services deploy automatically

### Option B: Manual (per service)
For each of the 4 services:

1. Go to https://dashboard.render.com → **New** → **Web Service**
2. Connect your GitHub repo
3. Set:
   - **Name**: `eakp-api-gateway` (or chat-service, ingestion-service, admin-service)
   - **Runtime**: Docker
   - **Root Directory**: `.` (project root)
   - **Dockerfile Path**: `api-gateway/Dockerfile` (change per service)
4. Add these **Environment Variables**:

#### All 4 services need:
```
SPRING_PROFILES_ACTIVE=prod
DATABASE_URL=jdbc:postgresql://ep-xxx.neon.tech/neondb?sslmode=require&pgbouncer=true
DB_USER=neondb_owner
DB_PASS=<neon-password>
REDIS_HOST=<upstash-host>
REDIS_PORT=6379
REDIS_PASS=<upstash-password>
JWT_SECRET=<see below>
CORS_ALLOWED_ORIGINS=https://your-app.vercel.app
```

#### chat-service additionally needs:
```
GROQ_API_KEY=<your-groq-key>
HF_API_KEY=<your-huggingface-token>
RABBITMQ_HOST=<cloudamqp-host>
RABBITMQ_PORT=5672
RABBITMQ_USER=<cloudamqp-user>
RABBITMQ_PASS=<cloudamqp-pass>
```

#### ingestion-service additionally needs:
```
HF_API_KEY=<your-huggingface-token>
RABBITMQ_HOST=<cloudamqp-host>
RABBITMQ_PORT=5672
RABBITMQ_USER=<cloudamqp-user>
RABBITMQ_PASS=<cloudamqp-pass>
MINIO_ENDPOINT=https://xxxxx.supabase.co/storage/v1/s3
MINIO_ACCESS_KEY=<supabase-s3-key>
MINIO_SECRET_KEY=<supabase-s3-secret>
MINIO_BUCKET=eakp-documents
```

#### admin-service additionally needs:
```
RABBITMQ_HOST=<cloudamqp-host>
RABBITMQ_PORT=5672
RABBITMQ_USER=<cloudamqp-user>
RABBITMQ_PASS=<cloudamqp-pass>
```

### Generate JWT Secret (run in any terminal):
```bash
# Option 1: OpenSSL
openssl rand -base64 32

# Option 2: PowerShell (Windows)
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }) -as [byte[]])

# Option 3: Python
python -c "import secrets,base64;print(base64.b64encode(secrets.token_bytes(32)).decode())"
```

> ⚠️ **Use the SAME JWT_SECRET on all 4 services!**

---

## Step 8: Deploy Frontend (Vercel)

1. Go to https://vercel.com/new → Sign in with GitHub
2. Click **Import** next to your repo
3. Set:
   - **Framework Preset**: Vite
   - **Root Directory**: `frontend`
   - **Build Command**: `npm run build`
   - **Output Directory**: `dist`
4. Add **Environment Variable**:
   ```
   VITE_API_BASE_URL=https://eakp-api-gateway.onrender.com
   ```
   (Replace with your actual Render API gateway URL)
5. Click **Deploy**

---

## Step 9: Update CORS Origins

After both frontend and backend are deployed:

1. Note your Vercel URL (e.g., `https://eakp-xyz.vercel.app`)
2. Go to Render dashboard → each of the 4 services → **Environment**
3. Update `CORS_ALLOWED_ORIGINS` to your Vercel URL:
   ```
   CORS_ALLOWED_ORIGINS=https://eakp-xyz.vercel.app
   ```
4. Click **Save Changes** (services will auto-redeploy)

---

## Step 10: Verify Deployment

1. ✅ Visit `https://your-app.vercel.app` → should show login page
2. ✅ Register a new account (use a real email)
3. ✅ Upload a small document (PDF or TXT)
4. ✅ Wait for status to change from PENDING → READY
5. ✅ Ask a question → should get a streamed answer with citations

---

## Troubleshooting

### "Service unavailable" or slow first load
Render free tier **sleeps after 15 minutes**. First request takes ~30 seconds. Solutions:
- Wait 30s and retry
- Use https://cron-job.org (free, no credit card) to ping all 4 health endpoints every 14 minutes:
  - `https://eakp-api-gateway.onrender.com/actuator/health`
  - `https://eakp-chat-service.onrender.com/actuator/health`
  - `https://eakp-ingestion-service.onrender.com/actuator/health`
  - `https://eakp-admin-service.onrender.com/actuator/health`

### "Database connection refused"
- Ensure `?sslmode=require` is in your DATABASE_URL
- Neon free tier suspends after 5 minutes of inactivity — first query wakes it (~1s)

### "RabbitMQ connection failed"
- CloudAMQP Lemur uses `amqps://` (TLS on port 5671, not 5672)
- Try setting `RABBITMQ_PORT=5671` and ensure your Spring config supports TLS

### "Embedding/LLM timeout"
- Groq and HuggingFace free tiers have rate limits
- Circuit breakers handle this gracefully — requests will retry after 15–30s

### "Upload fails"
- Supabase S3 endpoint format: `https://<project-ref>.supabase.co/storage/v1/s3`
- Ensure the `eakp-documents` bucket exists and your S3 keys have write access

---

## Architecture Diagram (Deployed)

```
┌─────────────────┐     ┌──────────────────────────────────────┐
│  Vercel (Free)  │     │    Render.com (Free × 4 services)    │
│  React SPA      │────▶│  api-gateway  │  chat-service        │
│  Static Hosting │     │  ingestion    │  admin-service       │
└─────────────────┘     └──────┬────────┬──────────┬───────────┘
                               │        │          │
                    ┌──────────▼──┐  ┌──▼────┐  ┌──▼──────────┐
                    │  Neon.tech  │  │Upstash│  │  Supabase   │
                    │  PostgreSQL │  │ Redis │  │  Storage    │
                    │  + pgvector │  │       │  │  (S3)       │
                    └─────────────┘  └───────┘  └─────────────┘
                                        │
                    ┌───────────────┐    │     ┌───────────────┐
                    │  CloudAMQP   │    │     │  Groq + HF    │
                    │  RabbitMQ    │────┘     │  LLM + Embed  │
                    └───────────────┘          └───────────────┘
```

**All services: Free tier, no credit card required.**

---

## Alternative: Railway.app (also no credit card)

If Render's sleep-after-15min is too annoying, **Railway.app** gives a free trial with $5 credit (no credit card needed for the trial). It's enough to run all 4 services 24/7 for about a week to demo the project.

1. https://railway.app → Sign in with GitHub
2. **New Project** → **Deploy from GitHub repo**
3. Add services for each Dockerfile
4. Add PostgreSQL, Redis plugins directly from Railway dashboard
5. Set the same env vars as above
