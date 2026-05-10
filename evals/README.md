# EAKP RAG Evaluation

Measures RAG quality using a 25-question dataset across 7 categories.

## Quick start

```bash
# Install deps
pip install requests rich

# Run against local stack (auto-login)
python run_evals.py

# Run against prod with token
python run_evals.py \
  --base-url https://your-chat-service.railway.app \
  --token eyJhbGc...

# Quick smoke test (5 questions)
python run_evals.py --max 5
```

## Metrics

| Metric | Formula | Target |
|--------|---------|--------|
| Faithfulness | Keyword + ground-truth overlap | >= 0.75 |
| Answer Relevancy | Question-answer word overlap | >= 0.70 |
| Latency P50 | Median response time | <= 2000ms |
| Latency P95 | 95th percentile response time | <= 5000ms |
| Cache Hit Rate | Cache hits / total queries | >= 0.20 |

## Upgrading to full RAGAS

Replace heuristic scorers with the `ragas` library for production-grade evaluation:

```bash
pip install ragas langchain-openai

# Then in run_evals.py, replace score_faithfulness_heuristic() with:
from ragas.metrics import faithfulness, answer_relevancy
from ragas import evaluate
```

## Adding questions

Edit `eval_dataset.json`. Each entry needs:
- `id`: unique string
- `question`: natural language question
- `ground_truth`: expected correct answer
- `context_keywords`: key terms the answer should contain
- `category`: grouping label

## Results

After running, `eval_results.json` is created with per-question scores.
Import into Excel or use with Grafana for trend tracking across releases.
