# 只读 SELECT 边界
<!-- knowledge-id: governance.read-only-select -->
受控 SQL 仅允许单条无注释 SELECT。INSERT、UPDATE、DELETE、DDL、权限语句、危险导出语句、多语句和参数占位符都必须拒绝。

# AST 与视图校验
<!-- knowledge-id: governance.ast-view-validation -->
JSqlParser 将 SQL 解析为 AST 后提取真实访问表名，再与语义视图白名单比较。安全判断不能只依赖字符串是否包含 SELECT。

# 自动 SQL 结构边界
<!-- knowledge-id: governance.agent-sql-structure -->
自动 Text-to-SQL 只允许单语义视图的简单 SELECT，拒绝 JOIN、UNION、子查询、CTE 和存在性扩展，避免自由 SQL 扩大执行面。

# 区域条件约束
<!-- knowledge-id: governance.agent-sql-region -->
自动 SQL 必须包含服务端计划中的单一 `region_name = '区域'` 条件。WHERE 中出现 OR 会被拒绝，模型不能自行扩大到其他区域。

# 日期条件约束
<!-- knowledge-id: governance.agent-sql-date -->
自动 SQL 必须包含服务端计划中的精确 `stat_date BETWEEN '开始日' AND '结束日'`。日期范围由 Java 解析和校验，不接受模型任意扩展。

# LIMIT 与扫描成本
<!-- knowledge-id: governance.limit-and-explain -->
查询缺少 LIMIT 时服务端补充 LIMIT 100，超过 100 行拒绝。执行前使用 EXPLAIN 检查预估扫描行数，超过阈值时要求缩小范围。

# 一次修复预算
<!-- knowledge-id: governance.one-repair-budget -->
SQL 生成、校验或执行失败时，可将错误上下文交给模型进行一次受控修复。再次失败后必须终止并返回原因，禁止无限重试消耗 Token。

# 结果校验边界
<!-- knowledge-id: governance.result-validation -->
工具或 SQL 返回后，结果检查器验证字段、行值、区域、日期、期末快照及数值边界。结果不满足契约时不得由回答层用自然语言掩盖问题。
