"""Run the fixed A-G retrieval experiment matrix on the expanded dataset.

The vector/BM25/RRF stages are deterministic and use the cached Qwen3
embeddings.  The optional second-stage experiments call the same local Qwen3
chat model and use the project's current reranker contract: rank only the
provided candidates and return a complete JSON permutation.

This script is deliberately explicit about the boundary between candidateN,
rerankN, and final topK=3.  It writes resumable per-variant rerank caches.
"""

from __future__ import annotations

import json
import re
import statistics
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

import numpy as np
import requests

from enterprise_scale_eval import bm25_rank, gold_for_query, metrics, read


ROOT = Path(__file__).resolve().parents[1]
EVAL = ROOT / "src" / "main" / "resources" / "evaluation"
DOC_PATH = EVAL / "enterprise-expanded-corpus-v1.json"
QUERY_PATH = EVAL / "enterprise-expanded-queries-v1.json"
EMBED_CACHE = ROOT / "docs" / "evaluation-results" / "enterprise-scale-evaluation-20260916.embeddings.npz"
MILVUS_RESULT = ROOT / "docs" / "evaluation-results" / "milvus-expanded-parameter-experiment-20260916.json"
OUT = ROOT / "docs" / "evaluation-results" / "enterprise-fixed-matrix-experiment-20260916.json"
CACHE_DIR = ROOT / "docs" / "evaluation-results" / "rerank-cache-20260916"
OLLAMA = "http://127.0.0.1:11434/api/chat"
MODEL = "qwen3:4b"
FINAL_K = 3
RRF_K = 60


def weighted_rrf(v: np.ndarray, b: np.ndarray, n: int, vw: float = 0.5,
                 rrf_k: int = RRF_K) -> np.ndarray:
    scores: dict[int, float] = {}
    for rank, idx in enumerate(v[:n]):
        scores[int(idx)] = scores.get(int(idx), 0.0) + vw / (rrf_k + rank + 1.0)
    kw = 1.0 - vw
    for rank, idx in enumerate(b[:n]):
        scores[int(idx)] = scores.get(int(idx), 0.0) + kw / (rrf_k + rank + 1.0)
    return np.array(sorted(scores, key=lambda idx: (-scores[idx], idx)), dtype=np.int32)


def hard_negative_keys(query: dict[str, Any], docs: list[dict[str, Any]]) -> set[str]:
    return {
        doc["key"]
        for text in (query.get("hardNegativeTexts") or [])
        for doc in docs
        if text and text in doc.get("content", "")
    }


def hard_negative_rate(rows: list[dict[str, Any]], ranked: dict[str, np.ndarray],
                       docs: list[dict[str, Any]]) -> dict[str, float]:
    key_by_index = [doc["key"] for doc in docs]
    values = []
    for query in rows:
        negatives = hard_negative_keys(query, docs)
        if not negatives:
            continue
        values.append(float(bool(set(key_by_index[int(i)] for i in ranked[str(query["id"])] [:FINAL_K]) & negatives)))
    return {
        "queriesWithHardNegative": len(values),
        "hardNegativeInTop3Rate": statistics.fmean(values) if values else 0.0,
    }


def parse_json_array(text: str, expected: int) -> list[int] | None:
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\[[^\]]*\]", text or "", flags=re.DOTALL)
        if not match:
            return None
        try:
            value = json.loads(match.group(0))
        except json.JSONDecodeError:
            return None
    if not isinstance(value, list) or len(value) != expected:
        return None
    if not all(isinstance(item, int) and not isinstance(item, bool) for item in value):
        return None
    if set(value) != set(range(1, expected + 1)):
        return None
    return value


def rerank_one(query: dict[str, Any], candidate_indices: np.ndarray,
               docs: list[dict[str, Any]], rerank_n: int) -> tuple[str, list[int], float, bool]:
    candidate_indices = candidate_indices[:rerank_n]
    prompt = [f"问题：{query['question']}", "", "候选片段："]
    for position, doc_index in enumerate(candidate_indices, 1):
        doc = docs[int(doc_index)]
        content = str(doc.get("content", ""))
        if len(content) > 300:
            content = content[:300] + "……"
        prompt.append(f"{position}. 标题：{doc.get('title', '')}")
        prompt.append(f"内容：{content}")
        prompt.append("")
    prompt.append("请按与问题的相关性从高到低排列全部候选编号。只输出JSON整数数组，不要解释。 /no_think")
    count = len(candidate_indices)
    schema = {
        "type": "array",
        "items": {"type": "integer", "minimum": 1, "maximum": count},
        "minItems": count,
        "maxItems": count,
    }
    body = {
        "model": MODEL,
        "stream": False,
        "think": False,
        "format": schema,
        "options": {"temperature": 0, "num_predict": count * 8},
        "messages": [
            {"role": "system", "content": "你是企业知识检索精排器。只根据候选文本与问题的相关性排序，不回答问题。"},
            {"role": "user", "content": "\n".join(prompt)},
        ],
    }
    started = time.perf_counter()
    try:
        response = requests.post(OLLAMA, json=body, timeout=600)
        response.raise_for_status()
        content = response.json().get("message", {}).get("content", "")
        order = parse_json_array(content, count)
        if order is not None:
            return str(query["id"]), [int(candidate_indices[i - 1]) for i in order], time.perf_counter() - started, False
    except Exception:
        pass
    return str(query["id"]), [int(i) for i in candidate_indices], time.perf_counter() - started, True


def run_rerank_variant(name: str, fused: dict[str, np.ndarray], rows: list[dict[str, Any]],
                       docs: list[dict[str, Any]], rerank_n: int) -> tuple[dict[str, np.ndarray], dict[str, Any]]:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache_path = CACHE_DIR / f"{name}.json"
    cached: dict[str, list[int]] = {}
    if cache_path.exists():
        try:
            raw = json.loads(cache_path.read_text(encoding="utf-8"))
            cached = {str(k): [int(i) for i in v] for k, v in raw.get("rankings", {}).items()}
        except Exception:
            cached = {}
    pending = [q for q in rows if str(q["id"]) not in cached]
    latencies: list[float] = []
    fallbacks = 0
    if pending:
        with ThreadPoolExecutor(max_workers=4) as pool:
            futures = [pool.submit(rerank_one, q, fused[str(q["id"])], docs, rerank_n) for q in pending]
            for done, future in enumerate(as_completed(futures), 1):
                qid, order, elapsed, fallback = future.result()
                cached[qid] = order
                latencies.append(elapsed)
                fallbacks += int(fallback)
                if done % 25 == 0 or done == len(futures):
                    print(f"{name}: reranked {done}/{len(futures)}", flush=True)
                if done % 25 == 0:
                    # Persist checkpoints so a model-server restart or a
                    # deliberate pause does not discard completed calls.
                    cache_path.write_text(
                        json.dumps({"rankings": cached}, ensure_ascii=False),
                        encoding="utf-8",
                    )
        cache_path.write_text(json.dumps({"rankings": cached}, ensure_ascii=False), encoding="utf-8")
    ranked = {str(q["id"]): np.array(cached[str(q["id"])], dtype=np.int32) for q in rows}
    return ranked, {
        "rerankCandidates": rerank_n,
        "cache": str(cache_path.relative_to(ROOT)),
        "newCalls": len(pending),
        "fallbacksThisRun": fallbacks,
        "latencySecondsThisRun": {
            "p50": statistics.median(latencies) if latencies else None,
            "p95": float(np.percentile(latencies, 95)) if latencies else None,
        },
    }


def method_metrics(rows: list[dict[str, Any]], ranked: dict[str, np.ndarray], docs: list[dict[str, Any]]) -> dict[str, Any]:
    values = metrics(rows, [ranked[str(q["id"])] for q in rows], docs, FINAL_K)
    return {**values, **hard_negative_rate(rows, ranked, docs)}


def main() -> None:
    docs = read(DOC_PATH)["documents"]
    queries = read(QUERY_PATH)["queries"]
    rows = [q for q in queries if gold_for_query(q, docs)]
    cache = np.load(EMBED_CACHE, allow_pickle=False)
    vector_matrix = np.argsort(-(cache["query_vectors"] @ cache["doc_vectors"].T), axis=1, kind="stable")
    keyword_matrix = bm25_rank(docs, queries)
    query_index = {str(q["id"]): i for i, q in enumerate(queries)}
    vector = {str(q["id"]): vector_matrix[query_index[str(q["id"])]].astype(np.int32) for q in rows}
    keyword = {str(q["id"]): keyword_matrix[query_index[str(q["id"])]].astype(np.int32) for q in rows}

    vector_top3 = {qid: value[:3] for qid, value in vector.items()}
    keyword_top3 = {qid: value[:3] for qid, value in keyword.items()}
    fused12_equal = {qid: weighted_rrf(vector[qid], keyword[qid], 12, 0.5) for qid in vector}
    fused20_equal = {qid: weighted_rrf(vector[qid], keyword[qid], 20, 0.5) for qid in vector}
    fused20_tuned = {qid: weighted_rrf(vector[qid], keyword[qid], 20, 0.5) for qid in vector}

    rerank_specs = {
        "F_hybrid12_rerank6": (fused12_equal, 6),
        "F_hybrid12_rerank12": (fused12_equal, 12),
        "G_hybrid20_rerank12": (fused20_equal, 12),
        "G_hybrid20_rerank20": (fused20_equal, 20),
    }
    methods: dict[str, dict[str, Any]] = {
        "A_vector_hnsw_top3": method_metrics(rows, vector_top3, docs),
        "B_bm25_top3": method_metrics(rows, keyword_top3, docs),
        "C_hybrid_equal_candidate12_top3": method_metrics(rows, fused12_equal, docs),
        "D_hybrid_equal_candidate20_top3": method_metrics(rows, fused20_equal, docs),
        "E_hybrid_tuned_candidate20_weight05_05_top3": method_metrics(rows, fused20_tuned, docs),
    }
    rerank_meta = {}
    for name, (fused, rerank_n) in rerank_specs.items():
        ranked, meta = run_rerank_variant(name, fused, rows, docs, rerank_n)
        methods[name] = method_metrics(rows, ranked, docs)
        rerank_meta[name] = meta

    milvus = read(MILVUS_RESULT)
    chosen_hnsw = next(
        (item for item in milvus.get("variants", []) if item.get("M") == 16 and item.get("ef") == 64),
        {},
    )
    result = {
        "experiment": "enterprise_fixed_matrix_A_to_G",
        "date": "2026-09-16",
        "finalTopK": FINAL_K,
        "rrfK": RRF_K,
        "dataset": {
            "documents": len(docs),
            "queriesTotal": len(queries),
            "answerableQueries": len(rows),
            "noAnswerQueries": len(queries) - len(rows),
            "embeddingMethod": "cached Qwen3-Embedding exact cosine proxy; HNSW metrics are read from the separate Milvus Flat/HNSW run",
        },
        "definitions": {
            "recallAt3": "每题正确文档中有多少比例进入最终Top3，再对所有可回答问题取平均",
            "mrrAt3": "每题第一个正确文档排名的倒数（第1名=1，第2名=0.5，第3名=0.333，未命中=0），再取平均",
            "hitRateAt3": "Top3中至少出现一个正确文档的问题比例；单正确文档时等于Top3命中率",
            "candidateN": "每个检索分支在融合前返回的候选数",
            "rerankN": "RRF融合后交给LLM精排的候选数；精排完成后再取最终Top3",
        },
        "matrix": {
            "A_vector_hnsw_top3": {"retrieval": "Milvus HNSW M=16, efConstruction=128, ef=64, limit=3", "milvus": chosen_hnsw},
            "B_bm25_top3": {"retrieval": "BM25 limit=3"},
            "C_hybrid_equal_candidate12_top3": {"retrieval": "向量Top12 + BM25 Top12 → 等权RRF → Top3"},
            "D_hybrid_equal_candidate20_top3": {"retrieval": "向量Top20 + BM25 Top20 → 等权RRF → Top3"},
            "E_hybrid_tuned_candidate20_weight05_05_top3": {"retrieval": "向量Top20 + BM25 Top20 → 权重0.5/0.5 RRF → Top3", "selection": "按调参集MRR/Recall选择；仍需独立测试集确认"},
            "F_hybrid12_rerank6": {"retrieval": "向量Top12 + BM25 Top12 → RRF → LLM Rerank Top6 → Top3"},
            "F_hybrid12_rerank12": {"retrieval": "向量Top12 + BM25 Top12 → RRF → LLM Rerank Top12 → Top3"},
            "G_hybrid20_rerank12": {"retrieval": "向量Top20 + BM25 Top20 → RRF → LLM Rerank Top12 → Top3"},
            "G_hybrid20_rerank20": {"retrieval": "向量Top20 + BM25 Top20 → RRF → LLM Rerank Top20 → Top3"},
        },
        "methods": methods,
        "rerank": rerank_meta,
        "limitations": [
            "企业扩充集中的新增文档和问题主要是确定性合成数据，结果用于工程回归和压力测试，不是独立人工基准。",
            "LLM精排复用了项目当前Prompt、300字符候选截断和Qwen3:4b本地模型；它不是训练好的交叉编码器。",
            "A的HNSW业务指标沿用576向量上的Milvus实测；本矩阵的候选Top12/Top20仍需在目标规模Milvus上复核。",
        ],
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(OUT), "methods": methods, "rerank": rerank_meta}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
