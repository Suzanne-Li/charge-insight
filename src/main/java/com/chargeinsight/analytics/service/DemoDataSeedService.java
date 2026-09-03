package com.chargeinsight.analytics.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates deterministic, non-production data for local ChatBI demonstrations. */
@Service
public class DemoDataSeedService {
    private static final List<Region> REGIONS = List.of(
            new Region(1L, "华东", "上海"), new Region(2L, "华东", "杭州"),
            new Region(3L, "华北", "北京"), new Region(4L, "华北", "天津"),
            new Region(5L, "华南", "深圳"), new Region(6L, "华南", "广州"));
    private final JdbcTemplate jdbcTemplate;

    public DemoDataSeedService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public SeedResult initialize(int days) {
        clearAll();
        Random random = new Random(20260826L);
        int groupCount = createDimensions();
        int orderCount = 0;
        int snapshotCount = 0;
        int faultCount = 0;
        LocalDate firstDate = LocalDate.now().minusDays(days - 1L);

        for (int dayIndex = 0; dayIndex < days; dayIndex++) {
            LocalDate statDate = firstDate.plusDays(dayIndex);
            for (long groupId = 101L; groupId < 101L + groupCount; groupId++) {
                DailyMetrics metrics = createDailyMetrics(groupId, dayIndex, days, random);
                writeDailyOperation(statDate, groupId, metrics);
                snapshotCount += writeSnapshots(statDate, groupId, metrics);
                orderCount += writeOrders(statDate, groupId, metrics, random);
                if (metrics.faultPiles > 0) {
                    writeFault(statDate, groupId, metrics, dayIndex);
                    faultCount++;
                }
            }
        }
        return new SeedResult(days, REGIONS.size(), groupCount, groupCount * 10, orderCount, snapshotCount, faultCount);
    }

    private void clearAll() {
        for (String table : List.of("data_import_error", "data_import_batch", "daily_fault_analysis", "daily_group_operation",
                "fault_event", "charging_order", "pile_status_snapshot", "share_config", "charging_pile", "station_group", "dim_region")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    private int createDimensions() {
        for (Region region : REGIONS) {
            jdbcTemplate.update("INSERT INTO dim_region(region_id, region_name, city_name) VALUES (?, ?, ?)",
                    region.id, region.regionName, region.cityName);
        }
        long groupId = 101L;
        for (Region region : REGIONS) {
            for (int sequence = 1; sequence <= 3; sequence++, groupId++) {
                jdbcTemplate.update("INSERT INTO station_group(group_id, group_code, group_name, region_id, operator_name) VALUES (?, ?, ?, ?, ?)",
                        groupId, "CG" + groupId, region.cityName + "私桩共享桩群" + sequence, region.id, "ChargeInsight运营中心");
                for (int pile = 1; pile <= 10; pile++) {
                    long pileId = groupId * 100 + pile;
                    jdbcTemplate.update("INSERT INTO charging_pile(pile_id, pile_code, group_id, vendor, pile_type, install_date) VALUES (?, ?, ?, ?, ?, ?)",
                            pileId, "PILE-" + pileId, groupId, pile % 2 == 0 ? "星云能源" : "云驰科技", "AC_7KW", LocalDate.now().minusDays(400L + pile));
                    jdbcTemplate.update("INSERT INTO share_config(pile_id, owner_id, share_status, effective_at) VALUES (?, ?, 'ENABLED', ?)",
                            pileId, "OWNER-" + pileId, Timestamp.valueOf(LocalDateTime.now().minusDays(365)));
                }
            }
        }
        return (int) (groupId - 101L);
    }

    private DailyMetrics createDailyMetrics(long groupId, int dayIndex, int days, Random random) {
        int total = 10;
        int offline = random.nextInt(2);
        // 华东第一桩群在最近七天出现通信故障，形成可解释的 GMV 与可用桩下降案例。
        boolean anomaly = groupId == 101L && dayIndex >= days - 7;
        if (anomaly) {
            offline = 3;
        }
        int fault = anomaly ? 2 : (random.nextInt(10) < 2 ? 1 : 0);
        int online = total - offline;
        int available = Math.max(0, online - fault);
        int orderCount = Math.max(2, available + random.nextInt(3) - 1);
        int successCount = Math.max(0, orderCount - (random.nextInt(12) == 0 ? 1 : 0));
        return new DailyMetrics(total, 8, online, available, offline, fault, orderCount, successCount);
    }

    private void writeDailyOperation(LocalDate date, long groupId, DailyMetrics metrics) {
        BigDecimal energy = BigDecimal.valueOf(metrics.successOrders * 14.2).setScale(3, RoundingMode.HALF_UP);
        BigDecimal gmv = BigDecimal.valueOf(metrics.successOrders * 24.8).setScale(2, RoundingMode.HALF_UP);
        BigDecimal income = gmv.multiply(BigDecimal.valueOf(0.95)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal fee = gmv.subtract(income);
        jdbcTemplate.update("INSERT INTO daily_group_operation VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                date, groupId, metrics.totalPiles, metrics.sharedPiles, metrics.onlinePiles, metrics.availablePiles, metrics.offlinePiles,
                metrics.faultPiles, metrics.orders, metrics.successOrders, energy, gmv, income, fee);
    }

    private int writeSnapshots(LocalDate date, long groupId, DailyMetrics metrics) {
        for (int pile = 1; pile <= 10; pile++) {
            boolean offline = pile <= metrics.offlinePiles;
            boolean fault = !offline && pile <= metrics.offlinePiles + metrics.faultPiles;
            jdbcTemplate.update("INSERT INTO pile_status_snapshot(snapshot_time, pile_id, online_status, available_status, fault_code) VALUES (?, ?, ?, ?, ?)",
                    Timestamp.valueOf(date.atTime(23, 0)), groupId * 100 + pile, offline ? "OFFLINE" : "ONLINE",
                    offline || fault ? "UNAVAILABLE" : "AVAILABLE", fault ? "COMMUNICATION_TIMEOUT" : null);
        }
        return 10;
    }

    private int writeOrders(LocalDate date, long groupId, DailyMetrics metrics, Random random) {
        for (int index = 1; index <= metrics.orders; index++) {
            boolean success = index <= metrics.successOrders;
            BigDecimal amount = success ? BigDecimal.valueOf(24.8) : BigDecimal.ZERO;
            jdbcTemplate.update("INSERT INTO charging_order(order_id, pile_id, start_time, end_time, energy_kwh, amount, owner_income, platform_fee, order_status, pay_channel, split_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "ORD-" + date + "-" + groupId + "-" + index, groupId * 100 + (1 + random.nextInt(10)),
                    Timestamp.valueOf(date.atTime(8 + index % 10, 0)), Timestamp.valueOf(date.atTime(9 + index % 10, 0)),
                    success ? BigDecimal.valueOf(14.2) : BigDecimal.ZERO, amount,
                    amount.multiply(BigDecimal.valueOf(0.95)).setScale(2, RoundingMode.HALF_UP), amount.multiply(BigDecimal.valueOf(0.05)).setScale(2, RoundingMode.HALF_UP),
                    success ? "SUCCESS" : "CANCELLED", index % 3 == 0 ? "LAKLA" : "FINANCE", success ? "SPLIT_SUCCESS" : null);
        }
        return metrics.orders;
    }

    private void writeFault(LocalDate date, long groupId, DailyMetrics metrics, int dayIndex) {
        String code = groupId == 101L ? "COMMUNICATION_TIMEOUT" : "DEVICE_SELF_CHECK";
        jdbcTemplate.update("INSERT INTO fault_event(fault_id, pile_id, fault_code, fault_type, severity, start_time, recover_time) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "FLT-" + date + "-" + groupId, groupId * 100 + 1, code, groupId == 101L ? "通信故障" : "设备自检故障",
                metrics.offlinePiles > 1 ? "HIGH" : "MEDIUM", Timestamp.valueOf(date.atTime(10, 0)), Timestamp.valueOf(date.atTime(14, 0)));
        jdbcTemplate.update("INSERT INTO daily_fault_analysis VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                date, groupId, "云驰科技", groupId == 101L ? "通信故障" : "设备自检故障", code, metrics.faultPiles,
                metrics.faultPiles * 240L, dayIndex % 3);
    }

    private record Region(long id, String regionName, String cityName) { }
    private record DailyMetrics(int totalPiles, int sharedPiles, int onlinePiles, int availablePiles, int offlinePiles,
                                int faultPiles, int orders, int successOrders) { }
    public record SeedResult(int days, int cityCount, int groupCount, int pileCount, int orderCount, int snapshotCount, int faultCount) { }
}
