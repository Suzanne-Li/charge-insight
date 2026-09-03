package com.chargeinsight.analytics.api;

import com.chargeinsight.analytics.service.DataImportService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/import")
public class DataImportController {
    private final DataImportService dataImportService;

    public DataImportController(DataImportService dataImportService) {
        this.dataImportService = dataImportService;
    }

    @PostMapping(value = "/orders", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importOrders(@RequestParam("file") MultipartFile file) {
        return Map.of("status", "SUCCESS", "result", dataImportService.importOrders(file));
    }

    @PostMapping(value = "/faults", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importFaults(@RequestParam("file") MultipartFile file) {
        return Map.of("status", "SUCCESS", "result", dataImportService.importFaults(file));
    }
}
