# 知衡 Agent · agent-rag-lite

面向企业制度与流程查询的 Java RAG Agent。支持文档异步解析、混合检索与精排、工具调用、会话记忆，以及可持久化和断线续传的 SSE 问答。

## 主要能力

- 文档：DOCX、文本型 PDF、TXT、Markdown；MinIO 保存原件，异步解析、分块和向量索引。
- 检索：Milvus 向量检索、BM25、中文 Bigram、RRF 融合与模型精排。
- Agent：Function Calling、MCP 工具发现与调用、会话恢复、取消和并发限制。
- 一致性：MySQL Outbox、RocketMQ 索引分发、文档蓝绿版本与失败补偿。
- 工作台：Vue 3、TypeScript、Pinia；问答、会话、引用溯源和文档管理。

## 环境

JDK 21（Maven 编译目标为 17）、Maven 3.9、Node.js 22.12+ 或 24、Docker Compose，以及 Ollama。

本地默认端口：应用 8080、MySQL 23306、Redis 26379、Milvus 19530、MinIO 9000/9001、RocketMQ 9876/10911、Ollama 11434。

## 本地启动

以下命令从仓库根目录执行。Compose 提供 MySQL、etcd、MinIO、Milvus 和 Attu；Redis 与 RocketMQ 需另外启动。

```powershell
docker compose up -d
docker compose -f deploy/rocketmq/compose.yml up -d
ollama pull qwen3:4b-instruct
ollama pull qwen3-embedding:0.6b
```

Linux Redis 配置与 systemd 单元见 `infra/`，监听本机 26379 端口；也可以使用自己的 Linux Redis 并设置 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`。使用 WSL2 时，Redis 和 RocketMQ 地址应填入 Windows 实际能访问的地址；RocketMQ Broker 的 `brokerIP1` 也必须是 Java 客户端可访问的地址。

构建前端，再启动后端：

```powershell
Push-Location frontend
npm ci
npm run build
Pop-Location
mvn spring-boot:run
```

访问 <http://localhost:8080/>，文档管理入口为 <http://localhost:8080/console/#/documents>。

```powershell
Invoke-RestMethod http://localhost:8080/api/system/health | ConvertTo-Json -Depth 10
```

`.env.example` 是变量清单。Spring Boot 不会自动读取根目录 `.env`，请在终端或 IDE 运行配置中设置变量，例如：

```powershell
$env:ROCKETMQ_NAMESERVER = "127.0.0.1:9876"
$env:CHAT_MODEL = "qwen3:4b-instruct"
mvn spring-boot:run
```

默认 `local-user-token`、`local-admin-token`、`local-mcp-token` 仅用于本地开发。生产配置见 `deploy/.env.production.example` 与 `application-prod.yml`，必须替换密码和令牌。

## 开发与验证

```powershell
Push-Location frontend
npm ci
npm test
npm run build
Pop-Location
mvn clean test
mvn -DskipTests package
```

**先构建前端再执行 Maven**。静态页面测试会检查真实构建产物；产物位于 `src/main/resources/static/console/`，由 Git 忽略。前端开发运行 `npm run dev`，访问 <http://localhost:5173/console/>，API 代理到 8080。

## 目录与资料

| 目录 | 内容 |
| --- | --- |
| `src/main/java/` | 后端业务与评测接口 |
| `src/test/` | 后端自动化测试 |
| `frontend/` | Vue 工作台源码与依赖锁文件 |
| `deploy/`、`infra/` | 部署配置示例 |
| `scripts/` | 数据集建设与离线实验工具 |
| `测试/` | 可手工上传的四种格式样本 |