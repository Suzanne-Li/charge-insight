# ChargeInsight

ChargeInsight 是一个面向充换电运营场景的智能问数与异常分析 Agent。它以 Java/Spring Boot 为核心，帮助运营人员通过自然语言完成运营总览、趋势查询、桩群排行、故障分析和异常归因。

项目不把大模型直接连接数据库：高频问题优先调用参数化的领域工具；临时分析才进入受控 Text-to-SQL，并经过只读校验、视图白名单、范围限制、行数限制和查询超时控制。

- 有限步 Agent Loop：规划、查询执行、结果检查和回答生成共享同一上下文，最多执行 8 步。
- 混合知识检索：对指标口径、语义视图、异常规则和 SQL 示例使用关键词、TF-IDF、BM25 与术语加分排序。
- 受控数据访问：领域工具使用参数化 SQL；Text-to-SQL 仅允许单语义视图的简单只读查询。
- 证据化异常分析：归因结论基于 GMV、订单、可用桩、离线率和故障数据的同期/环比证据，并明确相关性边界。
- 可观测与可回归：保存安全的 Trace，提供 SSE 工作流事件、版本化评测集和浏览器演示台。
- 权限边界：可选本地 JWT 认证；角色和区域范围在服务端查询层强制校验。

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

## 环境要求

| 软件 | 版本/用途 | 是否必需 |
| --- | --- | --- |
| JDK | 17 | 构建和运行必需 |
| Maven | 3.9+ | 本地构建和运行必需 |
| Docker / Docker Compose | MySQL、Redis 与 SQLBot | 推荐 |
| OpenAI 兼容模型服务 | 结构化计划与受控 SQL 生成 | Agent 分析功能必需 |

## 本地快速运行

在项目根目录创建本地变量文件，并仅在本机填写模型密钥：

```bash
cp .env.example .env
```

启动依赖服务：

```bash
docker compose up -d mysql redis sqlbot
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
| `GET /api/agent/evaluations/cases` | 查看版本化评测案例 |

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

## 技术栈

Java 17、Spring Boot 3.3、Spring AI、Spring JDBC、Spring Security、MySQL 8.4、Redis、JSqlParser、MCP、SSE、Docker Compose。
