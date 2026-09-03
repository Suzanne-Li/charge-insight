package com.chargeinsight.analytics.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DataImportService {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbcTemplate;

    public DataImportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ImportResult importOrders(MultipartFile file) {
        return importFile(file, "ORDER", this::saveOrder);
    }

    @Transactional
    public ImportResult importFaults(MultipartFile file) {
        return importFile(file, "FAULT", this::saveFault);
    }

    private ImportResult importFile(MultipartFile file, String dataType, RowConsumer consumer) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("CSV file must not be empty");
        }
        String batchId = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        jdbcTemplate.update("INSERT INTO data_import_batch(batch_id, data_type, source_file_name, import_status, started_at) VALUES (?, ?, ?, 'RUNNING', NOW())",
                batchId, dataType, fileName);
        int total = 0;
        int success = 0;
        int failed = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException("CSV header is required");
            }
            List<String> headers = parseCsv(headerLine);
            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) continue;
                total++;
                try {
                    consumer.accept(toRow(headers, parseCsv(line)));
                    success++;
                } catch (Exception ex) {
                    failed++;
                    jdbcTemplate.update("INSERT INTO data_import_error(batch_id, import_row_number, raw_content, error_message) VALUES (?, ?, ?, ?)",
                            batchId, rowNumber, line, messageOf(ex));
                }
            }
            String status = failed == 0 ? "SUCCESS" : (success == 0 ? "FAILED" : "PARTIAL_SUCCESS");
            jdbcTemplate.update("UPDATE data_import_batch SET total_count=?, success_count=?, failed_count=?, import_status=?, completed_at=NOW() WHERE batch_id=?",
                    total, success, failed, status, batchId);
            return new ImportResult(batchId, dataType, total, success, failed, status);
        } catch (IOException ex) {
            jdbcTemplate.update("UPDATE data_import_batch SET import_status='FAILED', completed_at=NOW() WHERE batch_id=?", batchId);
            throw new IllegalStateException("failed to read CSV", ex);
        }
    }

    private void saveOrder(Map<String, String> row) {
        String orderId = required(row, "order_id");
        long pileId = Long.parseLong(required(row, "pile_id"));
        LocalDateTime start = parseTime(required(row, "start_time"));
        LocalDateTime end = parseTime(required(row, "end_time"));
        BigDecimal energy = decimal(required(row, "energy_kwh"));
        BigDecimal amount = decimal(required(row, "amount"));
        String status = required(row, "order_status");
        String channel = required(row, "pay_channel");
        BigDecimal income = decimal(row.getOrDefault("owner_income", amount.multiply(BigDecimal.valueOf(0.95)).toPlainString()));
        BigDecimal fee = decimal(row.getOrDefault("platform_fee", amount.subtract(income).toPlainString()));
        jdbcTemplate.update("INSERT INTO charging_order(order_id,pile_id,start_time,end_time,energy_kwh,amount,owner_income,platform_fee,order_status,pay_channel,split_status) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE pile_id=VALUES(pile_id),start_time=VALUES(start_time),end_time=VALUES(end_time),energy_kwh=VALUES(energy_kwh),amount=VALUES(amount),owner_income=VALUES(owner_income),platform_fee=VALUES(platform_fee),order_status=VALUES(order_status),pay_channel=VALUES(pay_channel),split_status=VALUES(split_status)",
                orderId, pileId, Timestamp.valueOf(start), Timestamp.valueOf(end), energy, amount, income, fee, status, channel, row.get("split_status"));
    }

    private void saveFault(Map<String, String> row) {
        String faultId = required(row, "fault_id");
        long pileId = Long.parseLong(required(row, "pile_id"));
        LocalDateTime start = parseTime(required(row, "start_time"));
        String recover = row.get("recover_time");
        jdbcTemplate.update("INSERT INTO fault_event(fault_id,pile_id,fault_code,fault_type,severity,start_time,recover_time) VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE pile_id=VALUES(pile_id),fault_code=VALUES(fault_code),fault_type=VALUES(fault_type),severity=VALUES(severity),start_time=VALUES(start_time),recover_time=VALUES(recover_time)",
                faultId, pileId, required(row, "fault_code"), required(row, "fault_type"), required(row, "severity"), Timestamp.valueOf(start),
                recover == null || recover.isBlank() ? null : Timestamp.valueOf(parseTime(recover)));
    }

    private static Map<String, String> toRow(List<String> headers, List<String> values) {
        if (headers.size() != values.size()) throw new IllegalArgumentException("column count does not match header");
        Map<String, String> row = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) row.put(headers.get(index).trim(), values.get(index).trim());
        return row;
    }

    private static List<String> parseCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') { field.append('"'); index++; }
                else quoted = !quoted;
            } else if (current == ',' && !quoted) { fields.add(field.toString()); field.setLength(0); }
            else field.append(current);
        }
        if (quoted) throw new IllegalArgumentException("unclosed quote");
        fields.add(field.toString());
        return fields;
    }

    private static String required(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    private static LocalDateTime parseTime(String value) { return LocalDateTime.parse(value, DATE_TIME); }
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static String messageOf(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage().substring(0, Math.min(500, ex.getMessage().length())); }
    private interface RowConsumer { void accept(Map<String, String> row); }
    public record ImportResult(String batchId, String dataType, int totalCount, int successCount, int failedCount, String status) { }
}
