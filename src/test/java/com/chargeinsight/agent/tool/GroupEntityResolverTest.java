package com.chargeinsight.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import com.chargeinsight.security.RegionAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class GroupEntityResolverTest {
    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final GroupEntityResolver resolver = new GroupEntityResolver(jdbcTemplate, new AnalyticsQueryPolicy(), new RegionAccessPolicy());

    @Test
    void returnsUniqueFuzzyMatchWhenExactNameIsNotPresent() {
        GroupEntityResolver.GroupCandidate candidate = new GroupEntityResolver.GroupCandidate(101, "上海", "上海私桩共享桩群1");
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of("华东"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("华东"), eq("私桩共享桩群1"))).thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("华东"), eq("%私桩共享桩群1%"))).thenReturn(List.of(candidate));

        var resolved = resolver.resolve("华东", "", "私桩共享桩群1");

        assertThat(resolved.mode()).isEqualTo(GroupEntityResolver.ResolutionMode.UNIQUE_FUZZY);
        assertThat(resolved.matchedGroup().groupId()).isEqualTo(101);
    }

    @Test
    void refusesAmbiguousFuzzyMatches() {
        List<GroupEntityResolver.GroupCandidate> candidates = List.of(
                new GroupEntityResolver.GroupCandidate(101, "上海", "上海私桩共享桩群1"),
                new GroupEntityResolver.GroupCandidate(104, "杭州", "杭州私桩共享桩群1"));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of("华东"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("华东"), eq("私桩共享桩群1"))).thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("华东"), eq("%私桩共享桩群1%"))).thenReturn(candidates);

        assertThatThrownBy(() -> resolver.resolve("华东", "", "私桩共享桩群1"))
                .isInstanceOf(GroupEntityResolver.ScopeResolutionException.class)
                .hasMessageContaining("多个候选");
    }

    @Test
    void normalizesConcatenatedRegionAndCityFromOlderPlannerOutput() {
        GroupEntityResolver.GroupCandidate candidate = new GroupEntityResolver.GroupCandidate(101, "上海", "上海私桩共享桩群1");
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of("华东"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("华东"), eq("上海"), eq("私桩共享桩群1"))).thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("华东"), eq("上海"), eq("%私桩共享桩群1%"))).thenReturn(List.of(candidate));

        var resolved = resolver.resolve("华东上海", "", "私桩共享桩群1");

        assertThat(resolved.region()).isEqualTo("华东");
        assertThat(resolved.city()).isEqualTo("上海");
    }
}
