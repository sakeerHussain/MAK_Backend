package com.vehiclerental.mak.drivers;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.vehiclerental.mak.common.ApiException;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DriverImportService {
    private final DriverService drivers;
    private final Validator validator;

    public DriverImportService(DriverService drivers, Validator validator) {
        this.drivers = drivers;
        this.validator = validator;
    }

    public ImportResult importRows(List<DriverRequest> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > 2000) throw new ApiException(HttpStatus.BAD_REQUEST, "Import between 1 and 2,000 drivers.");
        List<ImportRow> results = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            DriverRequest request = rows.get(index);
            if (request == null) {
                results.add(new ImportRow(index + 2, "", "", "", null, "Driver row is empty."));
                continue;
            }
            String violations = validator.validate(request).stream()
                    .map(item -> item.getPropertyPath() + ": " + item.getMessage()).sorted().collect(Collectors.joining("; "));
            if (!violations.isEmpty()) {
                results.add(new ImportRow(index + 2, request.employeeId(), request.name(), request.phone(), null, violations));
                continue;
            }
            try {
                DriverResponse created = drivers.create(request);
                results.add(new ImportRow(index + 2, request.employeeId(), request.name(), request.phone(), created.id(), null));
            } catch (ApiException error) {
                results.add(new ImportRow(index + 2, request.employeeId(), request.name(), request.phone(), null, error.getMessage()));
            } catch (DataIntegrityViolationException error) {
                results.add(new ImportRow(index + 2, request.employeeId(), request.name(), request.phone(), null, "Duplicate Employee ID or phone number."));
            }
        }
        long successful = results.stream().filter(item -> item.error() == null).count();
        StringBuilder csv = new StringBuilder("Row,Employee ID,Name,Phone,Errors\r\n");
        results.stream().filter(item -> item.error() != null).forEach(item -> csv.append(item.rowNumber()).append(',')
                .append(quote(item.employeeId())).append(',').append(quote(item.name())).append(',')
                .append(quote(item.phone())).append(',').append(quote(item.error())).append("\r\n"));
        return new ImportResult(successful, results.size() - successful, results, csv.toString());
    }

    private String quote(String value) {
        String safe = value == null ? "" : value;
        if (safe.matches("^[\\s]*[=+@-].*")) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public record ImportRow(int rowNumber, String employeeId, String name, String phone, Long id, String error) { }
    public record ImportResult(long successful, long failed, List<ImportRow> rows, String errorReportCsv) { }
}
