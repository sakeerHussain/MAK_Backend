package com.vehiclerental.mak.drivers.service;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.drivers.dto.request.DriverRequest;
import com.vehiclerental.mak.drivers.dto.response.DriverImportResult;
import com.vehiclerental.mak.drivers.dto.response.DriverImportRow;
import com.vehiclerental.mak.drivers.dto.response.DriverResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
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

    public DriverImportResult importRows(List<DriverRequest> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > 2000) throw new ApiException(HttpStatus.BAD_REQUEST, "Import between 1 and 2,000 drivers.");
        List<DriverImportRow> results = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            DriverRequest request = rows.get(index);
            if (request == null) {
                results.add(new DriverImportRow(index + 2, "", "", "", null, "Driver row is empty."));
                continue;
            }
            String violations = validator.validate(request).stream()
                    .map(item -> item.getPropertyPath() + ": " + item.getMessage()).sorted().collect(Collectors.joining("; "));
            if (!violations.isEmpty()) {
                results.add(new DriverImportRow(index + 2, request.employeeId(), request.name(), request.phone(), null, violations));
                continue;
            }
            try {
                DriverResponse created = drivers.create(request);
                results.add(new DriverImportRow(index + 2, request.employeeId(), request.name(), request.phone(), created.id(), null));
            } catch (ApiException error) {
                results.add(new DriverImportRow(index + 2, request.employeeId(), request.name(), request.phone(), null, error.getMessage()));
            } catch (DataIntegrityViolationException error) {
                results.add(new DriverImportRow(index + 2, request.employeeId(), request.name(), request.phone(), null, "Duplicate Employee ID or phone number."));
            }
        }
        long successful = results.stream().filter(item -> item.error() == null).count();
        StringBuilder csv = new StringBuilder("Row,Employee ID,Name,Phone,Errors\r\n");
        results.stream().filter(item -> item.error() != null).forEach(item -> csv.append(item.rowNumber()).append(',')
                .append(quote(item.employeeId())).append(',').append(quote(item.name())).append(',')
                .append(quote(item.phone())).append(',').append(quote(item.error())).append("\r\n"));
        return new DriverImportResult(successful, results.size() - successful, results, csv.toString());
    }

    private String quote(String value) {
        String safe = value == null ? "" : value;
        if (safe.matches("^[\\s]*[=+@-].*")) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }



}
