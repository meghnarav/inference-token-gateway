#!/usr/bin/env python3
"""
load_test.py — Concurrent load simulator for the Inference Token Gateway

Simulates multiple users sending parallel requests to demonstrate:
  - Rate limiting enforcement per user
  - Cache hit behavior on repeated prompts
  - System behavior under concurrent load

Usage:
  python3 load_test.py [--host HOST] [--users N] [--requests-per-user N] [--concurrency N]

Examples:
  python3 load_test.py                                  # defaults
  python3 load_test.py --users 5 --requests-per-user 20
  python3 load_test.py --host http://localhost:8080 --concurrency 10
"""

import argparse
import concurrent.futures
import json
import random
import time
import uuid
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional
import urllib.request
import urllib.error

# ─── Configuration ────────────────────────────────────────────────────────────

PROMPTS = [
    "What is distributed systems engineering?",
    "Explain the CAP theorem with examples",
    "How does Redis implement sorted sets?",
    "What is the sliding window rate limiting algorithm?",
    "Explain idempotency in distributed systems",
    "What is eventual consistency?",
    "How do token buckets work in rate limiting?",
    "What is the difference between Redis and PostgreSQL?",
    "Explain the circuit breaker pattern",
    "What is Bloom filter and when should you use it?",
]

USERS = ["user-alice", "user-bob", "user-carol", "user-dave",
         "user-eve", "user-frank", "user-grace", "user-henry"]


# ─── Data Classes ─────────────────────────────────────────────────────────────

@dataclass
class RequestResult:
    user_id: str
    prompt: str
    status_code: int
    latency_ms: float
    tokens_consumed: int = 0
    cache_hit: bool = False
    remaining_tokens: int = 0
    error: Optional[str] = None
    request_id: str = ""


@dataclass
class UserStats:
    user_id: str
    total_requests: int = 0
    success_count: int = 0
    rate_limited_count: int = 0
    error_count: int = 0
    total_tokens: int = 0
    cache_hits: int = 0
    latencies: list = field(default_factory=list)


# ─── HTTP Helper ──────────────────────────────────────────────────────────────

def post_generate(host: str, user_id: str, prompt: str,
                  idempotency_key: Optional[str] = None) -> RequestResult:
    url = f"{host}/generate"
    payload = {"userId": user_id, "prompt": prompt}
    if idempotency_key:
        payload["idempotencyKey"] = idempotency_key

    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=body,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST"
    )

    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            latency_ms = (time.monotonic() - start) * 1000
            data = json.loads(resp.read())
            return RequestResult(
                user_id=user_id,
                prompt=prompt,
                status_code=resp.status,
                latency_ms=latency_ms,
                tokens_consumed=data.get("tokensConsumed", 0),
                cache_hit=data.get("cacheHit", False),
                remaining_tokens=data.get("remainingTokensThisMinute", 0),
                request_id=data.get("requestId", ""),
            )
    except urllib.error.HTTPError as e:
        latency_ms = (time.monotonic() - start) * 1000
        try:
            err_body = json.loads(e.read())
            error_msg = err_body.get("message", str(e))
        except Exception:
            error_msg = str(e)
        return RequestResult(
            user_id=user_id, prompt=prompt,
            status_code=e.code, latency_ms=latency_ms, error=error_msg
        )
    except Exception as e:
        latency_ms = (time.monotonic() - start) * 1000
        return RequestResult(
            user_id=user_id, prompt=prompt,
            status_code=0, latency_ms=latency_ms, error=str(e)
        )


def get_usage(host: str, user_id: str) -> dict:
    url = f"{host}/usage/{user_id}"
    try:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read())
    except Exception as e:
        return {"error": str(e)}


# ─── Worker ───────────────────────────────────────────────────────────────────

def user_worker(host: str, user_id: str, num_requests: int,
                repeat_ratio: float = 0.3) -> list[RequestResult]:
    """
    Simulates a single user sending num_requests to the gateway.

    repeat_ratio: fraction of requests that reuse a previously-sent prompt
                  (tests cache hit behavior)
    """
    results = []
    sent_prompts = []

    for i in range(num_requests):
        # Decide prompt: repeat an old one or pick a new one
        if sent_prompts and random.random() < repeat_ratio:
            prompt = random.choice(sent_prompts)
        else:
            prompt = random.choice(PROMPTS)
            sent_prompts.append(prompt)

        # Small jitter to avoid perfectly synchronized bursts
        time.sleep(random.uniform(0.05, 0.2))

        result = post_generate(host, user_id, prompt)
        results.append(result)

        status_icon = "✓" if result.status_code == 200 else ("⚡" if result.status_code == 429 else "✗")
        cache_icon  = "🔵" if result.cache_hit else "  "
        print(f"  {status_icon} {cache_icon} [{user_id}] req {i+1:02d}/{num_requests} "
              f"status={result.status_code} tokens={result.tokens_consumed} "
              f"latency={result.latency_ms:.0f}ms remaining={result.remaining_tokens}")

    return results


# ─── Aggregation & Reporting ──────────────────────────────────────────────────

def aggregate_results(all_results: list[RequestResult]) -> dict[str, UserStats]:
    stats: dict[str, UserStats] = {}
    for r in all_results:
        if r.user_id not in stats:
            stats[r.user_id] = UserStats(user_id=r.user_id)
        s = stats[r.user_id]
        s.total_requests += 1
        s.latencies.append(r.latency_ms)
        if r.status_code == 200:
            s.success_count += 1
            s.total_tokens += r.tokens_consumed
            if r.cache_hit:
                s.cache_hits += 1
        elif r.status_code == 429:
            s.rate_limited_count += 1
        else:
            s.error_count += 1
    return stats


def print_report(stats: dict[str, UserStats], wall_time_s: float):
    print("\n" + "═" * 70)
    print("  LOAD TEST RESULTS")
    print("═" * 70)

    total_req = total_success = total_rl = total_tokens = total_cache = 0

    for user_id, s in sorted(stats.items()):
        p50 = sorted(s.latencies)[len(s.latencies) // 2] if s.latencies else 0
        p99 = sorted(s.latencies)[int(len(s.latencies) * 0.99)] if s.latencies else 0
        cache_pct = (s.cache_hits / s.success_count * 100) if s.success_count else 0

        print(f"\n  User: {user_id}")
        print(f"    Requests:     {s.total_requests}  "
              f"(✓ {s.success_count}  ⚡ {s.rate_limited_count}  ✗ {s.error_count})")
        print(f"    Tokens Used:  {s.total_tokens:,}")
        print(f"    Cache Hits:   {s.cache_hits} ({cache_pct:.0f}%)")
        print(f"    Latency:      p50={p50:.0f}ms  p99={p99:.0f}ms")

        total_req     += s.total_requests
        total_success += s.success_count
        total_rl      += s.rate_limited_count
        total_tokens  += s.total_tokens
        total_cache   += s.cache_hits

    overall_cache_pct = (total_cache / total_success * 100) if total_success else 0
    rps = total_req / wall_time_s if wall_time_s > 0 else 0

    print("\n" + "─" * 70)
    print(f"  TOTALS | Wall time: {wall_time_s:.1f}s | Throughput: {rps:.1f} req/s")
    print(f"  Requests:  {total_req}  "
          f"(✓ {total_success}  ⚡ rate-limited {total_rl})")
    print(f"  Tokens:    {total_tokens:,}")
    print(f"  Cache Hit: {total_cache} ({overall_cache_pct:.0f}%)")
    print("═" * 70)


# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Gateway load simulator")
    parser.add_argument("--host",               default="http://localhost:8080")
    parser.add_argument("--users",              type=int, default=4)
    parser.add_argument("--requests-per-user",  type=int, default=10)
    parser.add_argument("--concurrency",        type=int, default=4)
    parser.add_argument("--repeat-ratio",       type=float, default=0.4,
                        help="Fraction of requests reusing a prior prompt (tests cache)")
    args = parser.parse_args()

    selected_users = random.sample(USERS, min(args.users, len(USERS)))

    print(f"\n{'═' * 70}")
    print(f"  Inference Token Gateway — Load Simulator")
    print(f"  {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"{'═' * 70}")
    print(f"  Host:              {args.host}")
    print(f"  Users:             {len(selected_users)}  →  {', '.join(selected_users)}")
    print(f"  Requests/user:     {args.requests_per_user}")
    print(f"  Concurrency:       {args.concurrency}")
    print(f"  Repeat ratio:      {args.repeat_ratio:.0%}  (cache stress)")
    print(f"{'─' * 70}\n")

    all_results: list[RequestResult] = []
    wall_start = time.monotonic()

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = {
            pool.submit(user_worker, args.host, uid, args.requests_per_user, args.repeat_ratio): uid
            for uid in selected_users
        }
        for future in concurrent.futures.as_completed(futures):
            uid = futures[future]
            try:
                results = future.result()
                all_results.extend(results)
            except Exception as e:
                print(f"  [ERROR] Worker for {uid} failed: {e}")

    wall_time = time.monotonic() - wall_start
    stats = aggregate_results(all_results)
    print_report(stats, wall_time)

    # Fetch final usage from API
    print("\n  Fetching usage summaries from API...\n")
    for uid in selected_users:
        usage = get_usage(args.host, uid)
        if "error" not in usage:
            print(f"  {uid}: {usage.get('totalTokensConsumed', '?')} tokens total, "
                  f"{usage.get('totalRequests', '?')} requests, "
                  f"cache hit rate={usage.get('cacheHitRate', 0):.1%}")
        else:
            print(f"  {uid}: [usage fetch failed: {usage['error']}]")

    print()


if __name__ == "__main__":
    main()
