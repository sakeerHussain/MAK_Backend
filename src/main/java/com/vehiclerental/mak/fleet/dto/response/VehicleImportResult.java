package com.vehiclerental.mak.fleet.dto.response;

import java.util.List;

public record VehicleImportResult(long successful, long failed, List<VehicleImportRow> rows, String errorReportCsv) { }
