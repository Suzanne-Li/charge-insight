# 运营语义视图

`v_daily_group_operation` 是桩群日运营视图。维度字段：`stat_date`、`region_name`、`city_name`、`group_id`、`group_name`。指标字段：`total_pile_count`、`available_pile_count`、`offline_pile_count`、`order_count`、`success_order_count`、`energy_kwh`、`gmv_amount`、`offline_rate`、`share_success_rate`。GMV、充电量和订单量在周期内求和；可用桩属于每日快照。

`v_daily_fault_analysis` 是桩群日故障视图。维度字段：`stat_date`、`region_name`、`city_name`、`group_id`、`group_name`、`vendor`、`fault_type`、`fault_code`。指标字段：`fault_pile_count`、`fault_duration_minutes`、`affected_order_count`。`COMMUNICATION_TIMEOUT` 表示通信超时故障。

两个视图通过 `stat_date`、`region_name`、`group_id` 或 `group_name` 关联。查询必须限定时间范围；普通运营人员只能查询 JWT 授权的区域。
