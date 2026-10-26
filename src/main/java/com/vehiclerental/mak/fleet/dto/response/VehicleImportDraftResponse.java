package com.vehiclerental.mak.fleet.dto.response;

import com.vehiclerental.mak.fleet.dto.request.VehicleRequest;
import java.time.Instant;
import java.util.List;

public record VehicleImportDraftResponse(long id, long version, VehicleRequest details, List<String> missingFields, Instant createdAt) { }
