package com.vehiclerental.mak.employees.dto.response;

import java.util.List;

public record EmployeeImportResult(long successful, long failed, List<EmployeeImportRow> rows, String errorReportCsv) { }
