#!/usr/bin/env python3
"""
EAKP RAG Evaluation Runner
===========================
Runs the eval_dataset.json against the live chat-service and measures:
  - Faithfulness     (is the answer grounded in context?)
  - Answer Relevancy (does the answer address the question?)
  - Latency P50/P95  (response time)
  - Cache Hit Rate   (how often semantic cache fires)

Usage:
  pip install requests rich tabulate
  python run_evals.py --base-url http://localhost:8081 --token <jwt>

Or with auto-login:
  python run_evals.py --gateway-url http://localhost:8080 \
      --email admin@eakp.local --password password
"""

import json
import time
import argparse
import statistics
import sys
from pathlib import Path

try:
    import requests
    from rich.console import Console
    from rich.table import Table
    from rich.progress import track
    from rich import print as rprint
except ImportError:
    print("Install deps: pip install requests rich tabulate")
    sys.exit(1)

console = Console()

# ── Config ────────────────────────────────────────────────────────────────────

DATASET_PATH = Path(__file__).parent / "eval_dataset.json"
RESULTS_PATH = Path(__file__).parent / "eval_results.json"


# ── Auth ──────────────────────────────────────────────────────────────────────

def login(gateway_url: str, email: str, password: str) -> str:
    """Login and return access token."""
    resp = requests.post(f"{gateway_url}/api/v1/auth/login",
                         json={"email": email, "password": password})
    resp.raise_for_status()
    token = resp.json()["accessToken"]
    console.print(f"[green]✓ Logged in as {email}[/green]")
    return token


# ── Conversation ──────────────────────────────────────────────────────────────

def create_conversation(base_url: str, token: str) -> str:
    """Create a fresh conversation and return its ID."""
    resp = requests.post(f"{base_url}/api/v1/chat/conversations",
                         headers={"Authorization": f"Bearer {token}"},
                         json={"title": "Evaluation run"})
    resp.raise_for_status()
    return resp.json()["id"]


def ask_question(base_url: str, token: str, conv_id: str,
                 question: str) -> dict:
    """Send a question and collect the full streamed response."""
    url = (f"{base_url}/api/v1/chat/stream"
           f"?conversationId={conv_id}&message={requests.utils.quote(question)}")

    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "text/event-stream"
    }

    start = time.time()
    tokens = []
    cache_hit = False

    with requests.get(url, headers=headers, stream=True, timeout=60) as resp:
        resp.raise_for_status()
        for line in resp.iter_lines():
            if not line:
                continue
            line = line.decode("utf-8")
            if line.startswith("event: done"):
                break
            if line.startswith("event: error"):
                return {"error": "Stream error", "latency_ms": 0,
                        "answer": "", "cache_hit": False}
            if line.startswith("data: "):
                token_text = line[6:]
                if token_text != "[DONE]":
                    tokens.append(token_text)

    latency_ms = (time.time() - start) * 1000
    answer = "".join(tokens).strip()

    return {
        "answer": answer,
        "latency_ms": latency_ms,
        "cache_hit": cache_hit,
        "token_count": len(tokens)
    }


# ── Scoring ───────────────────────────────────────────────────────────────────

def score_answer_relevancy(question: str, answer: str) -> float:
    """
    Heuristic answer relevancy score (0-1).
    Production: use an LLM judge or embedding cosine similarity
    between question and answer.
    """
    if not answer or len(answer.strip()) < 10:
        return 0.0

    q_words = set(question.lower().split())
    a_words  = set(answer.lower().split())

    # Word overlap ratio (Jaccard-like)
    if not q_words:
        return 0.5

    common = q_words & a_words
    overlap = len(common) / len(q_words)

    # Penalise "I don't know" type responses
    refusal_phrases = [
        "i don't have", "i cannot", "not in the context",
        "no information", "i'm unable"
    ]
    if any(p in answer.lower() for p in refusal_phrases):
        return max(0.0, overlap - 0.3)

    # Reward longer answers (more informative)
    length_bonus = min(0.2, len(answer) / 2000)

    return min(1.0, overlap + length_bonus)


def score_faithfulness_heuristic(answer: str, ground_truth: str,
                                  keywords: list) -> float:
    """
    Heuristic faithfulness: checks if answer contains key concepts from
    the ground truth. Production: use NLI model or LLM judge.
    """
    if not answer:
        return 0.0

    answer_lower  = answer.lower()
    gt_lower      = ground_truth.lower()
    gt_words      = set(gt_lower.split())
    answer_words  = set(answer_lower.split())

    # Keyword coverage
    kw_hits = sum(1 for kw in keywords if kw.lower() in answer_lower)
    kw_score = kw_hits / max(len(keywords), 1)

    # Ground truth word overlap
    common   = gt_words & answer_words
    gt_score = len(common) / max(len(gt_words), 1)

    return min(1.0, (kw_score * 0.6 + gt_score * 0.4))


# ── Main evaluation loop ──────────────────────────────────────────────────────

def run_evaluation(base_url: str, token: str,
                   dataset: list, max_questions: int = None) -> dict:
    """Run all evaluation questions and return aggregated results."""

    if max_questions:
        dataset = dataset[:max_questions]

    results = []
    latencies  = []
    faiths     = []
    relevancies = []
    cache_hits  = 0

    console.print(f"\n[bold]Running {len(dataset)} evaluation questions...[/bold]\n")

    for item in track(dataset, description="Evaluating..."):
        # Fresh conversation per question (no context bleed)
        try:
            conv_id = create_conversation(base_url, token)
        except Exception as e:
            console.print(f"[red]Failed to create conversation: {e}[/red]")
            continue

        # Ask question
        try:
            response = ask_question(base_url, token, conv_id, item["question"])
        except Exception as e:
            console.print(f"[red]Failed on Q{item['id']}: {e}[/red]")
            continue

        if "error" in response:
            console.print(f"[yellow]Error on Q{item['id']}: {response['error']}[/yellow]")
            continue

        # Score
        faithfulness  = score_faithfulness_heuristic(
            response["answer"], item["ground_truth"], item["context_keywords"])
        relevancy     = score_answer_relevancy(item["question"], response["answer"])
        latency       = response["latency_ms"]

        if response.get("cache_hit"):
            cache_hits += 1

        latencies.append(latency)
        faiths.append(faithfulness)
        relevancies.append(relevancy)

        result = {
            "id":            item["id"],
            "category":      item["category"],
            "question":      item["question"],
            "answer":        response["answer"][:300] + "..."
                             if len(response["answer"]) > 300
                             else response["answer"],
            "faithfulness":  round(faithfulness, 3),
            "relevancy":     round(relevancy, 3),
            "latency_ms":    round(latency, 1),
            "cache_hit":     response.get("cache_hit", False)
        }
        results.append(result)

    # ── Aggregate ─────────────────────────────────────────────────────────────

    if not results:
        console.print("[red]No results collected.[/red]")
        return {}

    latencies.sort()
    summary = {
        "total_questions": len(results),
        "avg_faithfulness":  round(statistics.mean(faiths), 3),
        "avg_relevancy":     round(statistics.mean(relevancies), 3),
        "latency_p50_ms":    round(latencies[len(latencies) // 2], 1),
        "latency_p95_ms":    round(latencies[int(len(latencies) * 0.95)], 1),
        "latency_max_ms":    round(max(latencies), 1),
        "cache_hit_rate":    round(cache_hits / len(results), 3),
        "low_faithfulness":  sum(1 for f in faiths if f < 0.5),
        "results":           results
    }

    # By category
    categories = {}
    for r in results:
        cat = r["category"]
        categories.setdefault(cat, {"faithfulness": [], "relevancy": []})
        categories[cat]["faithfulness"].append(r["faithfulness"])
        categories[cat]["relevancy"].append(r["relevancy"])

    summary["by_category"] = {
        cat: {
            "avg_faithfulness": round(statistics.mean(v["faithfulness"]), 3),
            "avg_relevancy":    round(statistics.mean(v["relevancy"]), 3),
            "count":            len(v["faithfulness"])
        }
        for cat, v in categories.items()
    }

    return summary


# ── Reporting ─────────────────────────────────────────────────────────────────

def print_report(summary: dict):
    console.print("\n" + "═" * 60)
    console.print("[bold cyan]  EAKP RAG Evaluation Report[/bold cyan]")
    console.print("═" * 60)

    # Overall metrics
    table = Table(title="Overall Metrics", show_header=True,
                  header_style="bold magenta")
    table.add_column("Metric", style="cyan")
    table.add_column("Value", style="green")
    table.add_column("Target", style="yellow")
    table.add_column("Status")

    def status(val, target, higher_better=True):
        if higher_better:
            return "✅" if val >= target else "⚠️"
        return "✅" if val <= target else "⚠️"

    af   = summary["avg_faithfulness"]
    ar   = summary["avg_relevancy"]
    p50  = summary["latency_p50_ms"]
    p95  = summary["latency_p95_ms"]
    chr_ = summary["cache_hit_rate"]

    table.add_row("Avg Faithfulness",  str(af),   ">= 0.75",  status(af, 0.75))
    table.add_row("Avg Relevancy",     str(ar),   ">= 0.70",  status(ar, 0.70))
    table.add_row("Latency P50 (ms)",  str(p50),  "<= 2000",  status(p50, 2000, False))
    table.add_row("Latency P95 (ms)",  str(p95),  "<= 5000",  status(p95, 5000, False))
    table.add_row("Cache Hit Rate",    str(chr_), ">= 0.20",  status(chr_, 0.20))
    table.add_row("Low Faithfulness",
                  str(summary["low_faithfulness"]),
                  "== 0", "✅" if summary["low_faithfulness"] == 0 else "⚠️")

    console.print(table)

    # By category
    cat_table = Table(title="\nBy Category", show_header=True,
                      header_style="bold blue")
    cat_table.add_column("Category")
    cat_table.add_column("Count")
    cat_table.add_column("Faithfulness")
    cat_table.add_column("Relevancy")

    for cat, metrics in sorted(summary["by_category"].items()):
        cat_table.add_row(
            cat,
            str(metrics["count"]),
            str(metrics["avg_faithfulness"]),
            str(metrics["avg_relevancy"])
        )
    console.print(cat_table)

    # Bottom-5 faithfulness
    worst = sorted(summary["results"],
                   key=lambda r: r["faithfulness"])[:5]
    if worst:
        console.print("\n[bold red]Lowest Faithfulness Answers:[/bold red]")
        for r in worst:
            console.print(
                f"  [{r['id']}] faith={r['faithfulness']} "
                f"Q: {r['question'][:60]}...")

    console.print(f"\n[bold]Results saved to:[/bold] {RESULTS_PATH}")
    console.print("═" * 60 + "\n")


# ── Entry point ───────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="EAKP RAG Evaluator")
    parser.add_argument("--base-url",     default="http://localhost:8081")
    parser.add_argument("--gateway-url",  default="http://localhost:8080")
    parser.add_argument("--token",        default=None, help="JWT access token")
    parser.add_argument("--email",        default="admin@eakp.local")
    parser.add_argument("--password",     default="password")
    parser.add_argument("--max",          type=int, default=None,
                        help="Limit to N questions (for quick smoke test)")
    args = parser.parse_args()

    # Auth
    if args.token:
        token = args.token
    else:
        token = login(args.gateway_url, args.email, args.password)

    # Load dataset
    dataset = json.loads(DATASET_PATH.read_text())
    console.print(f"[cyan]Loaded {len(dataset)} evaluation questions[/cyan]")

    # Run
    summary = run_evaluation(args.base_url, token, dataset, args.max)
    if not summary:
        sys.exit(1)

    # Save
    RESULTS_PATH.write_text(json.dumps(summary, indent=2))

    # Print
    print_report(summary)

    # Exit code: non-zero if faithfulness < 0.6
    if summary["avg_faithfulness"] < 0.6:
        console.print("[red]FAIL: Average faithfulness below 0.6[/red]")
        sys.exit(1)


if __name__ == "__main__":
    main()
