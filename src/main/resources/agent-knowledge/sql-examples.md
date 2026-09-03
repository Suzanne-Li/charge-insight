# 区域周期运营总览示例

问题：华东区域最近一周可用桩、充电量和 GMV 是多少？

口径：先按天汇总区域数据；可用桩取最后一个有数据日期的快照，日均可用桩作为辅助值；充电量、GMV 和订单量按周期累计。优先调用 `queryOperationOverview`，不需要生成自由 SQL。

# 通信故障桩群排行示例

问题：华东区域哪些桩群出现较多通信故障？

口径：查询 `v_daily_fault_analysis`，限定 `region_name`、时间范围和 `fault_code = 'COMMUNICATION_TIMEOUT'`，按 `group_id, group_name` 汇总 `fault_pile_count` 和 `fault_duration_minutes`，优先调用 `rankFaultGroups`。

# GMV 异常分析示例

问题：为什么华东上海私桩共享桩群1最近一周 GMV 较低？

分析步骤：比较本期与上期 GMV；比较期末可用桩、订单量、成功率和离线率；查询同期故障类型和持续时间。日汇总数据只能支持相关性判断，不能直接证明因果关系。
