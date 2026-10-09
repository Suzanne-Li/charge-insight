package com.chargeinsight.agent.api;

import com.chargeinsight.agent.sql.ControlledSqlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit, ADMIN-only SQL endpoint. Every request must declare a server-validated data scope. */
@RestController
@RequestMapping("/api/agent/sql")
public class ControlledSqlController {
    private final ControlledSqlService sqlService;

    public ControlledSqlController(ControlledSqlService sqlService) {
        this.sqlService = sqlService;
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@Valid @RequestBody SqlRequest request) {
        return Map.of("status", "SUCCESS", "result", sqlService.validateScoped(
                request.sql(), request.region(), request.startDate(), request.endDate()));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("status", "SUCCESS", "result", sqlService.readOnlyDatasourceStatus());
    }

    @PostMapping("/execute")
    public Map<String, Object> execute(@Valid @RequestBody SqlRequest request) {
        return Map.of("status", "SUCCESS", "result", sqlService.executeScoped(
                request.sql(), request.region(), request.startDate(), request.endDate()));
    }

    public record SqlRequest(@NotBlank String sql, @NotBlank String region,
                             @NotNull LocalDate startDate, @NotNull LocalDate endDate) { }
}
