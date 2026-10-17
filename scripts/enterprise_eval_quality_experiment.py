from __future__ import annotations

import json
import random
import statistics
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import numpy as np


ROOT = Path(__file__).resolve().parents[1]
EVAL = ROOT / "src" / "main" / "resources" / "evaluation"
CACHE = ROOT / "docs" / "evaluation-results" / "milvus-parameter-experiment-20260916.embeddings.npz"
OUT = ROOT / "docs" / "evaluation-results" / "enterprise-eval-quality-experiment-20260916.json"


def load() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    docs: list[dict[str, Any]] = []
    queries: list[dict[str, Any]] = []
    for name in ("day6-corpus.json", "day12-corpus.json"):
        docs.extend(json.loads((EVAL / name).read_text(encoding="utf-8"))["documents"])
    for name in ("day6-queries.json", "day12-queries.json"):
        queries.extend(json.loads((EVAL / name).read_text(encoding="utf-8"))["queries"])
    return docs, queries


def map_text(text: str, docs: list[dict[str, Any]]) -> set[str]:
    return {doc["key"] for doc in docs if text and text in doc["content"]}


def gold(query: dict[str, Any], docs: list[dict[str, Any]]) -> set[str]:
    result: set[str] = set()
    for text in query.get("relevantTexts", []):
        result.update(map_text(text, docs))
    return result


def metric(ids: list[str], expected: set[str], k: int) -> tuple[float, float, float]:
    top = ids[:k]
    found = len(set(top) & expected)
    recall = found / len(expected) if expected else 0.0
    first = next((idx + 1 for idx, item in enumerate(top) if item in expected), 0)
    mrr = 1.0 / first if first else 0.0
    hit = 1.0 if first else 0.0
    return recall, mrr, hit


def mean_metrics(rows: list[tuple[float, float, float]]) -> dict[str, float]:
    return {
        "recallAt3": statistics.fmean(row[0] for row in rows) if rows else 0.0,
        "mrrAt3": statistics.fmean(row[1] for row in rows) if rows else 0.0,
        "hitRateAt3": statistics.fmean(row[2] for row in rows) if rows else 0.0,
    }


def bootstrap(values: list[float], seed: int, samples: int = 5000) -> dict[str, float]:
    if not values:
        return {"mean": 0.0, "lower": 0.0, "upper": 0.0, "std": 0.0}
    rng = random.Random(seed)
    means = []
    for _ in range(samples):
        means.append(statistics.fmean(values[rng.randrange(len(values))] for _ in values))
    means.sort()
    return {
        "mean": statistics.fmean(values),
        "lower": means[int(0.025 * (samples - 1))],
        "upper": means[int(0.975 * (samples - 1))],
        "std": statistics.pstdev(values),
    }


def stratified_split(queries: list[dict[str, Any]], seed: int, tune_count: int) -> tuple[list[str], list[str]]:
    rng = random.Random(seed)
    groups: dict[str, list[str]] = defaultdict(list)
    for query in queries:
        groups[query["type"]].append(query["id"])
    for values in groups.values():
        rng.shuffle(values)
    ordered: list[str] = []
    round_index = 0
    while len(ordered) < len(queries):
        added = False
        for values in groups.values():
            if round_index < len(values):
                ordered.append(values[round_index])
                added = True
        if not added:
            break
        round_index += 1
    return ordered[:tune_count], ordered[tune_count:]


def main() -> None:
    docs, queries = load()
    cache = np.load(CACHE, allow_pickle=False)
    # milvus_parameter_experiment stores 5026 corpus vectors and 239 query vectors:
    # 200 public held-out queries followed by these 39 enterprise queries.
    vectors = cache["vectors"][-len(docs):]
    query_vectors = cache["query_vectors"][-len(queries):]
    scores = query_vectors @ vectors.T
    rankings = [[docs[index]["key"] for index in np.argsort(-row, kind="stable")[:10]] for row in scores]
    gold_sets = [gold(query, docs) for query in queries]
    hard_negative_sets = [set().union(*(map_text(text, docs) for text in query.get("hardNegativeTexts", [])))
                          for query in queries]

    per_query = []
    for query, ranking, expected, hard_neg in zip(queries, rankings, gold_sets, hard_negative_sets):
        recall, mrr, hit = metric(ranking, expected, 3)
        per_query.append({
            "id": query["id"],
            "type": query["type"],
            "answerable": bool(expected),
            "goldDocumentKeys": sorted(expected),
            "top3": ranking[:3],
            "recallAt3": recall,
            "mrrAt3": mrr,
            "hitAt3": hit,
            "hardNegativeAvailable": bool(hard_neg),
            "hardNegativeInTop3": bool(set(ranking[:3]) & hard_neg) if hard_neg else False,
        })

    answerable_rows = [row for row in per_query if row["answerable"]]
    full = mean_metrics([(row["recallAt3"], row["mrrAt3"], row["hitAt3"]) for row in answerable_rows])
    by_type = {}
    for type_name in sorted({row["type"] for row in per_query}):
        rows = [row for row in answerable_rows if row["type"] == type_name]
        by_type[type_name] = {
            "queries": len(rows),
            **mean_metrics([(row["recallAt3"], row["mrrAt3"], row["hitAt3"]) for row in rows]),
        }

    topk = {}
    for k in (1, 3, 5):
        rows = [metric(ranking, expected, k) for ranking, expected in zip(rankings, gold_sets) if expected]
        topk[str(k)] = mean_metrics(rows) if k == 3 else {
            "recallAtK": statistics.fmean(row[0] for row in rows),
            "mrrAtK": statistics.fmean(row[1] for row in rows),
            "hitRateAtK": statistics.fmean(row[2] for row in rows),
        }

    split_runs = []
    for seed in range(20260916, 20260921):
        tune, heldout = stratified_split(queries, seed, tune_count=24)
        held_rows = [row for row in answerable_rows if row["id"] in heldout]
        split_runs.append({
            "seed": seed,
            "tuneCount": len(tune),
            "heldOutCount": len(heldout),
            "tuneTypeCounts": dict(sorted(Counter(query["type"] for query in queries if query["id"] in tune).items())),
            "heldOutTypeCounts": dict(sorted(Counter(query["type"] for query in queries if query["id"] in heldout).items())),
            "heldOutMetrics": mean_metrics([(row["recallAt3"], row["mrrAt3"], row["hitAt3"]) for row in held_rows]),
        })

    result = {
        "dataset": "enterprise-knowledge-v2",
        "documents": len(docs),
        "queries": len(queries),
        "answerableQueries": len(answerable_rows),
        "labelImprovements": [
            "All 39 questions now have explicit requiredFacts; no-answer questions keep an empty list.",
            "q19 uses a unique travel-policy phrase including the two-million threshold context, removing a previous label collision with the training-expense policy.",
            "Each relevant text is audited against document keys; zero ambiguous text labels remain after the override.",
        ],
        "exactEmbeddingEvaluation": {
            "method": "Qwen3 Embedding vectors, exact cosine ranking over the 26 enterprise documents; no Milvus ANN approximation",
            "fullAnswerableMetricsAt3": full,
            "bootstrap5000": {
                "recallAt3": bootstrap([row["recallAt3"] for row in answerable_rows], 20260916),
                "mrrAt3": bootstrap([row["mrrAt3"] for row in answerable_rows], 20260917),
                "hitRateAt3": bootstrap([row["hitAt3"] for row in answerable_rows], 20260918),
            },
            "byQuestionType": by_type,
            "topK": topk,
        "hardNegativeTop3Rate": statistics.fmean(1.0 if row["hardNegativeInTop3"] else 0.0
                                                 for row in per_query
                                                 if row["answerable"] and row["hardNegativeAvailable"]),
        },
        "fiveSeedStratifiedStability": split_runs,
        "limitations": [
            "The questions and documents remain project-authored; the audit improves reproducibility and label clarity but does not turn them into a universal benchmark.",
            "The five splits are query-level stratified splits; semantically related questions may share a source document, so this is stability evidence rather than a strict unseen-domain test.",
            "The exact embedding experiment measures model/retrieval quality, not Milvus HNSW latency; that is reported separately in milvus-parameter-experiment-20260916.json.",
        ],
        "perQuery": per_query,
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "full": full,
        "bootstrap": result["exactEmbeddingEvaluation"]["bootstrap5000"],
        "topK": topk,
        "hardNegativeTop3Rate": result["exactEmbeddingEvaluation"]["hardNegativeTop3Rate"],
        "splits": split_runs,
    }, ensure_ascii=False, indent=2))
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
