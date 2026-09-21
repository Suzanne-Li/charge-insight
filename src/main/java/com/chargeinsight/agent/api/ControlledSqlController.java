package com.chargeinsight.agent.api;

import com.chargeinsight.agent.sql.ControlledSqlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit fallback endpoint; no agent route invokes it automatically. */
@RestController
@RequestMapping("/api/agent/sql")
public class ControlledSqlController {
    private final ControlledSqlService sqlService;

    public ControlledSqlController(ControlledSqlService sqlService) {
        this.sqlService = sqlService;
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@Valid @RequestBody SqlRequest request) {
        return Map.of("status", "SUCCESS", "result", sqlService.validate(request.sql()));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("status", "SUCCESS", "result", sqlService.readOnlyDatasourceStatus());
    }

    @PostMapping("/execute")
    public Map<String, Object> execute(@Valid @RequestBody SqlRequest request) {
        return Map.of("status", "SUCCESS", "result", sqlService.execute(request.sql()));
    }

    public record SqlRequest(@NotBlank String sql) { }
}
