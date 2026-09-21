package com.chargeinsight.common.api;

import com.chargeinsight.agent.sql.ControlledSqlService;
import com.chargeinsight.security.LocalAuthService;
import com.chargeinsight.security.RegionAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Keeps public API failures structured without exposing stack traces or configuration values. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalidRequest(IllegalArgumentException exception, HttpServletRequest request) {
        return response("INVALID_REQUEST", exception.getMessage(), request, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(QueryTimeoutException.class)
    public ResponseEntity<Map<String, Object>> queryTimeout(HttpServletRequest request) {
        return response("QUERY_TIMEOUT", "分析查询超时，请缩小时间范围后重试", request, HttpStatus.GATEWAY_TIMEOUT);
    }

    @ExceptionHandler(RegionAccessPolicy.RegionAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> regionForbidden(RegionAccessPolicy.RegionAccessDeniedException exception, HttpServletRequest request) {
        return response("REGION_FORBIDDEN", exception.getMessage(), request, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(LocalAuthService.InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> invalidCredentials(HttpServletRequest request) {
        return response("UNAUTHORIZED", "用户名或密码错误", request, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(ControlledSqlService.ControlledSqlExecutionException.class)
    public ResponseEntity<Map<String, Object>> controlledSqlFailure(
            ControlledSqlService.ControlledSqlExecutionException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", "CONTROLLED_SQL_EXECUTION_FAILED",
                "message", "受控 SQL 执行未完成，请根据 phase 检查只读权限或缩小查询范围",
                "phase", exception.phase(),
                "path", request.getRequestURI(),
                "httpStatus", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "timestamp", Instant.now().toString()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> internalError(HttpServletRequest request) {
        return response("FAILED", "分析服务暂时不可用，请稍后重试", request, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<Map<String, Object>> response(String status, String message, HttpServletRequest request, HttpStatus httpStatus) {
        return ResponseEntity.status(httpStatus).body(Map.of("status", status, "message", message, "path", request.getRequestURI(),
                "httpStatus", httpStatus.value(), "timestamp", Instant.now().toString()));
    }
}
