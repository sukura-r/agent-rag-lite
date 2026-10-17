# 离线实验工具

这些脚本用于数据建设和复现实验，保留为正式项目工具。Python 3.10+，可在独立虚拟环境中安装 `requirements.txt`；依赖清单不构成历史实验环境的精确锁定。

| 脚本 | 用途 |
| --- | --- |
| `build_enterprise_scale_dataset.py` | 建设扩展领域语料和问题集 |
| `enrich_enterprise_eval_dataset.py` | 补充领域标注 |
| `build-duretrieval-subset.py` | 从本地官方 Parquet 构建固定公开子集 |
| `enterprise_scale_eval.py` | 扩展领域评测与向量缓存生成 |
| `milvus_parameter_experiment.py` | Milvus 参数实验与公开集向量缓存生成 |
| `milvus_expanded_parameter_experiment.py` | 扩展领域 Milvus 实验 |
| `enterprise_weighted_rrf_experiment.py` | 加权融合实验 |
| `enterprise_fixed_matrix_experiment.py` | 固定检索与精排矩阵 |
| `enterprise_eval_quality_experiment.py` | 标注质量实验 |

从仓库根目录运行脚本。先阅读脚本顶部配置与 [数据说明](../docs/DATA-SOURCES.md)，确认服务地址、输入和输出；部分脚本会重写数据、创建临时向量集合或调用真实模型。无需为上传 GitHub 重跑这些耗时实验。
