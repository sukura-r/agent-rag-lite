"""Build a deterministic local DuRetrieval benchmark subset from official Parquet files."""

from __future__ import annotations

import argparse
import hashlib
import json
import random
from pathlib import Path

import pyarrow.parquet as parquet


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--query-count", type=int, default=300)
    parser.add_argument("--corpus-count", type=int, default=5000)
    parser.add_argument("--seed", type=int, default=20260904)
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_rows(path: Path, dataset: str, split: str, rows: list[dict]) -> None:
    payload = {
        "dataset": dataset,
        "config": "default",
        "split": split,
        "rows": [{"row_idx": index, "row": row, "truncated_cells": []}
                 for index, row in enumerate(rows)],
        "truncated": False,
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


def main() -> None:
    args = parse_args()
    query_path = args.input_dir / "queries.parquet"
    corpus_path = args.input_dir / "corpus.parquet"
    qrels_path = args.input_dir / "qrels.parquet"

    queries = parquet.read_table(query_path).to_pylist()
    corpus = parquet.read_table(corpus_path).to_pylist()
    positive_qrels = [row for row in parquet.read_table(qrels_path).to_pylist() if row["score"] > 0]

    qrels_query_ids = {row["qid"] for row in positive_qrels}
    eligible_queries = [row for row in queries if row["id"] in qrels_query_ids]
    if args.query_count > len(eligible_queries):
        raise ValueError(f"query-count {args.query_count} exceeds eligible queries {len(eligible_queries)}")

    randomizer = random.Random(args.seed)
    randomizer.shuffle(eligible_queries)
    selected_queries = eligible_queries[:args.query_count]
    selected_query_ids = {row["id"] for row in selected_queries}
    selected_qrels = [row for row in positive_qrels if row["qid"] in selected_query_ids]
    relevant_document_ids = {row["pid"] for row in selected_qrels}

    corpus_by_id = {row["id"]: row for row in corpus}
    missing_document_ids = relevant_document_ids - corpus_by_id.keys()
    if missing_document_ids:
        raise ValueError(f"official corpus is missing {len(missing_document_ids)} relevant documents")
    if len(relevant_document_ids) > args.corpus_count:
        raise ValueError("corpus-count is smaller than the number of required relevant documents")

    relevant_documents = [corpus_by_id[document_id] for document_id in sorted(relevant_document_ids)]
    negative_pool = [row for row in corpus if row["id"] not in relevant_document_ids]
    selected_negatives = randomizer.sample(negative_pool, args.corpus_count - len(relevant_documents))
    selected_documents = relevant_documents + selected_negatives
    randomizer.shuffle(selected_documents)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    write_rows(args.output_dir / "public-duretrieval-queries-source.json",
               "C-MTEB/DuRetrieval", "queries", selected_queries)
    write_rows(args.output_dir / "public-duretrieval-corpus-source.json",
               "C-MTEB/DuRetrieval", "corpus", selected_documents)
    write_rows(args.output_dir / "public-duretrieval-qrels-source.json",
               "C-MTEB/DuRetrieval-qrels", "dev", selected_qrels)

    metadata = {
        "dataset": "C-MTEB/DuRetrieval",
        "qrelsDataset": "C-MTEB/DuRetrieval-qrels",
        "datasetRevision": "a1a333e290fe30b10f3f56498e3a0d911a693ced",
        "qrelsRevision": "497b7bd1bbb25cb3757ff34d95a8be50a3de2279",
        "paper": "DuReader_retrieval: A Large-scale Chinese Benchmark for Passage Retrieval from Web Search Engine",
        "paperUrl": "https://aclanthology.org/2022.emnlp-main.357/",
        "sourceUrl": "https://huggingface.co/datasets/C-MTEB/DuRetrieval",
        "selection": (
            f"Official Parquet revisions; deterministic random sample of {args.query_count} from "
            f"{len(eligible_queries)} queries with seed {args.seed}; candidate corpus contains every official "
            f"positive document for selected queries and deterministic random negatives up to {args.corpus_count}"
        ),
        "queryCount": len(selected_queries),
        "corpusCount": len(selected_documents),
        "positiveQrelCount": len(selected_qrels),
        "populationQueryCount": len(queries),
        "populationCorpusCount": len(corpus),
        "samplingSeed": args.seed,
        "sourceSha256": {
            "queries": sha256(query_path),
            "corpus": sha256(corpus_path),
            "qrels": sha256(qrels_path),
        },
        "licenseNote": (
            "The Hugging Face dataset card and C-MTEB task metadata do not declare a license. "
            "This derived subset is retained only for local research evaluation with source attribution, "
            "not redistribution as a standalone dataset."
        ),
    }
    (args.output_dir / "public-duretrieval-metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")

    print(json.dumps({
        "queryCount": len(selected_queries),
        "corpusCount": len(selected_documents),
        "positiveQrelCount": len(selected_qrels),
        "relevantDocumentCount": len(relevant_document_ids),
        "negativeDocumentCount": len(selected_negatives),
        "seed": args.seed,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
