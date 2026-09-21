# ChargeInsight

ChargeInsight 是面向充电运营场景构建的自然语言数据分析系统。用户可围绕区域、城市、桩群、时间范围进行指标查询、趋势分析、排行对比与异常归因；系统通过受控 Agent Runtime 编排数据工具，并以混合检索、受控 Text-to-SQL、分层记忆及可观测机制提升分析准确性、安全性与可追溯性。

**技术栈：** Spring Boot、MySQL、Redis、Embedding、BM25、RRF、JSqlParser、MCP、Docker

## 项目亮点

- **受控 Agent Runtime：** 面向运营指标、趋势、排行与异常归因，基于有限状态机固化“下一步决策—工具执行—观测反馈—结果校验—结论生成”链路；异常归因按观测结果最多补充一次调用，避免批量盲调并保留全链路审计。
- **混合检索增强：** 对口语化运营提问语义模糊问题，采用 Embedding 向量检索 + BM25 召回，并以 RRF 融合排序；检索 Hit@3 达 95.83%，提升知识命中稳定性。
- **AST 安全 SQL 兜底：** 针对固定工具无法覆盖的临时分析，构建“自然语言解析—SQL 生成—JSqlParser AST 校验—EXPLAIN 预检—只读执行—一次修复”的 Text-to-SQL 链路；通过视图白名单、区域日期约束与 LIMIT 防止跨区域查询、全表扫描及过量返回，版本化 SQL 安全回归中有效放行与危险拦截均为 100%。
- **分层记忆系统：** 以 Runtime 工作状态保存当前分析范围、计划与观测；MySQL 持久化会话，滑动窗口超限后生成结构化摘要；将用户确认偏好向量化，实现按用户隔离的长期语义召回。
- **Harness Trace 可观测：** 构建版本化 Harness 回归，覆盖口语检索、规划路由、SQL 安全及异常归因；以 Trace ID 串联状态迁移、工具调用结果与本地 MCP 工单审计，支持按 Trace 回溯异常场景并定位问题。

## 目录

```text
src/main/java/com/chargeinsight
├── agent
│   ├── api          # Agent、SSE、Trace、评测与受控 SQL 接口
│   ├── chat         # 会话与可选 Redis 短期记忆
│   ├── knowledge    # 指标知识检索与目录
│   ├── planning     # 结构化分析计划与策略校验
│   ├── runtime      # 有限步 Agent Loop 与结果检查
│   ├── sql          # 受控 Text-to-SQL 校验与执行
│   ├── tool         # 参数化运营分析工具与实体解析
│   └── trace        # 运行审计 Trace
├── analytics        # 演示数据、CSV 导入与日聚合
├── mcp              # MCP 报表导出与本地模拟工单
├── security         # JWT、用户与区域访问控制
└── common           # 健康检查与统一异常响应

infra/mysql/init     # MySQL 初始化脚本
samples              # 脱敏的订单与故障 CSV 样例
```

## Agent 工作流

```text
运营问题
  -> 会话与权限范围恢复
  -> 指标知识检索
  -> 结构化分析计划
  -> 领域工具或受控 Text-to-SQL
  -> 数据集与证据检查
  -> 结构化运营结论、Trace 与 SSE 事件
```

模型仅用于生成结构化计划，以及在领域工具无法覆盖时生成或修复受控 SQL。时间解析、实体解析、权限、SQL AST 校验、结果检查和最终回答渲染由 Java 实现，避免未经验证的模型输出成为业务结论。

## 受控运行边界

- 运行时是单 Agent、有界状态机，不包含多 Agent 编排、ReAct 自由循环或自研 MCP 网关。
- 状态机以计划、查询、结果检查与回答为主链路；观察结果仅可触发至多一次补充工具调用。
- Text-to-SQL 先经 JSqlParser AST 校验，再进行 EXPLAIN 行数预检；实际查询使用独立 Reader 身份，只允许两个语义视图和最大返回行数。自动 Agent 兜底还会强制服务端限定的区域与日期范围；生成失败时最多允许一次修复。
- Trace 记录状态迁移、受控 SQL 和 MCP 本地工单/报表工具的安全摘要；不记录数据库凭据、模型密钥或原始敏感配置。

## 可选检索与记忆能力

- 向量检索默认关闭。启用 `CHARGE_VECTOR_ENABLED=true` 后，知识索引使用 Qdrant；索引构建由管理员显式触发，不在应用启动时隐式执行。
- 生产检索采用 BM25、Dense 与 RRF 融合；Dense 服务不可用或候选不足时会降级为 BM25，并在 Trace 中保留检索策略摘要。
- 会话消息以 MySQL 为审计源，Redis 仅作为可选短期缓存；超过阈值时生成确定性的结构化会话摘要，原始消息不删除。
- 长期记忆由用户通过显式接口主动保存；MySQL 是 owner-scoped 的事实源，Qdrant 仅为可重建的派生语义索引。

## 环境要求

| 软件 | 版本/用途 | 是否必需 |
| --- | --- | --- |
| JDK | 17 | 构建和运行必需 |
| Maven | 3.9+ | 本地构建和运行必需 |
| Docker / Docker Compose | MySQL、Redis、Qdrant 与 SQLBot | 推荐 |
| OpenAI 兼容模型服务 | 结构化计划与受控 SQL 生成 | Agent 分析功能必需 |

## 本地快速运行

在项目根目录创建本地变量文件，并仅在本机填写模型密钥：

```bash
cp .env.example .env
```

启动依赖服务：

```bash
docker compose up -d mysql redis qdrant sqlbot
docker compose ps
```

导入本地变量后启动应用：

```bash
set -a
source .env
set +a

JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  /opt/homebrew/opt/maven/bin/mvn spring-boot:run
```

默认服务端口为 `8080`；端口冲突时可指定其他端口：

```bash
SERVER_PORT=8081 /opt/homebrew/opt/maven/bin/mvn spring-boot:run
```

访问 `http://localhost:8080/`（或实际端口）打开浏览器演示台。

> `.env` 包含本地密钥和密码，已被 Git 忽略，绝不能提交。更完整的本地配置说明保留在本地 `docs/` 目录中，该目录同样不进入公开仓库。

若需启用显式受控 SQL 执行，除主数据源外还必须在私有环境中配置独立的只读 Reader：

```text
CHARGE_SQL_READER_ENABLED=true
CHARGE_SQL_READER_URL=<私有 JDBC URL>
CHARGE_SQL_READER_USERNAME=<私有 Reader 用户名>
CHARGE_SQL_READER_PASSWORD=<私有 Reader 密码>
```

Reader 仅应被授予 `v_daily_group_operation` 与 `v_daily_fault_analysis` 的读取权限。不要把实际值写入仓库、README、截图或 Issue。

## 启动验证

```bash
curl http://localhost:8080/api/health
```

预期返回 `status: SUCCESS`。初始化脱敏演示数据后，可以执行一次分析：

```bash
curl -X POST 'http://localhost:8080/api/admin/demo-data/init?days=60'

curl -X POST http://localhost:8080/api/agent/analyze \
  -H 'Content-Type: application/json' \
  -d '{"question":"为什么华东上海私桩共享桩群1最近一周GMV较低？"}'
```

主要接口：

| 接口 | 说明 |
| --- | --- |
| `GET /api/health` | 服务健康状态 |
| `POST /api/agent/plan` | 生成并校验结构化分析计划 |
| `POST /api/agent/analyze` | 执行完整分析工作流 |
| `POST /api/agent/analyze/stream` | 以 SSE 返回工作流节点事件 |
| `GET /api/agent/sessions` | 查看当前用户会话 |
| `GET /api/agent/traces/{traceId}` | 查询单次运行的审计 Trace |
| `GET /api/agent/memories` | 查看当前用户主动保存的长期记忆 |
| `POST /api/agent/memories` | 显式保存当前用户的长期记忆 |
| `GET /api/agent/sql/status` | 查询独立 Reader 是否就绪，不返回连接信息 |
| `POST /api/agent/sql/validate` | 仅执行 Text-to-SQL AST 与白名单校验 |
| `POST /api/agent/sql/execute` | 执行经校验、EXPLAIN 预检的受控只读 SQL |
| `GET /api/agent/evaluations/cases` | 查看版本化评测案例 |
| `GET /api/admin/knowledge-index` | 查看向量知识索引状态（管理员） |
| `POST /api/admin/knowledge-index/rebuild` | 显式重建向量知识索引（管理员） |
| `GET /api/admin/knowledge-index/evaluation` | 对比 BM25、Dense 与 RRF 检索评测（管理员） |

## 认证与安全

默认关闭 JWT，便于本地首次演示。启用认证时，必须在私有环境变量中配置随机 JWT 签名密钥和管理员初始密码；不要把真实值写入代码、Compose 文件、截图或聊天记录。

启用后：

- `ADMIN` 可以使用管理、Trace、评测、受控 Text-to-SQL 与 Agent 接口；
- `ANALYST` 只能使用由领域工具驱动的 Agent 接口；
- 所有分析查询还会按 JWT 中的区域范围二次校验。

MCP 服务默认关闭；启用后 `/mcp` 仅接受具有 `ADMIN` 角色的 Bearer Token。

## 构建与测试

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  /opt/homebrew/opt/maven/bin/mvn test

JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  /opt/homebrew/opt/maven/bin/mvn package
```

构建产物位于 `target/`，该目录是本地生成文件，不进入版本控制。

`mvn test` 会生成核心 Agent 包的 JaCoCo 覆盖率报告，位置为 `target/site/jacoco/index.html`。报告是本地构建产物，不应提交。

## 技术栈

Java 17、Spring Boot 3.3、Spring AI、Spring JDBC、Spring Security、MySQL 8.4、Redis、Qdrant、JSqlParser、MCP、SSE、JaCoCo、Docker Compose。
