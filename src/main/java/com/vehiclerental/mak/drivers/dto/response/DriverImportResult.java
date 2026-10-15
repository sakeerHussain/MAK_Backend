package com.vehiclerental.mak.drivers.dto.response;

import java.util.List;

public record DriverImportResult(long successful, long failed, List<DriverImportRow> rows, String errorReportCsv) { }
