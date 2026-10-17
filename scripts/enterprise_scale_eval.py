"""Evaluate the enlarged enterprise scale dataset with exact cosine ranking.

This is a retrieval/label audit, not a claim that synthetic documents are real
enterprise traffic.  It deliberately keeps exact cosine separate from the
Milvus ANN experiment.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import statistics
import time
from pathlib import Path
from typing import Any

import numpy as np
import requests


ROOT = Path(__file__).resolve().parents[2]
EVAL = ROOT / "agent-rag-lite" / "src" / "main" / "resources" / "evaluation"
DOC_PATH = EVAL / "enterprise-expanded-corpus-v1.json"
QUERY_PATH = EVAL / "enterprise-expanded-queries-v1.json"
OUT = ROOT / "agent-rag-lite" / "docs" / "evaluation-results" / "enterprise-scale-evaluation-20260916.json"
CACHE = OUT.with_suffix(".embeddings.npz")
OLLAMA = "http://127.0.0.1:11434/v1/embeddings"
MODEL = "qwen3-embedding:0.6b"
DIM = 1024
BATCH = 32


def read(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def embed(texts: list[str]) -> np.ndarray:
    values: list[list[float]] = []
    for start in range(0, len(texts), BATCH):
        batch = texts[start:start + BATCH]
        response = requests.post(OLLAMA, headers={"Authorization": "Bearer ollama"},
                                 json={"model": MODEL, "input": batch}, timeout=600)
        response.raise_for_status()
        rows = sorted(response.json()["data"], key=lambda item: item.get("index", 0))
        if len(rows) != len(batch):
            raise RuntimeError(f"embedding count mismatch: {len(rows)} != {len(batch)}")
        values.extend(row["embedding"] for row in rows)
        print(f"embedded {min(start + len(batch), len(texts))}/{len(texts)}", flush=True)
    result = np.asarray(values, dtype=np.float32)
    if result.shape != (len(texts), DIM):
        raise RuntimeError(f"unexpected embedding shape {result.shape}")
    return result / np.maximum(np.linalg.norm(result, axis=1, keepdims=True), 1e-12)


def gold_for_query(query: dict[str, Any], documents: list[dict[str, Any]]) -> set[str]:
    explicit = query.get("goldDocumentKeys")
    if explicit:
        return set(explicit)
    expected = [value for value in query.get("relevantTexts", []) if value]
    if not expected:
        return set()
    return {doc["key"] for doc in documents
            if any(value in doc.get("content", "") for value in expected)}


def metrics(rows: list[dict[str, Any]], indices: np.ndarray, documents: list[dict[str, Any]], k: int) -> dict[str, float]:
    key_by_index = [doc["key"] for doc in documents]
    values: list[float] = []
    rr: list[float] = []
    hits: list[float] = []
    for row, ranked in zip(rows, indices):
        gold = gold_for_query(row, documents)
        if not gold:
            continue
        found = [key_by_index[int(i)] for i in ranked[:k]]
        overlap = len(set(found) & gold) / len(gold)
        values.append(overlap)
        ranks = [pos + 1 for pos, key in enumerate(found) if key in gold]
        rr.append(1.0 / min(ranks) if ranks else 0.0)
        hits.append(1.0 if ranks else 0.0)
    return {f"recallAt{k}": statistics.fmean(values) if values else 0.0,
            f"mrrAt{k}": statistics.fmean(rr) if rr else 0.0,
            f"hitRateAt{k}": statistics.fmean(hits) if hits else 0.0,
            "answerableQueries": len(values)}


def tokenize(value: str) -> list[str]:
    """A small tokenizer matching the project's basic CJK/Latin token idea."""
    tokens: list[str] = []
    latin = []
    for char in value:
        if re.match(r"[A-Za-z0-9_]", char):
            latin.append(char.lower())
            continue
        if latin:
            tokens.append("".join(latin))
            latin = []
        if "\u4e00" <= char <= "\u9fff":
            tokens.append(char)
    if latin:
        tokens.append("".join(latin))
    return tokens


def bm25_rank(documents: list[dict[str, Any]], queries: list[dict[str, Any]]) -> np.ndarray:
    doc_tokens = [tokenize(doc["title"] + "。" + doc["content"]) for doc in documents]
    lengths = np.asarray([len(tokens) for tokens in doc_tokens], dtype=np.float64)
    avgdl = float(lengths.mean())
    df: dict[str, int] = {}
    for tokens in doc_tokens:
        for term in set(tokens):
            df[term] = df.get(term, 0) + 1
    n = len(documents)
    k1, b = 1.2, 0.75
    result = np.zeros((len(queries), n), dtype=np.float64)
    for qi, query in enumerate(queries):
        qterms = tokenize(query["question"])
        counts: dict[str, int] = {}
        for term in qterms:
            counts[term] = counts.get(term, 0) + 1
        for di, tokens in enumerate(doc_tokens):
            tf: dict[str, int] = {}
            for term in tokens:
                if term in counts:
                    tf[term] = tf.get(term, 0) + 1
            score = 0.0
            for term, frequency in tf.items():
                idf = math.log(1.0 + (n - df.get(term, 0) + 0.5) / (df.get(term, 0) + 0.5))
                denominator = frequency + k1 * (1.0 - b + b * lengths[di] / max(avgdl, 1.0))
                score += idf * frequency * (k1 + 1.0) / denominator
            result[qi, di] = score
    return np.argsort(-result, axis=1, kind="stable")


def rrf_rank(vector_indices: np.ndarray, keyword_indices: np.ndarray, rrf_k: int = 60) -> np.ndarray:
    fused = np.zeros((vector_indices.shape[0], vector_indices.shape[1]), dtype=np.float64)
    for qi in range(vector_indices.shape[0]):
        for rank, index in enumerate(vector_indices[qi]):
            fused[qi, int(index)] += 1.0 / (rrf_k + rank + 1)
        for rank, index in enumerate(keyword_indices[qi]):
            fused[qi, int(index)] += 1.0 / (rrf_k + rank + 1)
    return np.argsort(-fused, axis=1, kind="stable")


def bootstrap(rows: list[dict[str, Any]], indices: np.ndarray, documents: list[dict[str, Any]], k: int,
              seed: int = 20260916, rounds: int = 5000) -> dict[str, dict[str, float]]:
    key_by_index = [doc["key"] for doc in documents]
    recalls: list[float] = []
    mrrs: list[float] = []
    hits: list[float] = []
    records: list[tuple[float, float, float]] = []
    for row, ranked in zip(rows, indices):
        gold = gold_for_query(row, documents)
        if not gold:
            continue
        found = [key_by_index[int(i)] for i in ranked[:k]]
        ranks = [pos + 1 for pos, key in enumerate(found) if key in gold]
        records.append((len(set(found) & gold) / len(gold), 1.0 / min(ranks) if ranks else 0.0,
                        1.0 if ranks else 0.0))
    rng = np.random.default_rng(seed)
    sample = np.asarray(records, dtype=np.float64)
    for _ in range(rounds):
        selected = sample[rng.integers(0, len(sample), len(sample))]
        recalls.append(float(selected[:, 0].mean()))
        mrrs.append(float(selected[:, 1].mean()))
        hits.append(float(selected[:, 2].mean()))

    def interval(values: list[float]) -> dict[str, float]:
        return {"mean": float(np.mean(values)), "lower": float(np.percentile(values, 2.5)),
                "upper": float(np.percentile(values, 97.5)), "std": float(np.std(values))}

    return {"recallAt3": interval(recalls), "mrrAt3": interval(mrrs), "hitRateAt3": interval(hits)}


def main() -> None:
    started = time.perf_counter()
    documents = read(DOC_PATH)["documents"]
    queries = read(QUERY_PATH)["queries"]
    doc_texts = [doc["title"] + "。" + doc["content"] for doc in documents]
    query_texts = [query["question"] for query in queries]
    if CACHE.exists():
        cache = np.load(CACHE, allow_pickle=False)
        doc_vectors, query_vectors = cache["doc_vectors"], cache["query_vectors"]
        print(f"loaded cache {CACHE}", flush=True)
    else:
        doc_vectors = embed(doc_texts)
        query_vectors = embed(query_texts)
        np.savez_compressed(CACHE, doc_vectors=doc_vectors, query_vectors=query_vectors)
        print(f"saved cache {CACHE}", flush=True)
    scores = query_vectors @ doc_vectors.T
    vector_indices = np.argsort(-scores, axis=1, kind="stable")
    keyword_indices = bm25_rank(documents, queries)
    fused_indices = rrf_rank(vector_indices, keyword_indices)
    answerable = [query for query in queries if gold_for_query(query, documents)]
    no_answer = [query for query in queries if not gold_for_query(query, documents)]

    by_source: dict[str, dict[str, Any]] = {}
    for label, rows in {
        "base39": queries[:39],
        "syntheticAnswerable": [q for q in queries[39:] if gold_for_query(q, documents)],
        "syntheticNoAnswer": [q for q in queries[39:] if not gold_for_query(q, documents)],
    }.items():
        row_indices = [queries.index(q) for q in rows]
        by_source[label] = {
            "queries": len(rows),
            "vectorMetricsAt3": metrics(rows, vector_indices[row_indices], documents, 3),
            "bm25MetricsAt3": metrics(rows, keyword_indices[row_indices], documents, 3),
            "rrfMetricsAt3": metrics(rows, fused_indices[row_indices], documents, 3),
        }

    key_by_index = [doc["key"] for doc in documents]
    hard_rows = []
    hard_with_negative = 0
    hard_outrank = 0
    for row, ranked in zip(queries, vector_indices):
        negatives = row.get("hardNegativeTexts") or []
        gold = gold_for_query(row, documents)
        if not negatives or not gold:
            continue
        hard_with_negative += 1
        found = [key_by_index[int(i)] for i in ranked[:3]]
        neg_keys = {doc["key"] for doc in documents
                    if any(text and text in doc.get("content", "") for text in negatives)}
        if set(found) & neg_keys:
            hard_rows.append(1.0)
        else:
            hard_rows.append(0.0)
        gold_rank = min((pos for pos, i in enumerate(ranked) if key_by_index[int(i)] in gold),
                        default=10**9)
        neg_rank = min((pos for pos, i in enumerate(ranked) if key_by_index[int(i)] in neg_keys),
                       default=10**9)
        if neg_rank < gold_rank:
            hard_outrank += 1

    grouped_folds = []
    for fold in range(5):
        rows = []
        row_indices = []
        for i, query in enumerate(queries):
            gold = gold_for_query(query, documents)
            if gold and int(hashlib.sha256(sorted(gold)[0].encode()).hexdigest(), 16) % 5 == fold:
                rows.append(query)
                row_indices.append(i)
        grouped_folds.append({"fold": fold, "queries": len(rows),
                              "vectorMetricsAt3": metrics(rows, vector_indices[row_indices], documents, 3),
                              "rrfMetricsAt3": metrics(rows, fused_indices[row_indices], documents, 3)})

    result = {
        "dataset": "enterprise-expanded-scale-v1",
        "embeddingModel": MODEL,
        "method": "exact cosine over the expanded corpus; no Milvus ANN approximation",
        "corpus": {"documents": len(documents),
                   "baseDocuments": sum(1 for d in documents if d.get("sourceType") not in {"SYNTHETIC_SCALE_EXPANSION", "SYNTHETIC_SCALE_HARD_NEGATIVE"}),
                   "syntheticCurrent": sum(1 for d in documents if d.get("sourceType") == "SYNTHETIC_SCALE_EXPANSION"),
                   "syntheticHardNegative": sum(1 for d in documents if d.get("sourceType") == "SYNTHETIC_SCALE_HARD_NEGATIVE")},
        "queries": {"total": len(queries), "answerable": len(answerable), "noAnswer": len(no_answer)},
        "vectorMetricsAt3": metrics(answerable, vector_indices[[queries.index(q) for q in answerable]], documents, 3),
        "bm25MetricsAt3": metrics(answerable, keyword_indices[[queries.index(q) for q in answerable]], documents, 3),
        "rrfMetricsAt3": metrics(answerable, fused_indices[[queries.index(q) for q in answerable]], documents, 3),
        "vectorMetricsAt1": metrics(answerable, vector_indices[[queries.index(q) for q in answerable]], documents, 1),
        "vectorMetricsAt5": metrics(answerable, vector_indices[[queries.index(q) for q in answerable]], documents, 5),
        "rrfMetricsAt1": metrics(answerable, fused_indices[[queries.index(q) for q in answerable]], documents, 1),
        "rrfMetricsAt5": metrics(answerable, fused_indices[[queries.index(q) for q in answerable]], documents, 5),
        "bootstrap5000VectorAt3": bootstrap(answerable, vector_indices[[queries.index(q) for q in answerable]], documents, 3),
        "bootstrap5000RrfAt3": bootstrap(answerable, fused_indices[[queries.index(q) for q in answerable]], documents, 3),
        "bySource": by_source,
        "hardNegative": {"answerableQueriesWithHardNegative": hard_with_negative,
                         "negativeInTop3Rate": statistics.fmean(hard_rows) if hard_rows else 0.0,
                         "negativeOutrankGoldCount": hard_outrank,
                         "negativeOutrankGoldRate": hard_outrank / hard_with_negative if hard_with_negative else 0.0},
        "groupedFiveFoldStability": grouped_folds,
        "limitations": [
            "The 500+50 expansion is deterministic synthetic scale data, not real company traffic.",
            "Generated paraphrases share templates with their source documents, so this is not an independent human benchmark.",
            "Exact cosine results do not measure Milvus ANN latency; run the paired Milvus experiment separately.",
        ],
        "elapsedSeconds": time.perf_counter() - started,
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {OUT}", flush=True)
    print(json.dumps({k: result[k] for k in ("corpus", "queries", "vectorMetricsAt3", "bm25MetricsAt3", "rrfMetricsAt3", "vectorMetricsAt1", "rrfMetricsAt1", "hardNegative")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
