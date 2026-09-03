package com.chargeinsight.agent.metric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Small, explicit metric catalog for the autumn-recruiting scope.
 *
 * <p>This is deliberately not a generic metric platform. It centralizes the business semantics that
 * the planner, tools, result checker and UI must share so the model never invents an aggregation.</p>
 */
@Service
public class MetricCatalogService {
    private final Map<String, MetricDefinition> definitions;
    private final Map<String, String> aliases;

    public MetricCatalogService() {
        List<MetricDefinition> catalog = List.of(
                new MetricDefinition("available_pile_count", "可用桩", List.of("可用桩数"), MetricKind.SNAPSHOT,
                        "PERIOD_END", "个", "统计期最后一个有数据日期在线、可使用且无故障阻塞的充电桩数"),
                new MetricDefinition("avg_available_pile_count", "日均可用桩", List.of(), MetricKind.AVERAGE,
                        "DAILY_AVERAGE", "个", "统计期内每日可用桩数的算术平均值"),
                new MetricDefinition("energy_kwh", "充电量", List.of("电量"), MetricKind.FLOW,
                        "PERIOD_SUM", "kWh", "统计期内成功订单充电量之和"),
                new MetricDefinition("gmv_amount", "GMV", List.of("交易额"), MetricKind.FLOW,
                        "PERIOD_SUM", "元", "统计期内成功充电订单交易金额之和"),
                new MetricDefinition("order_count", "订单量", List.of("订单数"), MetricKind.FLOW,
                        "PERIOD_SUM", "单", "统计期内充电订单数量之和"),
                new MetricDefinition("share_success_rate", "共享成功率", List.of("订单成功率"), MetricKind.RATIO,
                        "RATIO_OF_SUMS", "%", "成功订单量除以订单总量"),
                new MetricDefinition("offline_rate", "离线率", List.of(), MetricKind.RATIO,
                        "RATIO_OF_SUMS", "%", "离线桩日除以总桩日"),
                new MetricDefinition("fault_pile_days", "故障桩日", List.of("故障数"), MetricKind.FLOW,
                        "PERIOD_SUM", "桩日", "按日汇总的故障桩数量之和，不等于去重故障桩数"));
        Map<String, MetricDefinition> byId = new LinkedHashMap<>();
        Map<String, String> byAlias = new LinkedHashMap<>();
        for (MetricDefinition definition : catalog) {
            byId.put(definition.id(), definition);
            byAlias.put(normalize(definition.id()), definition.id());
            byAlias.put(normalize(definition.displayName()), definition.id());
            definition.aliases().forEach(alias -> byAlias.put(normalize(alias), definition.id()));
        }
        this.definitions = Map.copyOf(byId);
        this.aliases = Map.copyOf(byAlias);
    }

    public MetricDefinition require(String idOrAlias) {
        String id = aliases.get(normalize(idOrAlias));
        MetricDefinition definition = id == null ? null : definitions.get(id);
        if (definition == null) {
            throw new IllegalArgumentException("不支持的运营指标：" + idOrAlias);
        }
        return definition;
    }

    public List<MetricDefinition> list() {
        return List.copyOf(definitions.values());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_（）()-]+", "");
    }

    public enum MetricKind { SNAPSHOT, FLOW, AVERAGE, RATIO }

    public record MetricDefinition(String id, String displayName, List<String> aliases, MetricKind kind,
                                   String aggregation, String unit, String description) { }
}
