"""Run Flat/HNSW comparison on the enlarged enterprise scale corpus."""

from __future__ import annotations

import json
import statistics
import time
import uuid
from pathlib import Path
from typing import Any

import numpy as np
from pymilvus import Collection, CollectionSchema, DataType, FieldSchema, connections, utility


ROOT = Path(__file__).resolve().parents[2]
EVAL = ROOT / "agent-rag-lite" / "src" / "main" / "resources" / "evaluation"
RESULTS = ROOT / "agent-rag-lite" / "docs" / "evaluation-results"
DOC_PATH = EVAL / "enterprise-expanded-corpus-v1.json"
QUERY_PATH = EVAL / "enterprise-expanded-queries-v1.json"
CACHE = RESULTS / "enterprise-scale-evaluation-20260916.embeddings.npz"
OUT = RESULTS / "milvus-expanded-parameter-experiment-20260916.json"
DIM = 1024


def read(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def gold_for_query(query: dict[str, Any], documents: list[dict[str, Any]]) -> set[str]:
    if query.get("goldDocumentKeys"):
        return {"ent-" + key for key in query["goldDocumentKeys"]}
    expected = [x for x in query.get("relevantTexts", []) if x]
    return {"ent-" + doc["key"] for doc in documents
            if any(text in doc.get("content", "") for text in expected)} if expected else set()


def make_collection(name: str) -> Collection:
    fields = [
        FieldSchema(name="id", dtype=DataType.VARCHAR, max_length=128, is_primary=True, auto_id=False),
        FieldSchema(name="document_id", dtype=DataType.VARCHAR, max_length=128),
        FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=DIM),
    ]
    return Collection(name=name, schema=CollectionSchema(fields), consistency_level="Strong")


def create(name: str, vectors: np.ndarray, ids: list[str], docs: list[str], kind: str,
           m: int | None = None) -> tuple[Collection, float, float]:
    started = time.perf_counter()
    collection = make_collection(name)
    collection.insert([ids, docs, vectors.tolist()])
    collection.flush()
    inserted = time.perf_counter()
    if kind == "FLAT":
        params = {"index_type": "FLAT", "metric_type": "COSINE", "params": {}}
    else:
        params = {"index_type": "HNSW", "metric_type": "COSINE",
                  "params": {"M": m, "efConstruction": 128}}
    collection.create_index("embedding", params)
    collection.load()
    loaded = time.perf_counter()
    return collection, (inserted - started) * 1000, (loaded - inserted) * 1000


def search(collection: Collection, queries: np.ndarray, ef: int | None) -> tuple[list[list[str]], list[float]]:
    params = {"metric_type": "COSINE", "params": {} if ef is None else {"ef": ef}}
    for vector in queries[:5]:
        collection.search([vector.tolist()], "embedding", params, limit=3, output_fields=["document_id"])
    found: list[list[str]] = []
    durations: list[float] = []
    for vector in queries:
        started = time.perf_counter()
        hits = collection.search([vector.tolist()], "embedding", params, limit=3, output_fields=["document_id"])
        durations.append((time.perf_counter() - started) * 1000)
        found.append([str(hit.id) for hit in hits[0]])
    return found, durations


def recall(results: list[list[str]], gold: list[set[str]]) -> float:
    values = [len(set(found) & expected) / len(expected) for found, expected in zip(results, gold) if expected]
    return statistics.fmean(values) if values else 0.0


def hit(results: list[list[str]], gold: list[set[str]]) -> float:
    values = [1.0 if set(found) & expected else 0.0 for found, expected in zip(results, gold) if expected]
    return statistics.fmean(values) if values else 0.0


def p50(values: list[float]) -> float:
    return float(np.percentile(values, 50)) if values else 0.0


def p95(values: list[float]) -> float:
    return float(np.percentile(values, 95)) if values else 0.0


def main() -> None:
    documents = read(DOC_PATH)["documents"]
    queries = read(QUERY_PATH)["queries"]
    cache = np.load(CACHE, allow_pickle=False)
    vectors = cache["doc_vectors"]
    query_vectors = cache["query_vectors"]
    ids = ["ent-" + doc["key"] for doc in documents]
    document_ids = [doc["key"] for doc in documents]
    gold = [gold_for_query(query, documents) for query in queries]
    answerable = [expected for expected in gold if expected]
    run_tag = "zhiheng_milvus_expanded_" + uuid.uuid4().hex[:8]
    collections: list[Collection] = []
    result: dict[str, Any] = {
        "runTag": run_tag,
        "embeddingModel": "qwen3-embedding:0.6b",
        "dimension": DIM,
        "corpus": {"documents": len(documents), "vectors": len(vectors),
                   "baseDocuments": 26, "syntheticCurrent": 500, "syntheticHardNegative": 50},
        "queries": {"total": len(queries), "answerable": len(answerable), "noAnswer": len(queries) - len(answerable)},
        "method": "Milvus 2.6.6, COSINE, limit=3; sequential local requests; fixed expanded corpus",
        "variants": [],
    }
    connections.connect(alias="default", host="127.0.0.1", port="19530")
    try:
        flat, insert_ms, load_ms = create(run_tag + "_flat", vectors, ids, document_ids, "FLAT")
        collections.append(flat)
        flat_results, flat_latencies = search(flat, query_vectors, None)
        result["flatBaseline"] = {
            "insertMs": insert_ms, "indexLoadMs": load_ms,
            "businessRecallAt3": recall(flat_results, gold), "businessHitRateAt3": hit(flat_results, gold),
            "p50Ms": p50(flat_latencies), "p95Ms": p95(flat_latencies),
        }
        for m in (8, 16, 32):
            collection, insert_ms, load_ms = create(run_tag + f"_hnsw_m{m}", vectors, ids, document_ids, "HNSW", m)
            collections.append(collection)
            for ef in (32, 64, 128):
                approx, latencies = search(collection, query_vectors, ef)
                overlap = [len(set(found) & set(exact)) / 3.0 for found, exact in zip(approx, flat_results)]
                result["variants"].append({
                    "index": "HNSW", "M": m, "efConstruction": 128, "ef": ef,
                    "annRecallAt3VsFlat": statistics.fmean(overlap),
                    "businessRecallAt3": recall(approx, gold), "businessHitRateAt3": hit(approx, gold),
                    "p50Ms": p50(latencies), "p95Ms": p95(latencies),
                    "insertMs": insert_ms, "indexLoadMs": load_ms,
                })
                print(f"finished M={m} ef={ef}", flush=True)
    finally:
        for collection in collections:
            try:
                utility.drop_collection(collection.name)
            except Exception as exc:
                print(f"cleanup failed: {exc}", flush=True)
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {OUT}", flush=True)
    print(json.dumps({"flatBaseline": result.get("flatBaseline"), "variants": result["variants"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
