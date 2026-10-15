package com.vehiclerental.mak.employees.service;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.employees.dto.request.EmployeeRequest;
import com.vehiclerental.mak.employees.dto.response.EmployeeImportResult;
import com.vehiclerental.mak.employees.dto.response.EmployeeImportRow;
import com.vehiclerental.mak.employees.dto.response.EmployeeResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class EmployeeImportService {
    private final EmployeeService employees;
    private final Validator validator;

    public EmployeeImportService(EmployeeService employees, Validator validator) {
        this.employees = employees;
        this.validator = validator;
    }

    public EmployeeImportResult importRows(List<EmployeeRequest> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > 2000) throw new ApiException(HttpStatus.BAD_REQUEST, "Import between 1 and 2,000 employees.");
        List<EmployeeImportRow> results = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            EmployeeRequest request = rows.get(index);
            if (request == null) {
                results.add(new EmployeeImportRow(index + 2, "", "", "", null, "Employee row is empty."));
                continue;
            }
            String violations = validator.validate(request).stream()
                    .map(item -> item.getPropertyPath() + ": " + item.getMessage()).sorted().collect(Collectors.joining("; "));
            if (!violations.isEmpty()) {
                results.add(new EmployeeImportRow(index + 2, request.employeeId(), request.name(), request.phone(), null, violations));
                continue;
            }
            try {
                EmployeeResponse created = employees.create(request);
                results.add(new EmployeeImportRow(index + 2, request.employeeId(), request.name(), request.phone(), created.id(), null));
            } catch (ApiException error) {
                results.add(new EmployeeImportRow(index + 2, request.employeeId(), request.name(), request.phone(), null, error.getMessage()));
            } catch (DataIntegrityViolationException error) {
                results.add(new EmployeeImportRow(index + 2, request.employeeId(), request.name(), request.phone(), null, "Duplicate Employee ID or phone number."));
            }
        }
        long successful = results.stream().filter(item -> item.error() == null).count();
        StringBuilder csv = new StringBuilder("Row,Employee ID,Name,Phone,Errors\r\n");
        results.stream().filter(item -> item.error() != null).forEach(item -> csv.append(item.rowNumber()).append(',')
                .append(quote(item.employeeId())).append(',').append(quote(item.name())).append(',')
                .append(quote(item.phone())).append(',').append(quote(item.error())).append("\r\n"));
        return new EmployeeImportResult(successful, results.size() - successful, results, csv.toString());
    }

    private String quote(String value) {
        String safe = value == null ? "" : value;
        if (safe.matches("^[\\s]*[=+@-].*")) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }



}
