"""Build a clearly labelled, deterministic scale-expansion dataset.

The original 26 enterprise documents remain untouched.  This artifact adds
500 synthetic current-policy documents and 50 synthetic old-version hard
negatives, plus 1000 answerable and 20 no-answer queries.  It is intentionally
marked as synthetic scale data: it tests retrieval at a larger candidate size,
but it is not presented as real company data or as an independent human
benchmark.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
EVAL = ROOT / "agent-rag-lite" / "src" / "main" / "resources" / "evaluation"
CORPUS_OUT = EVAL / "enterprise-expanded-corpus-v1.json"
QUERY_OUT = EVAL / "enterprise-expanded-queries-v1.json"


DOMAINS = [
    ("人事", "HR", "员工排班调整", "人事负责人", "排班申请单", "岗位变更说明"),
    ("财务", "FIN", "项目费用预支", "财务负责人", "预算明细", "项目负责人意见"),
    ("采购", "PROC", "临时供应商准入", "采购负责人", "供应商报价", "资质核验单"),
    ("IT运维", "IT", "运维变更申请", "系统负责人", "变更方案", "回滚计划"),
    ("信息安全", "SEC", "数据访问申请", "信息安全负责人", "访问范围说明", "风险评估表"),
    ("行政", "ADM", "会议资源申请", "行政负责人", "会议用途说明", "参会人员清单"),
    ("项目管理", "PM", "里程碑调整申请", "项目负责人", "影响分析", "更新后的计划"),
    ("客户服务", "CS", "客户升级工单", "客服负责人", "客户影响说明", "处理方案"),
    ("研发", "RD", "研发环境资源申请", "研发负责人", "资源规格说明", "安全确认单"),
    ("合规", "COMP", "合规例外申请", "合规负责人", "例外原因", "补救措施"),
]


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def build_documents() -> list[dict[str, Any]]:
    base: list[dict[str, Any]] = []
    for name in ("day6-corpus.json", "day12-corpus.json"):
        base.extend(read_json(EVAL / name)["documents"])

    expansion: list[dict[str, Any]] = []
    for i in range(500):
        domain, code, policy, approver, material_a, material_b = DOMAINS[i % len(DOMAINS)]
        days = 1 + ((i * 7) % 14)
        threshold = 2000 + ((i * 13) % 20) * 1000
        number = f"SCALE-{code}-{i + 1:04d}"
        effective = f"2026年{(i % 12) + 1}月1日"
        content = (
            f"制度编号为{number}。本文件是规模评测用的合成制度，不代表任何真实公司的制度。"
            f"{domain}{policy}应至少提前{days}个工作日提交，金额或资源量超过{threshold}元时，"
            f"必须由{approver}审批。申请材料包括{material_a}、{material_b}和申请人说明。"
            f"本规则自{effective}起执行，审批完成后应保留记录用于审计。"
        )
        expansion.append({
            "key": f"scale-{code.lower()}-{i + 1:04d}",
            "domain": domain,
            "title": f"{policy}规模评测规则{i + 1:04d}",
            "sourceType": "SYNTHETIC_SCALE_EXPANSION",
            "version": 1,
            "publicDisplay": False,
            "content": content,
            "scaleMetadata": {"generated": True, "template": "policy-v1", "seed": 20260916},
        })
        if i % 10 == 0:
            old_days = min(20, days + 5)
            old_threshold = max(1000, threshold - 5000)
            old_key = f"scale-{code.lower()}-{i + 1:04d}-old"
            old_content = (
                f"制度编号为{number}-OLD。该旧版本已废止，仅用于规模评测困难负例。"
                f"旧规则要求提前{old_days}个工作日提交，金额超过{old_threshold}元时由{approver}审批；"
                f"当前问题不得引用此版本，应以{number}现行规则为准。"
            )
            expansion.append({
                "key": old_key,
                "domain": domain,
                "title": f"{policy}旧版规模评测负例{i + 1:04d}",
                "sourceType": "SYNTHETIC_SCALE_HARD_NEGATIVE",
                "version": 0,
                "publicDisplay": False,
                "content": old_content,
                "scaleMetadata": {"generated": True, "template": "old-policy-negative-v1", "seed": 20260916},
            })
    return base + expansion


def build_queries(documents: list[dict[str, Any]]) -> list[dict[str, Any]]:
    base: list[dict[str, Any]] = []
    for name in ("day6-queries.json", "day12-queries.json"):
        base.extend(read_json(EVAL / name)["queries"])
    expansion_docs = [d for d in documents if d.get("sourceType") == "SYNTHETIC_SCALE_EXPANSION"]
    by_prefix = {d["key"].rsplit("-", 1)[0]: d for d in documents
                 if d.get("sourceType") == "SYNTHETIC_SCALE_HARD_NEGATIVE"}
    queries: list[dict[str, Any]] = []
    for i, document in enumerate(expansion_docs):
        content = document["content"]
        # The generated phrase is explicit and independently auditable.
        import re
        days = re.search(r"至少提前(\d+)个工作日", content).group(1)
        threshold = re.search(r"超过(\d+)元", content).group(1)
        approver = re.search(r"必须由(.+?)审批", content).group(1)
        code = re.search(r"制度编号为(SCALE-[A-Z]+-\d+)", content).group(1)
        fact_time = f"{days}个工作日"
        fact_approval = f"{approver}审批"
        negative = by_prefix.get(document["key"])
        hard = [negative["content"]] if negative else []
        common = {
            "relevantTexts": [f"至少提前{days}个工作日提交", f"必须由{approver}审批"],
            "answerFacts": [fact_time, fact_approval],
            "hardNegativeTexts": hard,
            "goldDocumentKeys": [document["key"]],
            "scaleMetadata": {"generated": True, "sourceDocument": document["key"], "seed": 20260916},
        }
        queries.append({
            "id": f"scale-q-{i + 1:04d}-direct",
            "type": "SCALE_DIRECT",
            "question": f"{document['domain']}的{document['title'].split('规模评测规则')[0]}要提前多久提交，超过多少由谁审批？",
            **common,
        })
        queries.append({
            "id": f"scale-q-{i + 1:04d}-rewrite",
            "type": "SCALE_SEMANTIC_REWRITE",
            "question": f"查一下制度{code}的申请时限和审批门槛。",
            **common,
        })
    for i in range(20):
        queries.append({
            "id": f"scale-noanswer-{i + 1:02d}",
            "type": "SCALE_NO_ANSWER",
            "question": f"制度SCALE-NOT-EXIST-{i + 1:04d}是否规定了{31 + i}个工作日内完成？",
            "relevantTexts": [],
            "answerFacts": [],
            "hardNegativeTexts": [],
            "goldDocumentKeys": [],
            "scaleMetadata": {"generated": True, "noAnswer": True, "seed": 20260916},
        })
    return base + queries


def main() -> None:
    documents = build_documents()
    queries = build_queries(documents)
    corpus = {
        "name": "enterprise-expanded-scale-v1",
        "description": "26 existing documents plus 500 synthetic scale documents and 50 synthetic old-version negatives",
        "sourcePolicy": "The 500+50 expansion is deterministic synthetic scale data, not real company data.",
        "documents": documents,
    }
    query_set = {
        "name": "enterprise-expanded-scale-queries-v1",
        "description": "Existing 39 questions plus 1000 generated answerable queries and 20 generated no-answer queries",
        "sourcePolicy": "Generated labels are explicit and auditable; use for scale regression, not human preference claims.",
        "queries": queries,
    }
    CORPUS_OUT.write_text(json.dumps(corpus, ensure_ascii=False, indent=2), encoding="utf-8")
    QUERY_OUT.write_text(json.dumps(query_set, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {CORPUS_OUT}: documents={len(documents)}")
    print(f"wrote {QUERY_OUT}: queries={len(queries)}")


if __name__ == "__main__":
    main()
