package com.vehiclerental.mak.fleet;

import java.util.List;

public record VehicleResponse(
        Long id, VehicleRequest details, String owner, String documents,
        List<VehicleFileResponse> carImages, List<VehicleFileResponse> documentRecords
) { }
