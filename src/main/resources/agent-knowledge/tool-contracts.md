# 运营总览工具
<!-- knowledge-id: tool.operation-overview -->
`queryOperationOverview` 适用于区域运营总览，输入为区域与日期范围，返回期末可用桩、日均可用桩、累计充电量、GMV、订单量、离线率和共享成功率。所有值由参数化查询生成。

# 桩群趋势工具
<!-- knowledge-id: tool.operation-trend -->
`queryOperationTrend` 适用于指定桩群的 GMV、充电量、订单量、可用桩或离线率时间趋势。输入必须包含区域、唯一桩群、受控指标和日期范围。

# 桩群排行工具
<!-- knowledge-id: tool.group-ranking -->
`rankGroups` 适用于 GMV 最低或离线率最高等固定排行问题。返回行数受限，排序指标来自枚举白名单，不允许模型直接传递排序 SQL 片段。

# 故障排行工具
<!-- knowledge-id: tool.fault-ranking -->
`rankFaultGroups` 适用于区域故障或通信超时故障的桩群排行。通信超时使用固定 `COMMUNICATION_TIMEOUT` 故障编码，返回故障桩日、累计时长和影响订单日计数。

# 故障明细工具
<!-- knowledge-id: tool.fault-breakdown -->
`queryFaultBreakdown` 适用于指定桩群的故障类型明细。它按故障类型和编码汇总，不返回原始故障事件，也不将日汇总字段伪装为去重明细。

# 异常证据对比工具
<!-- knowledge-id: tool.anomaly-comparison -->
`compareAnomalyEvidence` 适用于经营波动归因的本期/上期对比，返回两期运营概览及当前期故障汇总。它提供相关性分析证据，不提供因果证明。

# 受控动态查询工具
<!-- knowledge-id: tool.controlled-text-to-sql -->
`controlledTextToSql` 仅处理固定领域工具未覆盖的临时维度、筛选或聚合。它必须在 Schema 与 SQL 示例检索后经 AST、区域、日期、LIMIT、EXPLAIN 和超时治理执行。
