# 充换电运营指标口径

可用桩：`available_pile_count`，在线、可使用且没有故障阻塞的充电桩数，属于存量指标。用户查询一个周期的“可用桩数”时，默认返回统计期最后一个有数据日期的快照值；需要平均值时必须明确称为“日均可用桩”。

日均可用桩：统计期内每天 `available_pile_count` 的算术平均值，只作为周期运营效率的辅助指标，不能替代用户询问的“可用桩数”。

离线率：`offline_pile_count / total_pile_count`。离线率升高会减少可用桩，可能影响订单量与 GMV。

共享成功率：`success_order_count / order_count`，无订单时按 0 处理。

GMV：`gmv_amount`，成功充电订单交易金额，属于流量指标；周期问题按天求和，单位元。

充电量：`energy_kwh`，成功订单电量，属于流量指标；周期问题按天求和，单位 kWh。

桩主收入：`owner_income`，共享订单分账给桩主的收益。

平台服务费：`platform_fee`，平台分账手续费。
