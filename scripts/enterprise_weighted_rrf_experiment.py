"""Sweep candidate-pool size and weighted RRF for the expanded enterprise set.

This is an offline retrieval-only experiment. It keeps final evaluation at
Recall/MRR/Hit@3 while allowing each component retriever to return more than
three candidates before fusion. The LLM reranker is intentionally evaluated in
a separate stage on the same candidate pool.
"""

from __future__ import annotations

import hashlib
import json
import re
from collections import defaultdict
from pathlib import Path

import numpy as np

from enterprise_scale_eval import bm25_rank, gold_for_query, metrics, read


ROOT = Path(__file__).resolve().parents[1]
EVAL = ROOT / "src" / "main" / "resources" / "evaluation"
DOC_PATH = EVAL / "enterprise-expanded-corpus-v1.json"
QUERY_PATH = EVAL / "enterprise-expanded-queries-v1.json"
CACHE = ROOT / "docs" / "evaluation-results" / "enterprise-scale-evaluation-20260916.embeddings.npz"
OUTPUT = ROOT / "docs" / "evaluation-results" / "enterprise-weighted-rrf-experiment-20260916.json"


def weighted_rrf(vector_order: np.ndarray, keyword_order: np.ndarray, candidate_n: int,
                 alpha: float, rrf_k: int = 60) -> np.ndarray:
    """Fuse one query's branch rankings; alpha is vector weight."""
    scores: dict[int, float] = defaultdict(float)
    for rank, doc_index in enumerate(vector_order[:candidate_n]):
        scores[int(doc_index)] += alpha / (rrf_k + rank + 1.0)
    beta = 1.0 - alpha
    for rank, doc_index in enumerate(keyword_order[:candidate_n]):
        scores[int(doc_index)] += beta / (rrf_k + rank + 1.0)
    return np.array(sorted(scores, key=lambda idx: (-scores[idx], idx)), dtype=np.int32)


def grouped_fold(query: dict, docs: list[dict]) -> int:
    """Keep all paraphrases for a gold document in the same fold."""
    gold = gold_for_query(query, docs)
    key = "|".join(sorted(gold)) or f"query:{query.get('id', '')}"
    return int(hashlib.sha256(key.encode("utf-8")).hexdigest()[:8], 16) % 5


def query_bucket(query: dict) -> str:
    text = str(query.get("question", ""))
    if query.get("hardNegativeTexts"):
        return "hard_negative"
    if re.search(r"(编号|制度编号|SCALE[-_A-Z]*\d+|[A-Z]{2,}[-_]\d{2,})", text, re.I):
        return "identifier_or_code"
    if re.search(r"(多久|多少|几天|金额|比例|阈值|超过|不低于|不超过|期限|时限|时间)", text):
        return "numeric_or_condition"
    qtype = str(query.get("type", "")).lower()
    if "rewrite" in qtype or "semantic" in qtype:
        return "semantic_rewrite"
    return "other"


def evaluate(rows: list[dict], ranked: dict[str, np.ndarray], docs: list[dict]) -> dict:
    if not rows:
        return {"count": 0}
    # Fused candidate unions have variable length (up to 2*candidateN), so do
    # not stack them into a rectangular matrix; metrics only needs each row's
    # first k indices.
    result = metrics(rows, [ranked[str(q["id"])] for q in rows], docs, 3)
    return {"count": len(rows), **result}


def main() -> None:
    docs = read(DOC_PATH)["documents"]
    queries = read(QUERY_PATH)["queries"]
    answerable = [q for q in queries if gold_for_query(q, docs)]
    cache = np.load(CACHE, allow_pickle=False)
    vector_scores = cache["query_vectors"] @ cache["doc_vectors"].T
    vector_matrix = np.argsort(-vector_scores, axis=1, kind="stable")
    keyword_matrix = bm25_rank(docs, queries)
    query_index = {str(q["id"]): i for i, q in enumerate(queries)}
    vector_orders = {str(q["id"]): vector_matrix[query_index[str(q["id"])]].astype(np.int32)
                     for q in answerable}
    keyword_orders = {str(q["id"]): keyword_matrix[query_index[str(q["id"])]].astype(np.int32)
                      for q in answerable}
    folds = {str(q["id"]): grouped_fold(q, docs) for q in answerable}
    tuning = [q for q in answerable if folds[str(q["id"])] in (0, 1, 2)]
    holdout = [q for q in answerable if folds[str(q["id"])] in (3, 4)]

    candidate_sizes = [3, 6, 12, 20]
    alphas = [round(i / 10.0, 1) for i in range(11)]
    sweep: list[dict] = []
    configs: dict[tuple[int, float], dict[str, np.ndarray]] = {}
    for candidate_n in candidate_sizes:
        for alpha in alphas:
            ranked: dict[str, np.ndarray] = {}
            for q in answerable:
                qid = str(q["id"])
                ranked[qid] = weighted_rrf(
                    vector_orders[qid], keyword_orders[qid], candidate_n, alpha
                )
            configs[(candidate_n, alpha)] = ranked
            sweep.append({
                "candidateN": candidate_n,
                "vectorWeight": alpha,
                "keywordWeight": round(1.0 - alpha, 1),
                "tuning": evaluate(tuning, ranked, docs),
                "holdout": evaluate(holdout, ranked, docs),
            })

    def best(split: str) -> dict:
        return max(
            sweep,
            key=lambda x: (
                x[split].get("mrrAt3", 0.0),
                x[split].get("recallAt3", 0.0),
                x[split].get("hitAt3", 0.0),
                -x["candidateN"],
            ),
        )

    best_tuning = best("tuning")
    best_holdout = best("holdout")
    selected_ranked = configs[(best_tuning["candidateN"], best_tuning["vectorWeight"])]

    def buckets(rows: list[dict], ranked: dict[str, np.ndarray]) -> dict[str, dict]:
        grouped: dict[str, list[dict]] = defaultdict(list)
        for q in rows:
            grouped[query_bucket(q)].append(q)
        return {
            name: evaluate(group, ranked, docs)
            for name, group in sorted(grouped.items())
        }

    all_bucket_rows: dict[str, list[dict]] = defaultdict(list)
    tuning_bucket_rows: dict[str, list[dict]] = defaultdict(list)
    for q in answerable:
        all_bucket_rows[query_bucket(q)].append(q)
        if q in tuning:
            tuning_bucket_rows[query_bucket(q)].append(q)

    best_by_bucket: dict[str, dict] = {}
    for bucket, rows in sorted(tuning_bucket_rows.items()):
        if len(rows) < 5:
            continue
        candidates = []
        for item in sweep:
            config_rows = configs[(item["candidateN"], item["vectorWeight"])]
            candidates.append((evaluate(rows, config_rows, docs), item))
        best_metrics, best_item = max(
            candidates,
            key=lambda pair: (
                pair[0].get("mrrAt3", 0.0),
                pair[0].get("recallAt3", 0.0),
                pair[0].get("hitRateAt3", 0.0),
            ),
        )
        best_by_bucket[bucket] = {**best_item, "tuning": best_metrics,
                                  "holdout": evaluate(
                                      all_bucket_rows[bucket],
                                      configs[(best_item["candidateN"], best_item["vectorWeight"])],
                                      docs)}

    vector_top3 = {qid: order[:3] for qid, order in vector_orders.items()}
    keyword_top3 = {qid: order[:3] for qid, order in keyword_orders.items()}
    equal_hybrid = configs[(12, 0.5)]
    result = {
        "experiment": "weighted_rrf_candidate_pool_sweep",
        "input": [str(DOC_PATH.relative_to(ROOT)), str(QUERY_PATH.relative_to(ROOT))],
        "notes": [
            "All methods use the same expanded corpus, gold labels, and final K=3.",
            "Hybrid methods retrieve candidateN from each branch, fuse with weighted RRF, then take final top3.",
            "The vector order is exact cosine from the cached embeddings, an upper-bound proxy; it is not a live Milvus HNSW query.",
            "This file evaluates fusion only; no LLM reranker was called.",
            "Folds are grouped by gold document so paraphrases do not cross tuning/holdout splits.",
            "The expanded set is synthetic/curated for engineering validation, not an independent human benchmark.",
        ],
        "data": {
            "corpusDocuments": len(docs),
            "answerableQueries": len(answerable),
            "tuningQueries": len(tuning),
            "holdoutQueries": len(holdout),
            "foldCounts": {str(f): sum(v == f for v in folds.values()) for f in range(5)},
            "rrfK": 60,
            "candidateSizes": candidate_sizes,
            "weightGrid": alphas,
        },
        "baselines": {
            "vectorExactCosineTop3": evaluate(answerable, vector_top3, docs),
            "bm25Top3": evaluate(answerable, keyword_top3, docs),
            "hybridEqualRrfCandidate12Top3": evaluate(answerable, equal_hybrid, docs),
            "byBucket": {
                "vectorExactCosineTop3": buckets(answerable, vector_top3),
                "bm25Top3": buckets(answerable, keyword_top3),
                "hybridEqualRrfCandidate12Top3": buckets(answerable, equal_hybrid),
            },
        },
        "selectedByTuning": {
            **best_tuning,
            "holdoutAtSelected": evaluate(holdout, selected_ranked, docs),
            "holdoutByBucketAtSelected": buckets(holdout, selected_ranked),
        },
        "bestByBucketOnTuning": best_by_bucket,
        "bestObservedHoldout": best_holdout,
        "sweep": sweep,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "output": str(OUTPUT),
        "vectorExactCosineTop3": result["baselines"]["vectorExactCosineTop3"],
        "bm25Top3": result["baselines"]["bm25Top3"],
        "hybridEqualRrfCandidate12Top3": result["baselines"]["hybridEqualRrfCandidate12Top3"],
        "selectedByTuning": result["selectedByTuning"],
        "bestObservedHoldout": best_holdout,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
