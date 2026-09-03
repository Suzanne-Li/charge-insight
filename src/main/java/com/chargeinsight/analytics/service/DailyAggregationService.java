package com.chargeinsight.analytics.service;

import java.sql.Date;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyAggregationService {
    private final JdbcTemplate jdbcTemplate;

    public DailyAggregationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public AggregationResult rebuild(LocalDate date) {
        Date sqlDate = Date.valueOf(date);
        jdbcTemplate.update("DELETE FROM daily_fault_analysis WHERE stat_date=?", sqlDate);
        jdbcTemplate.update("DELETE FROM daily_group_operation WHERE stat_date=?", sqlDate);
        int operations = jdbcTemplate.update("""
                INSERT INTO daily_group_operation (stat_date, group_id, total_pile_count, shared_pile_count, online_pile_count, available_pile_count, offline_pile_count, fault_pile_count, order_count, success_order_count, energy_kwh, gmv_amount, owner_income, platform_fee)
                SELECT ?, g.group_id, COUNT(p.pile_id),
                       SUM(CASE WHEN s.pile_id IS NOT NULL THEN 1 ELSE 0 END),
                       SUM(CASE WHEN ps.online_status = 'ONLINE' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN ps.available_status = 'AVAILABLE' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN ps.online_status = 'OFFLINE' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN ps.fault_code IS NOT NULL THEN 1 ELSE 0 END),
                       COALESCE(o.order_count, 0), COALESCE(o.success_order_count, 0), COALESCE(o.energy_kwh, 0), COALESCE(o.gmv_amount, 0), COALESCE(o.owner_income, 0), COALESCE(o.platform_fee, 0)
                FROM station_group g JOIN charging_pile p ON p.group_id=g.group_id
                LEFT JOIN share_config s ON s.pile_id=p.pile_id AND s.share_status='ENABLED' AND s.effective_at < DATE_ADD(?, INTERVAL 1 DAY) AND (s.expired_at IS NULL OR s.expired_at >= ?)
                LEFT JOIN pile_status_snapshot ps ON ps.pile_id=p.pile_id AND DATE(ps.snapshot_time)=?
                LEFT JOIN (SELECT cp.group_id, COUNT(*) order_count, SUM(CASE WHEN co.order_status='SUCCESS' THEN 1 ELSE 0 END) success_order_count, SUM(CASE WHEN co.order_status='SUCCESS' THEN co.energy_kwh ELSE 0 END) energy_kwh, SUM(CASE WHEN co.order_status='SUCCESS' THEN co.amount ELSE 0 END) gmv_amount, SUM(CASE WHEN co.order_status='SUCCESS' THEN co.owner_income ELSE 0 END) owner_income, SUM(CASE WHEN co.order_status='SUCCESS' THEN co.platform_fee ELSE 0 END) platform_fee FROM charging_order co JOIN charging_pile cp ON cp.pile_id=co.pile_id WHERE DATE(co.start_time)=? GROUP BY cp.group_id) o ON o.group_id=g.group_id
                GROUP BY g.group_id, o.order_count, o.success_order_count, o.energy_kwh, o.gmv_amount, o.owner_income, o.platform_fee
                """, sqlDate, sqlDate, sqlDate, sqlDate, sqlDate);
        int faults = jdbcTemplate.update("""
                INSERT INTO daily_fault_analysis (stat_date, group_id, vendor, fault_type, fault_code, fault_pile_count, fault_duration_minutes, affected_order_count)
                SELECT ?, cp.group_id, cp.vendor, fe.fault_type, fe.fault_code, COUNT(DISTINCT fe.pile_id),
                       SUM(TIMESTAMPDIFF(MINUTE, fe.start_time, COALESCE(fe.recover_time, NOW()))), 0
                FROM fault_event fe JOIN charging_pile cp ON cp.pile_id=fe.pile_id
                WHERE DATE(fe.start_time)=? GROUP BY cp.group_id, cp.vendor, fe.fault_type, fe.fault_code
                """, sqlDate, sqlDate);
        return new AggregationResult(date, operations, faults);
    }

    public record AggregationResult(LocalDate statDate, int groupOperationRows, int faultAnalysisRows) { }
}
