# 期末可用桩
<!-- knowledge-id: metric.catalog.available-pile-count -->
`available_pile_count` 是在线、可使用且没有故障阻塞的充电桩数。它属于存量指标，周期问题默认取统计期最后一个有数据日期的快照，单位为个。

# 日均可用桩
<!-- knowledge-id: metric.catalog.average-available-pile-count -->
`avg_available_pile_count` 是统计期内每日可用桩数的算术平均值。它只能解释周期运营效率，不能代替用户直接询问的期末可用桩数。

# 累计充电量
<!-- knowledge-id: metric.catalog.energy-kwh -->
`energy_kwh` 是统计期内成功订单充电量之和，属于流量指标，单位为 kWh。别名为电量，周期问题按天求和。

# 累计 GMV
<!-- knowledge-id: metric.catalog.gmv-amount -->
`gmv_amount` 是统计期内成功充电订单交易金额之和，属于流量指标，单位为元。别名为交易额，不能用单日值替代周期累计。

# 累计订单量
<!-- knowledge-id: metric.catalog.order-count -->
`order_count` 是统计期内充电订单数量之和，属于流量指标，单位为单。别名为订单数，可与 GMV、充电量的同期变化共同用于经营波动核验。

# 共享成功率
<!-- knowledge-id: metric.catalog.share-success-rate -->
`share_success_rate` 等于成功订单量除以订单总量，属于比例指标，单位为百分比。应按同期分子和分母汇总后计算，无订单时按 0 处理。

# 离线率
<!-- knowledge-id: metric.catalog.offline-rate -->
`offline_rate` 等于离线桩日除以总桩日，属于比例指标，单位为百分比。不能将每日离线率直接相加，也不能将其解释为唯一离线设备数。

# 故障桩日
<!-- knowledge-id: metric.catalog.fault-pile-days -->
`fault_pile_days` 是按日汇总的故障桩数量之和，单位为桩日。它不等于去重故障桩数、自然日覆盖数或唯一故障事件数。
