from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
EVAL = ROOT / "src" / "main" / "resources" / "evaluation"
OUT = ROOT / "docs" / "evaluation-results" / "enterprise-dataset-quality-20260916.json"


FACTS: dict[str, list[str]] = {
    "q01": ["提前三个工作日"],
    "q02": ["2026年7月1日"],
    "q03": ["有效发票", "行程单", "住宿清单", "审批通过的出差申请"],
    "q04": ["五个工作日内"],
    "q05": ["医院证明"],
    "q06": ["有效期两年", "到期前六十天"],
    "q07": ["三家供应商比价", "采购委员会审批"],
    "q08": ["两小时内冻结"],
    "q09": ["五分钟内响应", "十分钟内通知"],
    "q10": ["敏感数据"],
    "q11": ["Secret管理系统", "九十天轮换"],
    "q12": ["一个工作日预约"],
    "q13": ["一个工作日内", "OA流程"],
    "q14": ["项目委员会审批"],
    "q15": ["三十分钟"],
    "q16": ["财务负责人", "分管副总会签"],
    "q17": ["次年第一季度"],
    "q18": ["生产密钥", "核心算法参数", "大规模客户明细", "公共网盘"],
    "q19": ["两万元", "分管副总审批"],
    "q20": [],
    "q21": ["提前三个工作日", "次年第一季度"],
    "d12-q01": ["六个月内"],
    "d12-q02": ["十个工作日内"],
    "d12-q03": ["2026年8月1日"],
    "d12-q04": ["分管副总审批"],
    "d12-q05": ["五万元", "财务负责人确认"],
    "d12-q06": ["五分钟内响应", "十分钟内通知"],
    "d12-q07": ["四十五天未使用", "暂停"],
    "d12-q08": ["数据负责人", "信息安全负责人", "会签"],
    "d12-q09": ["三百六十五天"],
    "d12-q10": ["区域负责人审批", "员工全程陪同"],
    "d12-q11": ["六十分钟"],
    "d12-q12": ["五个工作日", "十个工作日"],
    "d12-q13": ["两小时内"],
    "d12-q14": ["数据负责人", "信息安全负责人", "会签"],
    "d12-q15": [],
    "d12-q16": [],
    "d12-q17": ["技术负责人", "业务负责人批准", "六十分钟"],
    "d12-q18": ["一个工作日"],
}

RELEVANT_TEXT_OVERRIDES: dict[str, list[str]] = {
    # “还需分管副总审批” appears in both travel and training policies.  The
    # question is specifically about travel reimbursement, so the gold phrase
    # must retain the unique threshold context instead of creating an
    # ambiguous document-level label.
    "q19": ["单笔费用超过两万元时，还需分管副总审批"],
}


def load(name: str, key: str) -> list[dict[str, Any]]:
    data = json.loads((EVAL / name).read_text(encoding="utf-8"))
    return data[key]


def main() -> None:
    query_files = ["day6-queries.json", "day12-queries.json"]
    queries: list[dict[str, Any]] = []
    for name in query_files:
        path = EVAL / name
        data = json.loads(path.read_text(encoding="utf-8"))
        for query in data["queries"]:
            if query["id"] not in FACTS:
                raise RuntimeError(f"no required facts mapping for {query['id']}")
            query["answerFacts"] = FACTS[query["id"]]
            if query["id"] in RELEVANT_TEXT_OVERRIDES:
                query["relevantTexts"] = RELEVANT_TEXT_OVERRIDES[query["id"]]
            queries.append(query)
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    documents: list[dict[str, Any]] = []
    for name in ["day6-corpus.json", "day12-corpus.json"]:
        documents.extend(load(name, "documents"))

    mapping: dict[str, Any] = {}
    ambiguous: dict[str, list[str]] = {}
    for query in queries:
        gold_documents: set[str] = set()
        text_matches: dict[str, list[str]] = {}
        for relevant in query.get("relevantTexts", []):
            matches = [doc["key"] for doc in documents if relevant and relevant in doc["content"]]
            text_matches[relevant] = matches
            gold_documents.update(matches)
        if any(len(matches) > 1 for matches in text_matches.values()):
            ambiguous[query["id"]] = [doc for docs in text_matches.values() for doc in docs]
        mapping[query["id"]] = {
            "type": query["type"],
            "requiredFacts": FACTS[query["id"]],
            "goldDocumentKeys": sorted(gold_documents),
            "textMatches": text_matches,
            "hardNegativeCount": len(query.get("hardNegativeTexts", [])),
        }

    type_counts = Counter(query["type"] for query in queries)
    domain_counts = Counter(doc["domain"] for doc in documents)
    source_counts = Counter(doc["sourceType"] for doc in documents)
    explicit_fact_counts = Counter(len(query.get("answerFacts", [])) for query in queries)
    no_answer = sum(not query.get("relevantTexts") for query in queries)
    quality = {
        "dataset": "enterprise-knowledge-v2",
        "generatedAt": "2026-09-16",
        "documents": len(documents),
        "queries": len(queries),
        "answerableQueries": len(queries) - no_answer,
        "noAnswerQueries": no_answer,
        "questionTypes": dict(sorted(type_counts.items())),
        "documentDomains": dict(sorted(domain_counts.items())),
        "documentSourceTypes": dict(sorted(source_counts.items())),
        "requiredFactCountDistribution": dict(sorted((str(k), v) for k, v in explicit_fact_counts.items())),
        "hardNegativeQuestionCount": sum(bool(q.get("hardNegativeTexts")) for q in queries),
        "hardNegativeTextCount": sum(len(q.get("hardNegativeTexts", [])) for q in queries),
        "ambiguousTextLabelQuestions": sorted(ambiguous),
        "ambiguousTextLabelCount": len(ambiguous),
        "allQuestionsHaveExplicitRequiredFacts": all("answerFacts" in q for q in queries),
        "labelPolicy": {
            "retrieval": "goldDocumentKeys derived from relevantTexts and manually reviewed for ambiguous matches",
            "generation": "requiredFacts are separately listed; all facts must be covered for answerable correctness",
            "noAnswer": "empty requiredFacts and empty relevantTexts; correct behavior is explicit refusal",
        },
        "limitations": [
            "Questions and documents are project-authored; this is a business regression set, not a universal benchmark.",
            "The current audit detects ambiguity but does not constitute a second human annotator review.",
            "A 39-question sample remains sensitive to individual questions; later runs should add independent questions and multiple seeds.",
        ],
        "queryAudit": mapping,
    }
    OUT.write_text(json.dumps(quality, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"updated answerFacts in {', '.join(query_files)}")
    print(f"wrote {OUT}")
    print(json.dumps({k: quality[k] for k in ["queries", "answerableQueries", "noAnswerQueries", "hardNegativeQuestionCount", "ambiguousTextLabelQuestions", "allQuestionsHaveExplicitRequiredFacts"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
