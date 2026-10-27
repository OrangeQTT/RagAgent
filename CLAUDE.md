# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

「千言」AI 智能助手后端：基于 Spring Boot 3.5.9 + Java 17 + langchain4j（1.17.2 核心 / 1.17.2-beta27 社区）的 RAG 问答 Agent。模型全部走阿里云百炼（DashScope）Qwen 系列；对话记忆存 Redis；知识库向量存 PostgreSQL（pgvector）；可选 BM25 稀疏检索（ParadeDB pg_search）。前端是 `src/main/resources/front/` 下的静态 HTML，由外部静态服务器（nginx 等）托管，直接跨域调用后端。

## 常用命令

```bash
# 编译 + 打包（跳过测试通常更快；测试会启动完整 Spring 上下文，依赖 Redis/PG/百炼可达）
mvn clean package -DskipTests

# 跑全部测试 / 跑单个测试
mvn test
mvn test -Dtest=InfinteChatAgentApplicationTests

# 本地启动（默认 profile 是 prod，本地联调请先改成 dev 或加 -Dspring.profiles.active=dev）
mvn spring-boot:run
```

Docker 构建入口在根目录 `Dockerfile`（temurin:17-jre，UID 1001，`/init-docs` 的文档会复制进 `/data/docs`）。

## 架构（big picture）

### AI Agent 装配（入口）
- `ai/AiChat.java`：langchain4j `AiServices` 接口，定义 `chat()` / `streamChat()`，`@SystemMessage(fromResource = "system-prompt/chat-bot.txt")`，挂了 `SafeInputGuardrail` 敏感词拦截。
- `ai/AiChatService.java`：把 `ChatModel`、`StreamingChatModel`、RAG `ContentRetriever`、Redis 记忆、4 个 Tool、MCP `toolProvider` 装配成 `AiChat` Bean；并把 `skillTool.catalog()` 追加进系统提示词（`systemMessageTransformer`）。
- 控制器都在 `controller/AiChatController.java`：`POST /api/chat`（一次性）、`POST /api/streamChat`（`Flux<String>` 流式）、`POST /api/insert`（直接写知识库）。`ragas/EvalController.java` 是 `@Profile("dev","test")` 的 RAG 评测接口。

### 模型层（全部百炼/Qwen）
- `config/DashScopeModelConfig.java`：`QwenChatModel` + `QwenStreamingChatModel`（`qwen3-max`），都挂了 `AiModelMonitorListener`。
- `config/RerankModelConfig.java`：`QwenScoringModel`（`qwen3-rerank`）做精排。
- 关键点：`QwenScoringModel` 只有 `1.17.0-beta27` 起的 community 版才有，所以 pom 里 community BOM 用了 beta 版本——**升级 langchain4j 时 community 版本必须单独配套**。

### RAG 检索链路（两阶段）
```
docs 目录(Markdown) → RagDataLoader(启动时加载) → MarkdownHeadingSplitter(按 ### 切块)
  → text-embedding-v4 向量化 → PgVectorEmbeddingStore(dropTableFirst=true)
  → 检索: vectorContentRetriever(稠密) + PgBm25ContentRetriever(BM25稀疏)
  → HybridContentRetriever(RRF 融合) → RerankingContentRetriever(qwen3-rerank 精排, topK=5)
```
- 各环节都在 `config/RagConfig.java` 组装；最终对外出口是 `rerankingContentRetriever` Bean。
- `rag/splitter/MarkdownHeadingSplitter.java`：按三级标题切块，**一个 `###` 到下一个 `###` 是一个检索单元**——正文里以 `###` 开头的行会劈开知识，RagTool 里有兜底拦截。
- `job/PgBm25IndexInitializer.java`：灌库后建 BM25 索引。`config/PgSearchAvailability.java` 启动时探测 `pg_search` 扩展，**标准 PostgreSQL 上没有它 → BM25 整体跳过，退化为纯向量检索**（不阻塞启动，只是少一路召回）。

### 记忆与存储
- `config/RedisChatMemoryStoreConfig.java`：`MessageWindowChatMemory`，窗口 20 条，TTL 3600s。
- `config/EmbeddingStoreConfig.java`：`PgVectorEmbeddingStore`，`dropTableFirst(true)` —— **每次启动都重建表并全量重灌文档**，因此改文档 = 重启即重灌。
- `config/DataSourceConfig.java`：BM25 专用的独立 Hikari 数据源（复用 `pgvector.*` 参数，`bm25-pool`）。

### Skills 系统
- `skill/SkillTool.java`：启动时扫 `classpath*:skills/*/SKILL.md`，frontmatter 解析 `name`/`description`；L1 = `catalog()` 技能清单常驻系统提示词，L2 = `loadSkill` 工具按需取正文。新增技能 = 在 `src/main/resources/skills/<name>/SKILL.md` 写新文件后**重新打包**。

### MCP（联网搜索，可选）
- `config/McpToolConfig.java`：接智谱 BigModel `web_search` MCP。`bigmodel.api-key` 未配置（空或 `-`）时跳过；连接失败 try/catch 降级为空 provider，不阻塞启动；`initializationTimeout=10s` 快速失败。

### 监控（Prometheus）
- `Monitor/` 包：`AiModelMonitorListener`（langchain4j `ChatModelListener`）+ `AiModelMetricsCollector`（Micrometer）。暴露指标：`ai_model_requests_total`、`ai_model_errors_total`、`ai_model_tokens_total`、`ai_model_response_duration_seconds`，标签含 `user_id/session_id/model_name`。
- **必须的调用约定**：每次调 `chatModel` 前要 `MonitorContextHolder.setContext(...)`，调用完 `clearContext()`（ThreadLocal，Tomcat 线程复用，不清理会污染下一个请求；`EvalController` 里注释讲得很清楚）。

## 配置与密钥（敏感信息不入库）

- profile：`application-dev.yml`（本地 localhost Redis/PG）、`application-prod.yml`（部署环境）。`spring.profiles.active` 当前是 `prod`。
- 服务器端口 `10010`，context-path `/api`。
- **密钥全部外置，仓库里的 yml 只有 `${ENV_VAR:默认值}` 占位符**：
  - 通用密钥（mail、DashScope、BigModel）放根目录 `config/application.yml`；
  - 环境相关（Redis/PG 地址密码）放 `config/application-dev.yml` / `config/application-prod.yml`；
  - `config/*.yml` 已被 `.gitignore` 忽略，只有 `config/example.yml` 模板会入库。Spring Boot 会自动读取项目根目录（或 jar 同级）的 `config/`，优先级高于 classpath。
  - 也可以完全用环境变量启动，见 `config/example.yml` 顶部列出的变量名。
  - 新环境/新电脑：`cp config/example.yml config/application.yml` 后填真实值。
- **两类 key 不要混**：
  - 百炼/DashScope key 是 `sk-` 开头 → 填 `langchain4j.community.dashscope.*.api-key`（chat/streaming/embedding 三处）。
  - 智谱 BigModel key 是 `id.secret` 两段带点 → 填 `bigmodel.api-key`（或环境变量 `BIGMODEL_API_KEY`），只用于 MCP 联网搜索。

## 常见坑

1. **改 RAG 文档 = 重启**：`RagDataLoader` 只在启动时加载，且用 `Files.list` **不递归**（子目录里的文件不加载）。文档放 `rag.docs-path`（prod 为 `/data/docs`）根目录，重启后看日志「本地文档加载完成，共加载 N 个文档」。
2. **测试会启动完整 Spring 上下文**：`InfinteChatAgentApplicationTests` 需要 Redis、PG、以及百炼网络都可达，否则 contextLoads 失败。CI/打包可 `-DskipTests`。
3. **Lombok 是显式 optional 依赖**：升级 langchain4j 后它不再作为传递依赖带入，别删 pom 里那段。
4. **监控上下文**：任何绕过 `AiChatService` 直接调 `ChatModel` 的新代码，都要先 `MonitorContextHolder.setContext`，否则日志刷「MonitorContext is null」。
5. **前端是外部静态服务器托管**：Spring Boot 不提供 `front/` 静态映射。前端页面里硬编码了后端地址（如 `http://<host>:10010/api/streamChat`），改域名/端口要同步改 `src/main/resources/front/*.html`。
6. **缺 `config/` 会启动失败**：仓库里没有真实密钥，克隆后必须先按 `config/example.yml` 建好本地 `config/application.yml`（或设好环境变量），否则 DashScope/数据库连接会因空值报错。
