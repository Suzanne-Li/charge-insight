# 运营视图时间与区域字段
<!-- knowledge-id: schema.operation.dimensions-time-region -->
`v_daily_group_operation` 的时间与区域字段为 `stat_date`、`region_name`、`city_name`。动态查询必须用 `region_name` 和 `stat_date` 限定服务端已验证的范围。

# 运营视图桩群字段
<!-- knowledge-id: schema.operation.dimensions-group -->
`v_daily_group_operation` 的桩群维度为 `group_id`、`group_name`。桩群趋势和故障明细必须同时受区域与唯一桩群实体约束。

# 运营视图设备状态字段
<!-- knowledge-id: schema.operation.device-state-fields -->
`total_pile_count`、`available_pile_count`、`offline_pile_count` 是桩群日状态字段。区域周期查询应先按天汇总，再按指标语义取期末快照或日均值。

# 运营视图订单字段
<!-- knowledge-id: schema.operation.order-fields -->
`order_count` 是订单总量，`success_order_count` 是成功订单量。共享成功率按同期两者汇总后的比值计算，不能平均每日比例。

# 运营视图经营字段
<!-- knowledge-id: schema.operation.business-fields -->
`energy_kwh` 和 `gmv_amount` 是成功订单的经营流量字段，周期问题按天求和。GMV 与充电量可共同解释经营变化，但不构成因果证明。

# 运营视图比例字段
<!-- knowledge-id: schema.operation.ratio-fields -->
`offline_rate` 与 `share_success_rate` 是比例字段。排行时应使用底层分子分母聚合后的结果，不能对不同桩群的日比例直接求和。

# 故障视图时间范围字段
<!-- knowledge-id: schema.fault.dimensions-time-region -->
`v_daily_fault_analysis` 使用 `stat_date`、`region_name`、`city_name` 记录桩群日故障。所有故障分析都必须限定区域和日期范围。

# 故障视图桩群与厂商字段
<!-- knowledge-id: schema.fault.dimensions-group-vendor -->
`v_daily_fault_analysis` 的实体字段为 `group_id`、`group_name`、`vendor`。厂商字段当前不是固定领域工具的自由筛选维度，临时组合必须走受控 SQL 兜底。

# 故障类型与编码字段
<!-- knowledge-id: schema.fault.type-code-fields -->
`fault_type` 和 `fault_code` 用于归类故障。`COMMUNICATION_TIMEOUT` 表示通信超时，可用于受控的通信故障排行和故障影响分析。

# 故障影响字段
<!-- knowledge-id: schema.fault.impact-fields -->
`fault_pile_count`、`fault_duration_minutes`、`affected_order_count` 为日汇总故障影响字段。跨天后应分别表述为故障桩日、累计故障时长和影响订单日计数。

# 视图关联条件
<!-- knowledge-id: schema.cross-view.join-keys -->
两个语义视图可按 `stat_date`、`region_name`、`group_id` 或 `group_name` 关联。自动 Text-to-SQL 禁止 JOIN、子查询和 CTE，因此复杂关联不属于当前兜底范围。

# 语义视图白名单
<!-- knowledge-id: schema.allowed-semantic-views -->
受控 SQL 只能访问 `v_daily_group_operation` 与 `v_daily_fault_analysis`。基础表不在 Agent 查询面内，模型不能通过表名猜测扩大数据访问。

# 期末快照检查
<!-- knowledge-id: schema.snapshot-validation -->
期末可用桩等存量指标需要数据集中的 `snapshotDate`。结果检查发现期末快照缺失时必须拒绝生成经营结论。

# Dataset 必需字段
<!-- knowledge-id: schema.dataset-contract -->
统一 Dataset 根据意图要求必需字段：总览含快照、可用桩、充电量和 GMV，趋势含日期与指标值，排行和故障分析各有固定字段契约。
