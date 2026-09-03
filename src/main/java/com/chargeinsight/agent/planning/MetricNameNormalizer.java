package com.chargeinsight.agent.planning;

import java.util.Locale;
import java.util.Map;

/** Converts common planner vocabulary to the stable metric names used by tools and evaluations. */
public final class MetricNameNormalizer {
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("gmv", "gmv_amount"), Map.entry("交易额", "gmv_amount"), Map.entry("交易金额", "gmv_amount"),
            Map.entry("可用桩", "available_pile_count"), Map.entry("可用桩数", "available_pile_count"),
            Map.entry("离线桩", "offline_pile_count"), Map.entry("离线桩数", "offline_pile_count"),
            Map.entry("离线率", "offline_rate"), Map.entry("订单量", "order_count"),
            Map.entry("成功订单数", "success_order_count"), Map.entry("共享成功率", "share_success_rate"),
            Map.entry("充电量", "energy_kwh"), Map.entry("通信故障", "communication_timeout"),
            Map.entry("communication_timeout", "communication_timeout"));

    private MetricNameNormalizer() { }

    public static String normalize(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return ALIASES.getOrDefault(normalized, normalized);
    }
}
