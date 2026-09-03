package com.chargeinsight.agent.sql;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Last-resort SQL execution path. It accepts one simple SELECT over semantic views only, validates
 * its AST, applies a row limit, checks MySQL's estimated rows, and persists an audit trace.
 */
@Service
public class ControlledSqlService {
    private static final int MAX_ROWS = 100;
    private static final long MAX_ESTIMATED_ROWS = 100_000;
    private static final Set<String> ALLOWED_VIEWS = Set.of("v_daily_group_operation", "v_daily_fault_analysis");
    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?is)\\blimit\\s+(\\d+)\\s*$");
    private static final Pattern FORBIDDEN_TOKENS = Pattern.compile("(?is)\\b(insert|update|delete|replace|merge|alter|drop|create|truncate|grant|revoke|call|load|outfile|dumpfile|handler|set|use|show|describe|explain)\\b");
    private final JdbcTemplate jdbcTemplate;

    public ControlledSqlService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SqlValidation validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String normalized = sql.trim();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.contains(";") || normalized.contains("--") || normalized.contains("/*") || normalized.contains("?")) {
            throw new IllegalArgumentException("仅允许不含注释、分号或参数占位符的单条 SELECT");
        }
        if (!normalized.regionMatches(true, 0, "select", 0, "select".length()) || FORBIDDEN_TOKENS.matcher(normalized).find()) {
            throw new IllegalArgumentException("仅允许只读 SELECT 查询");
        }
        Matcher limitMatcher = LIMIT_PATTERN.matcher(normalized);
        boolean hasLimit = limitMatcher.find();
        if (normalized.toLowerCase(Locale.ROOT).matches(".*\\blimit\\b.*") && !hasLimit) {
            throw new IllegalArgumentException("LIMIT 必须位于查询末尾且为正整数");
        }
        if (hasLimit && Integer.parseInt(limitMatcher.group(1)) > MAX_ROWS) {
            throw new IllegalArgumentException("LIMIT 不能超过 " + MAX_ROWS);
        }
        if (!hasLimit) {
            normalized += " LIMIT " + MAX_ROWS;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(normalized);
            if (!(statement instanceof Select)) {
                throw new IllegalArgumentException("仅允许 SELECT AST");
            }
            Set<String> tables = new java.util.LinkedHashSet<>();
            for (Object table : new TablesNamesFinder().getTableList(statement)) {
                tables.add(String.valueOf(table).toLowerCase(Locale.ROOT));
            }
            if (tables.isEmpty() || !ALLOWED_VIEWS.containsAll(tables)) {
                throw new IllegalArgumentException("SQL 只能访问视图：" + String.join("、", ALLOWED_VIEWS));
            }
            return new SqlValidation(normalized, List.copyOf(tables), MAX_ROWS);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("SQL AST 解析失败");
        }
    }

    /**
     * Stricter validation used by the automatic Agent fallback. Unlike the explicit ADMIN SQL
     * endpoint, the generated statement must be a single-table query whose region and date range
     * are fixed by server-side planning, so model output cannot broaden the user's data scope.
     */
    public SqlValidation validateAgentFallback(String sql, String region, LocalDate startDate, LocalDate endDate) {
        SqlValidation validation = validate(sql);
        try {
            Select select = (Select) CCJSqlParserUtil.parse(validation.normalizedSql());
            PlainSelect plainSelect = select.getPlainSelect();
            if (plainSelect == null || !(plainSelect.getFromItem() instanceof Table)
                    || (plainSelect.getJoins() != null && !plainSelect.getJoins().isEmpty())
                    || select.getWithItemsList() != null) {
                throw new IllegalArgumentException("自动 SQL 仅允许单语义视图的简单查询");
            }
            String where = plainSelect.getWhere() == null ? "" : plainSelect.getWhere().toString();
            if (Pattern.compile("(?i)\\bor\\b|\\bunion\\b|\\bexists\\b").matcher(where).find()) {
                throw new IllegalArgumentException("自动 SQL 的筛选条件仅允许 AND 组合");
            }
            String escapedRegion = region.replace("'", "''");
            Pattern regionPredicate = Pattern.compile("(?i)(?:\\w+\\.)?region_name\\s*=\\s*'"
                    + Pattern.quote(escapedRegion) + "'");
            Pattern datePredicate = Pattern.compile("(?i)(?:\\w+\\.)?stat_date\\s+between\\s+'"
                    + Pattern.quote(startDate.toString()) + "'\\s+and\\s+'" + Pattern.quote(endDate.toString()) + "'");
            if (!regionPredicate.matcher(where).find() || !datePredicate.matcher(where).find()) {
                throw new IllegalArgumentException("自动 SQL 必须包含服务端限定的区域与日期范围");
            }
            return validation;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("自动 SQL AST 校验失败");
        }
    }

    public SqlExecution execute(String sql) {
        SqlValidation validation = validate(sql);
        String traceId = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO analytics_agent_trace(trace_id, question, status, started_at) VALUES (?, ?, 'RUNNING', NOW())",
                traceId, "CONTROLLED_SQL_EXECUTION");
        try {
            long estimatedRows = estimatedRows(validation.normalizedSql());
            if (estimatedRows > MAX_ESTIMATED_ROWS) {
                throw new IllegalArgumentException("查询预估扫描行数超过 " + MAX_ESTIMATED_ROWS + "，请缩小范围");
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(validation.normalizedSql());
            jdbcTemplate.update("UPDATE analytics_agent_trace SET status='SUCCESS', completed_at=NOW() WHERE trace_id=?", traceId);
            saveStep(traceId, "CONTROLLED_SQL", "views=" + validation.views() + ", estimatedRows=" + estimatedRows + ", returnedRows=" + rows.size());
            return new SqlExecution(traceId, validation.normalizedSql(), validation.views(), estimatedRows, rows);
        } catch (RuntimeException exception) {
            jdbcTemplate.update("UPDATE analytics_agent_trace SET status='FAILED', completed_at=NOW() WHERE trace_id=?", traceId);
            saveStep(traceId, "CONTROLLED_SQL_FAILED", "受控 SQL 执行失败");
            throw exception;
        }
    }

    public SqlExecution executeAgentFallback(String sql, String region, LocalDate startDate, LocalDate endDate) {
        SqlValidation validation = validateAgentFallback(sql, region, startDate, endDate);
        long estimatedRows = estimatedRows(validation.normalizedSql());
        if (estimatedRows > MAX_ESTIMATED_ROWS) {
            throw new IllegalArgumentException("查询预估扫描行数超过 " + MAX_ESTIMATED_ROWS + "，请缩小范围");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(validation.normalizedSql());
        return new SqlExecution(null, validation.normalizedSql(), validation.views(), estimatedRows, rows);
    }

    private long estimatedRows(String sql) {
        return jdbcTemplate.queryForList("EXPLAIN " + sql).stream()
                .mapToLong(row -> ((Number) row.getOrDefault("rows", 0)).longValue())
                .sum();
    }

    private void saveStep(String traceId, String type, String summary) {
        jdbcTemplate.update("INSERT INTO analytics_agent_trace_step(trace_id, step_number, step_type, summary) VALUES (?, 1, ?, ?)",
                traceId, type, summary);
    }

    public record SqlValidation(String normalizedSql, List<String> views, int maxRows) { }
    public record SqlExecution(String traceId, String normalizedSql, List<String> views, long estimatedRows,
                               List<Map<String, Object>> rows) { }
}
