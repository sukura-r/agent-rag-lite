"""Run a real Milvus FLAT/HNSW parameter comparison for the project.

This is deliberately separate from PublicRetrievalBenchmarkService.  That service
does exact cosine ranking in memory; this script measures the actual Milvus ANN
path and records both index recall and business retrieval recall.
"""

from __future__ import annotations

import hashlib
import json
import statistics
import time
import uuid
from pathlib import Path
from typing import Any

import numpy as np
import requests
from pymilvus import Collection, CollectionSchema, DataType, FieldSchema, connections, utility


ROOT = Path(__file__).resolve().parents[2]
EVAL = ROOT / "agent-rag-lite" / "src" / "main" / "resources" / "evaluation"
OUT = ROOT / "agent-rag-lite" / "docs" / "evaluation-results" / "milvus-parameter-experiment-20260916.json"
OLLAMA = "http://127.0.0.1:11434/v1/embeddings"
MODEL = "qwen3-embedding:0.6b"
DIM = 1024
BATCH = 32


def read_rows(path: Path) -> list[dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    return [item["row"] for item in data["rows"]]


def load_enterprise() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    documents: list[dict[str, Any]] = []
    queries: list[dict[str, Any]] = []
    for name in ("day6-corpus.json", "day12-corpus.json"):
        documents.extend(json.loads((EVAL / name).read_text(encoding="utf-8"))["documents"])
    for name in ("day6-queries.json", "day12-queries.json"):
        queries.extend(json.loads((EVAL / name).read_text(encoding="utf-8"))["queries"])
    return documents, queries


def embed(texts: list[str]) -> np.ndarray:
    vectors: list[list[float]] = []
    for start in range(0, len(texts), BATCH):
        batch = texts[start:start + BATCH]
        response = requests.post(
            OLLAMA,
            headers={"Authorization": "Bearer ollama"},
            json={"model": MODEL, "input": batch if len(batch) > 1 else batch[0]},
            timeout=600,
        )
        response.raise_for_status()
        data = response.json()["data"]
        ordered = sorted(data, key=lambda item: item.get("index", 0))
        if len(ordered) != len(batch):
            raise RuntimeError(f"embedding count mismatch: expected={len(batch)} actual={len(ordered)}")
        vectors.extend(item["embedding"] for item in ordered)
        print(f"embedded {min(start + len(batch), len(texts))}/{len(texts)}", flush=True)
    result = np.asarray(vectors, dtype=np.float32)
    if result.shape != (len(texts), DIM):
        raise RuntimeError(f"embedding shape mismatch: {result.shape}")
    norms = np.linalg.norm(result, axis=1, keepdims=True)
    result = result / np.maximum(norms, 1e-12)
    return result


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def make_schema(name: str) -> Collection:
    fields = [
        FieldSchema(name="id", dtype=DataType.VARCHAR, max_length=64, is_primary=True, auto_id=False),
        FieldSchema(name="document_id", dtype=DataType.VARCHAR, max_length=128),
        FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=DIM),
    ]
    collection = Collection(name=name, schema=CollectionSchema(fields), consistency_level="Strong")
    return collection


def create_collection(name: str, vectors: np.ndarray, ids: list[str], document_ids: list[str],
                      index_type: str, m: int | None = None) -> tuple[Collection, float, float]:
    started = time.perf_counter()
    collection = make_schema(name)
    collection.insert([ids, document_ids, vectors.tolist()])
    collection.flush()
    inserted_at = time.perf_counter()
    if index_type == "FLAT":
        index_params = {"index_type": "FLAT", "metric_type": "COSINE", "params": {}}
    else:
        index_params = {
            "index_type": "HNSW",
            "metric_type": "COSINE",
            "params": {"M": m, "efConstruction": 128},
        }
    collection.create_index(field_name="embedding", index_params=index_params)
    collection.load()
    loaded_at = time.perf_counter()
    return collection, (inserted_at - started) * 1000, (loaded_at - inserted_at) * 1000


def search(collection: Collection, queries: np.ndarray, ef: int | None) -> tuple[list[list[str]], list[float]]:
    params = {"metric_type": "COSINE", "params": {}}
    if ef is not None:
        params["params"] = {"ef": ef}
    # Warm up the loaded collection, excluding startup/cache effects from timings.
    for vector in queries[:5]:
        collection.search([vector.tolist()], "embedding", params, limit=3, output_fields=["id"])
    result_ids: list[list[str]] = []
    durations: list[float] = []
    for vector in queries:
        started = time.perf_counter()
        hits = collection.search([vector.tolist()], "embedding", params, limit=3, output_fields=["id"])
        durations.append((time.perf_counter() - started) * 1000)
        result_ids.append([str(hit.id) for hit in hits[0]])
    return result_ids, durations


def average_recall(results: list[list[str]], gold: list[set[str]]) -> float:
    values = []
    for found, expected in zip(results, gold):
        values.append(len(set(found) & expected) / len(expected) if expected else 0.0)
    return statistics.fmean(values) if values else 0.0


def average_hit(results: list[list[str]], gold: list[set[str]]) -> float:
    answerable = [(found, expected) for found, expected in zip(results, gold) if expected]
    return statistics.fmean(1.0 if set(found) & expected else 0.0 for found, expected in answerable) if answerable else 0.0


def p50(values: list[float]) -> float:
    return float(np.percentile(values, 50)) if values else 0.0


def p95(values: list[float]) -> float:
    return float(np.percentile(values, 95)) if values else 0.0


def main() -> None:
    connections.connect(alias="default", host="127.0.0.1", port="19530")
    run_tag = "zhiheng_milvus_param_" + uuid.uuid4().hex[:8]
    public_corpus_path = EVAL / "public-duretrieval-corpus-source.json"
    public_queries_path = EVAL / "public-duretrieval-queries-source.json"
    public_qrels_path = EVAL / "public-duretrieval-qrels-source.json"
    public_docs = read_rows(public_corpus_path)
    public_queries = read_rows(public_queries_path)
    public_qrels = read_rows(public_qrels_path)
    public_gold = {}
    for row in public_qrels:
        public_gold.setdefault(row["qid"], set()).add(row["pid"])
    # The existing public experiment reserves the first 100 questions for tuning.
    public_queries = public_queries[100:]
    public_query_texts = [item["text"] for item in public_queries]

    enterprise_docs, enterprise_queries = load_enterprise()
    enterprise_ids: list[set[str]] = []
    for query in enterprise_queries:
        matched: set[str] = set()
        for document in enterprise_docs:
            if any(expected and expected in document["content"] for expected in query.get("relevantTexts", [])):
                matched.add("ent-" + document["key"])
        enterprise_ids.append(matched)

    # One shared corpus allows the same ANN index to be tested for both generic
    # web retrieval and the actual enterprise-domain queries.
    ids = [item["id"] for item in public_docs] + ["ent-" + item["key"] for item in enterprise_docs]
    document_ids = [item["id"] for item in public_docs] + [item["key"] for item in enterprise_docs]
    texts = [item["text"] for item in public_docs] + [item["content"] for item in enterprise_docs]
    query_texts = public_query_texts + [item["question"] for item in enterprise_queries]
    cache_path = OUT.with_suffix(".embeddings.npz")
    if cache_path.exists():
        cache = np.load(cache_path, allow_pickle=False)
        vectors = cache["vectors"]
        query_vectors = cache["query_vectors"]
        print(f"loaded embedding cache: {cache_path}", flush=True)
    else:
        vectors = embed(texts)
        query_vectors = embed(query_texts)
        np.savez_compressed(cache_path, vectors=vectors, query_vectors=query_vectors)
        print(f"saved embedding cache: {cache_path}", flush=True)

    public_gold_list = [public_gold.get(item["id"], set()) for item in public_queries]
    all_gold = public_gold_list + enterprise_ids
    public_slice = slice(0, len(public_queries))
    enterprise_slice = slice(len(public_queries), len(public_queries) + len(enterprise_queries))
    collections: list[Collection] = []
    results: dict[str, Any] = {
        "runTag": run_tag,
        "embeddingModel": MODEL,
        "dimension": DIM,
        "corpus": {"public": len(public_docs), "enterprise": len(enterprise_docs), "total": len(ids)},
        "queries": {"publicHeldOut": len(public_queries), "enterprise": len(enterprise_queries)},
        "sourceSha256": {str(path.name): sha(path) for path in
                          (public_corpus_path, public_queries_path, public_qrels_path)},
        "method": "Milvus 2.6.6, COSINE, limit=3; public queries are held-out offset 100..299",
        "variants": [],
    }
    try:
        flat_name = run_tag + "_flat"
        flat, insert_ms, load_ms = create_collection(flat_name, vectors, ids, document_ids, "FLAT")
        collections.append(flat)
        flat_results, flat_latencies = search(flat, query_vectors, None)
        results["flatBaseline"] = {
            "insertMs": insert_ms,
            "indexLoadMs": load_ms,
            "publicBusinessRecallAt3": average_recall(flat_results[public_slice], public_gold_list),
            "enterpriseBusinessRecallAt3": average_recall(flat_results[enterprise_slice], enterprise_ids),
            "enterpriseHitRateAt3": average_hit(flat_results[enterprise_slice], enterprise_ids),
            "p50Ms": p50(flat_latencies),
            "p95Ms": p95(flat_latencies),
        }
        for m in (8, 16, 32):
            hnsw_name = run_tag + f"_hnsw_m{m}"
            collection, insert_ms, load_ms = create_collection(hnsw_name, vectors, ids, document_ids, "HNSW", m=m)
            collections.append(collection)
            for ef in (32, 64, 128):
                approx_results, latencies = search(collection, query_vectors, ef)
                ann_values = [len(set(found) & set(exact)) / 3.0
                              for found, exact in zip(approx_results, flat_results)]
                results["variants"].append({
                    "index": "HNSW",
                    "M": m,
                    "efConstruction": 128,
                    "ef": ef,
                    "insertMs": insert_ms,
                    "indexLoadMs": load_ms,
                    "annRecallAt3VsFlat": statistics.fmean(ann_values),
                    "publicBusinessRecallAt3": average_recall(approx_results[public_slice], public_gold_list),
                    "enterpriseBusinessRecallAt3": average_recall(approx_results[enterprise_slice], enterprise_ids),
                    "enterpriseHitRateAt3": average_hit(approx_results[enterprise_slice], enterprise_ids),
                    "p50Ms": p50(latencies),
                    "p95Ms": p95(latencies),
                })
                print(f"finished M={m} ef={ef}", flush=True)
    finally:
        for collection in collections:
            try:
                utility.drop_collection(collection.name)
            except Exception as exc:
                print(f"cleanup failed for {collection.name}: {exc}", flush=True)

    OUT.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {OUT}", flush=True)


if __name__ == "__main__":
    main()
