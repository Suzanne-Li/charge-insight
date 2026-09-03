package com.chargeinsight.agent.tool;

import com.chargeinsight.security.RegionAccessPolicy;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolves a planner's group phrase to one concrete, auditable analytics entity. */
@Component
public class GroupEntityResolver {
    private final JdbcTemplate jdbcTemplate;
    private final AnalyticsQueryPolicy policy;
    private final RegionAccessPolicy regionAccessPolicy;

    public GroupEntityResolver(JdbcTemplate jdbcTemplate, AnalyticsQueryPolicy policy, RegionAccessPolicy regionAccessPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.policy = policy;
        this.regionAccessPolicy = regionAccessPolicy;
    }

    public ResolvedGroup resolve(String region, String city, String requestedGroup) {
        NormalizedScope scope = normalize(region, city);
        String safeGroup = policy.requiredScope(requestedGroup, "group");
        String groupWithoutRegion = stripPrefix(safeGroup, scope.region());
        scope = inferCityFromGroup(scope, groupWithoutRegion);
        String lookupGroup = stripPrefix(groupWithoutRegion, scope.city());
        List<GroupCandidate> exactMatches = find(scope, "group_name = ?", lookupGroup);
        if (exactMatches.size() == 1) {
            return new ResolvedGroup(scope.region(), scope.city(), safeGroup, exactMatches.get(0), ResolutionMode.EXACT);
        }
        if (exactMatches.size() > 1) {
            throw ScopeResolutionException.ambiguous(scope, safeGroup, exactMatches);
        }
        List<GroupCandidate> fuzzyMatches = find(scope, "group_name LIKE ?", "%" + lookupGroup + "%");
        if (fuzzyMatches.size() == 1) {
            return new ResolvedGroup(scope.region(), scope.city(), safeGroup, fuzzyMatches.get(0), ResolutionMode.UNIQUE_FUZZY);
        }
        if (fuzzyMatches.isEmpty()) {
            throw ScopeResolutionException.notFound(scope, safeGroup);
        }
        throw ScopeResolutionException.ambiguous(scope, safeGroup, fuzzyMatches);
    }

    private NormalizedScope normalize(String region, String city) {
        String safeRegion = policy.requiredScope(region, "region");
        regionAccessPolicy.assertAllowed(safeRegion);
        if (city != null && !city.isBlank()) return new NormalizedScope(safeRegion, city.trim());
        List<String> regions = jdbcTemplate.queryForList("SELECT DISTINCT region_name FROM v_daily_group_operation", String.class);
        return regions.stream().filter(safeRegion::startsWith).max(Comparator.comparingInt(String::length))
                .map(match -> new NormalizedScope(match, safeRegion.substring(match.length()).trim()))
                .filter(scope -> !scope.city().isEmpty()).orElse(new NormalizedScope(safeRegion, null));
    }

    /** Some planner outputs retain the city in group while also filling scope.city; query by the canonical group name. */
    private String stripPrefix(String group, String prefix) {
        if (prefix == null || prefix.isBlank() || !group.startsWith(prefix)) return group;
        String stripped = group.substring(prefix.length()).trim();
        return stripped.isEmpty() ? group : stripped;
    }

    private NormalizedScope inferCityFromGroup(NormalizedScope scope, String group) {
        if (scope.city() != null && !scope.city().isBlank()) return scope;
        List<String> cities = jdbcTemplate.queryForList(
                "SELECT DISTINCT city_name FROM v_daily_group_operation WHERE region_name = ?",
                String.class, scope.region());
        return cities.stream().filter(group::startsWith).max(Comparator.comparingInt(String::length))
                .map(city -> new NormalizedScope(scope.region(), city)).orElse(scope);
    }

    private List<GroupCandidate> find(NormalizedScope scope, String predicate, String groupValue) {
        String cityPredicate = scope.city() == null ? "" : " AND city_name = ?";
        String sql = """
                SELECT DISTINCT group_id, city_name, group_name
                FROM v_daily_group_operation
                WHERE region_name = ?%s AND %s
                ORDER BY city_name, group_id
                """.formatted(cityPredicate, predicate);
        Object[] args = scope.city() == null ? new Object[] {scope.region(), groupValue} : new Object[] {scope.region(), scope.city(), groupValue};
        return jdbcTemplate.query(sql,
                (row, ignored) -> new GroupCandidate(row.getLong("group_id"), row.getString("city_name"), row.getString("group_name")),
                args);
    }

    public enum ResolutionMode { EXACT, UNIQUE_FUZZY }
    public record NormalizedScope(String region, String city) { }
    public record GroupCandidate(long groupId, String cityName, String groupName) { }
    public record ResolvedGroup(String region, String city, String requestedGroup, GroupCandidate matchedGroup, ResolutionMode mode) { }

    public static class ScopeResolutionException extends RuntimeException {
        private final NormalizedScope scope;
        private final String requestedGroup;
        private final List<GroupCandidate> candidates;
        private final boolean ambiguous;

        private ScopeResolutionException(String message, NormalizedScope scope, String requestedGroup, List<GroupCandidate> candidates, boolean ambiguous) {
            super(message);
            this.scope = scope;
            this.requestedGroup = requestedGroup;
            this.candidates = candidates;
            this.ambiguous = ambiguous;
        }

        static ScopeResolutionException ambiguous(NormalizedScope scope, String group, List<GroupCandidate> candidates) {
            return new ScopeResolutionException("桩群名称存在多个候选，请指定城市或完整桩群名称", scope, group, candidates, true);
        }

        static ScopeResolutionException notFound(NormalizedScope scope, String group) {
            return new ScopeResolutionException("未找到匹配的桩群", scope, group, List.of(), false);
        }

        public String region() { return scope.region(); }
        public String city() { return scope.city(); }
        public String requestedGroup() { return requestedGroup; }
        public List<GroupCandidate> candidates() { return candidates; }
        public boolean ambiguous() { return ambiguous; }
    }
}
