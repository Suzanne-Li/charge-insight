package com.chargeinsight.agent.sql;

import com.chargeinsight.security.RegionAccessPolicy;
import java.time.temporal.ChronoUnit;
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
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private static final int MAX_SCOPE_DAYS = 31;
    private static final Set<String> ALLOWED_VIEWS = Set.of("v_daily_group_operation", "v_daily_fault_analysis");
    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?is)\\blimit\\s+(\\d+)\\s*$");
    private static final Pattern FORBIDDEN_TOKENS = Pattern.compile("(?is)\\b(insert|update|delete|replace|merge|alter|drop|create|truncate|grant|revoke|call|load|outfile|dumpfile|handler|set|use|show|describe|explain)\\b");
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<ControlledSqlReadClient> readClient;
    private final RegionAccessPolicy regionAccessPolicy;

    public ControlledSqlService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null, new RegionAccessPolicy());
    }

    @Autowired
    public ControlledSqlService(JdbcTemplate jdbcTemplate,
            @Qualifier("controlledSqlReadClient") ObjectProvider<ControlledSqlReadClient> readClient,
            RegionAccessPolicy regionAccessPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.readClient = readClient;
        this.regionAccessPolicy = regionAccessPolicy;
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
     * Validates the only SQL shape that either the automatic Agent fallback or the explicit
     * administrator endpoint may execute. Scope comes from the server-side request contract,
     * is checked against the caller's region claims here at the data boundary, and must appear
     * exactly in the parsed WHERE expression.
     */
    public SqlValidation validateScoped(String sql, String region, LocalDate startDate, LocalDate endDate) {
        String safeRegion = requiredRegion(region);
        validatePeriod(startDate, endDate);
        regionAccessPolicy.assertAllowed(safeRegion);
        SqlValidation validation = validate(sql);
        try {
            Select select = (Select) CCJSqlParserUtil.parse(validation.normalizedSql());
            PlainSelect plainSelect = select.getPlainSelect();
            if (plainSelect == null || !(plainSelect.getFromItem() instanceof Table)
                    || (plainSelect.getJoins() != null && !plainSelect.getJoins().isEmpty())
                    || select.getWithItemsList() != null || validation.views().size() != 1) {
                throw new IllegalArgumentException("受控 SQL 仅允许单语义视图的简单查询");
            }
            String normalized = validation.normalizedSql();
            if (countKeyword(normalized, "select") != 1
                    || Pattern.compile("(?i)\\b(union|exists|or)\\b").matcher(normalized).find()) {
                throw new IllegalArgumentException("受控 SQL 仅允许 AND 组合的简单筛选条件");
            }
            ScopePredicates predicates = collectScopePredicates(plainSelect.getWhere(), safeRegion, startDate, endDate);
            if (!predicates.hasRegion() || !predicates.hasDateRange()) {
                throw new IllegalArgumentException("受控 SQL 必须包含服务端限定的区域与日期范围");
            }
            return validation;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("受控 SQL AST 范围校验失败");
        }
    }

    /**
     * Compatibility entry point for the automatic Agent fallback. Explicit execution uses the
     * same scope policy; no SQL path may bypass the region/date boundary.
     */
    public SqlValidation validateAgentFallback(String sql, String region, LocalDate startDate, LocalDate endDate) {
        return validateScoped(sql, region, startDate, endDate);
    }

    public SqlExecution executeScoped(String sql, String region, LocalDate startDate, LocalDate endDate) {
        SqlValidation validation = validateScoped(sql, region, startDate, endDate);
        // Reject before creating a run record when no SELECT-only execution identity is available.
        readJdbc();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO analytics_agent_trace(trace_id, question, status, started_at) VALUES (?, ?, 'RUNNING', NOW())",
                traceId, "CONTROLLED_SQL_EXECUTION region=" + region + ", dates=" + startDate + "~" + endDate);
        String executionPhase = "EXPLAIN";
        try {
            long estimatedRows = estimatedRows(jdbcTemplate, validation.normalizedSql());
            if (estimatedRows > MAX_ESTIMATED_ROWS) {
                throw new IllegalArgumentException("查询预估扫描行数超过 " + MAX_ESTIMATED_ROWS + "，请缩小范围");
            }
            executionPhase = "READ";
            List<Map<String, Object>> rows = readJdbc().queryForList(validation.normalizedSql());
            executionPhase = "AUDIT";
            jdbcTemplate.update("UPDATE analytics_agent_trace SET status='SUCCESS', completed_at=NOW() WHERE trace_id=?", traceId);
            saveStep(traceId, "CONTROLLED_SQL", "views=" + validation.views() + ", estimatedRows=" + estimatedRows + ", returnedRows=" + rows.size());
            return new SqlExecution(traceId, validation.normalizedSql(), validation.views(), estimatedRows, rows);
        } catch (RuntimeException exception) {
            jdbcTemplate.update("UPDATE analytics_agent_trace SET status='FAILED', completed_at=NOW() WHERE trace_id=?", traceId);
            saveStep(traceId, "CONTROLLED_SQL_FAILED", "phase=" + executionPhase);
            throw new ControlledSqlExecutionException(executionPhase, exception);
        }
    }

    public SqlExecution executeAgentFallback(String sql, String region, LocalDate startDate, LocalDate endDate) {
        SqlValidation validation = validateScoped(sql, region, startDate, endDate);
        long estimatedRows = estimatedRows(jdbcTemplate, validation.normalizedSql());
        if (estimatedRows > MAX_ESTIMATED_ROWS) {
            throw new IllegalArgumentException("查询预估扫描行数超过 " + MAX_ESTIMATED_ROWS + "，请缩小范围");
        }
        List<Map<String, Object>> rows = readJdbc().queryForList(validation.normalizedSql());
        return new SqlExecution(null, validation.normalizedSql(), validation.views(), estimatedRows, rows);
    }

    private String requiredRegion(String region) {
        if (region == null || region.isBlank()) throw new IllegalArgumentException("受控 SQL 必须指定区域范围");
        return region.trim();
    }

    private void validatePeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) throw new IllegalArgumentException("受控 SQL 必须指定开始和结束日期");
        if (endDate.isBefore(startDate)) throw new IllegalArgumentException("受控 SQL 结束日期不能早于开始日期");
        if (ChronoUnit.DAYS.between(startDate, endDate) + 1 > MAX_SCOPE_DAYS) {
            throw new IllegalArgumentException("受控 SQL 日期范围不能超过 " + MAX_SCOPE_DAYS + " 天");
        }
    }

    private ScopePredicates collectScopePredicates(Expression expression, String region, LocalDate startDate, LocalDate endDate) {
        if (expression == null) return ScopePredicates.none();
        if (expression instanceof AndExpression and) {
            return collectScopePredicates(and.getLeftExpression(), region, startDate, endDate)
                    .merge(collectScopePredicates(and.getRightExpression(), region, startDate, endDate));
        }
        if (expression instanceof OrExpression) {
            throw new IllegalArgumentException("受控 SQL 的筛选条件不允许 OR");
        }
        if (expression instanceof EqualsTo equals && isColumn(equals.getLeftExpression(), "region_name")
                && region.equals(stringLiteral(equals.getRightExpression()))) {
            return ScopePredicates.region();
        }
        if (expression instanceof EqualsTo equals && isColumn(equals.getRightExpression(), "region_name")
                && region.equals(stringLiteral(equals.getLeftExpression()))) {
            return ScopePredicates.region();
        }
        if (expression instanceof Between between && isColumn(between.getLeftExpression(), "stat_date")
                && startDate.toString().equals(stringLiteral(between.getBetweenExpressionStart()))
                && endDate.toString().equals(stringLiteral(between.getBetweenExpressionEnd()))) {
            return ScopePredicates.dateRange();
        }
        return ScopePredicates.none();
    }

    private boolean isColumn(Expression expression, String expectedColumn) {
        return expression instanceof net.sf.jsqlparser.schema.Column column
                && expectedColumn.equalsIgnoreCase(column.getColumnName());
    }

    private String stringLiteral(Expression expression) {
        String rendered = expression == null ? "" : expression.toString().trim();
        if (rendered.length() < 2 || !rendered.startsWith("'") || !rendered.endsWith("'")) return "";
        return rendered.substring(1, rendered.length() - 1).replace("''", "'");
    }

    private int countKeyword(String value, String keyword) {
        Matcher matcher = Pattern.compile("(?i)\\b" + Pattern.quote(keyword) + "\\b").matcher(value);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    /**
     * EXPLAIN has no data-reading side effect after AST validation. It runs on the primary
     * application connection because MySQL otherwise requires the reader to have SELECT on every
     * base table behind a view; actual result rows still always use the dedicated reader identity.
     */
    static long estimatedRows(JdbcTemplate explainJdbcTemplate, String sql) {
        return explainJdbcTemplate.queryForList("EXPLAIN " + sql).stream()
                .mapToLong(row -> ((Number) row.getOrDefault("rows", 0)).longValue())
                .sum();
    }

    private JdbcTemplate readJdbc() {
        ControlledSqlReadClient client = readClient == null ? null : readClient.getIfAvailable();
        JdbcTemplate template = client == null ? null : client.jdbcTemplate();
        if (template == null) throw new IllegalStateException("受控 SQL 执行需要启用独立只读数据源");
        return template;
    }

    /** Safe diagnostics only: never returns connection URLs, usernames, credentials, or driver errors. */
    public ReadOnlyDatasourceStatus readOnlyDatasourceStatus() {
        ControlledSqlReadClient client = readClient == null ? null : readClient.getIfAvailable();
        JdbcTemplate template = client == null ? null : client.jdbcTemplate();
        if (template == null) return new ReadOnlyDatasourceStatus("NOT_CONFIGURED");
        try {
            Integer value = template.queryForObject("SELECT 1", Integer.class);
            return Integer.valueOf(1).equals(value) ? new ReadOnlyDatasourceStatus("READY")
                    : new ReadOnlyDatasourceStatus("UNAVAILABLE");
        } catch (RuntimeException exception) {
            return new ReadOnlyDatasourceStatus("UNAVAILABLE");
        }
    }

    private void saveStep(String traceId, String type, String summary) {
        jdbcTemplate.update("INSERT INTO analytics_agent_trace_step(trace_id, step_number, step_type, summary) VALUES (?, 1, ?, ?)",
                traceId, type, summary);
    }

    public record SqlValidation(String normalizedSql, List<String> views, int maxRows) { }
    public record SqlExecution(String traceId, String normalizedSql, List<String> views, long estimatedRows,
                               List<Map<String, Object>> rows) { }
    public record ReadOnlyDatasourceStatus(String state) { }
    private record ScopePredicates(boolean hasRegion, boolean hasDateRange) {
        static ScopePredicates none() { return new ScopePredicates(false, false); }
        static ScopePredicates region() { return new ScopePredicates(true, false); }
        static ScopePredicates dateRange() { return new ScopePredicates(false, true); }
        ScopePredicates merge(ScopePredicates other) {
            return new ScopePredicates(hasRegion || other.hasRegion, hasDateRange || other.hasDateRange);
        }
    }

    /** Publicly safe failure category; the database exception remains server-side only. */
    public static final class ControlledSqlExecutionException extends RuntimeException {
        private final String phase;

        ControlledSqlExecutionException(String phase, RuntimeException cause) {
            super("controlled SQL execution failed", cause);
            this.phase = phase;
        }

        public String phase() {
            return phase;
        }
    }
}
