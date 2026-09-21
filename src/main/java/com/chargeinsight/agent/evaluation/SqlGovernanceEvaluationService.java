package com.chargeinsight.agent.evaluation;

import com.chargeinsight.agent.sql.ControlledSqlService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Deterministic security regression suite for the SQL validation boundary. */
@Service
public class SqlGovernanceEvaluationService {
    private static final String CASES_RESOURCE = "agent-evaluation/sql-governance-cases.json";
    private final ControlledSqlService sqlService;
    private final List<SqlCase> cases;

    public SqlGovernanceEvaluationService(ControlledSqlService sqlService, ObjectMapper objectMapper) {
        this.sqlService = sqlService;
        this.cases = loadCases(objectMapper);
    }

    public SqlGovernanceReport run() {
        List<SqlCaseResult> results = new ArrayList<>();
        for (SqlCase sqlCase : cases) {
            boolean accepted;
            try {
                sqlService.validate(sqlCase.sql());
                accepted = true;
            } catch (IllegalArgumentException ignored) {
                accepted = false;
            }
            results.add(new SqlCaseResult(sqlCase.id(), sqlCase.expectedAccepted() == accepted, accepted));
        }
        long allowed = cases.stream().filter(SqlCase::expectedAccepted).count();
        long blocked = cases.size() - allowed;
        long allowedAccepted = results.stream().filter(result -> result.accepted() && caseById(result.id()).expectedAccepted()).count();
        long blockedRejected = results.stream().filter(result -> !result.accepted() && !caseById(result.id()).expectedAccepted()).count();
        return new SqlGovernanceReport(cases.size(), allowed, blocked,
                rate(allowedAccepted, allowed), rate(blockedRejected, blocked),
                results.stream().filter(SqlCaseResult::passed).count(), List.copyOf(results));
    }

    public List<SqlCase> cases() {
        return cases;
    }

    private SqlCase caseById(String id) {
        return cases.stream().filter(sqlCase -> sqlCase.id().equals(id)).findFirst().orElseThrow();
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 1 : (double) numerator / denominator;
    }

    private List<SqlCase> loadCases(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource(CASES_RESOURCE).getInputStream()) {
            List<SqlCase> loaded = objectMapper.readValue(input, new TypeReference<>() { });
            if (loaded.isEmpty()) throw new IllegalStateException("SQL 治理评测集不能为空");
            return List.copyOf(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载评测集：" + CASES_RESOURCE, exception);
        }
    }

    public record SqlCase(String id, String category, String sql, boolean expectedAccepted) { }
    public record SqlCaseResult(String id, boolean passed, boolean accepted) { }
    public record SqlGovernanceReport(int total, long expectedAllowed, long expectedBlocked,
                                      double validSqlAcceptRate, double unsafeSqlBlockRate,
                                      long passed, List<SqlCaseResult> cases) { }
}
