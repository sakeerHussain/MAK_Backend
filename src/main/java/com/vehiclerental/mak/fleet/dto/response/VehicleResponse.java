package com.vehiclerental.mak.fleet.dto.response;

import com.vehiclerental.mak.fleet.dto.request.VehicleRequest;

import java.util.List;

public record VehicleResponse(
        Long id, VehicleRequest details, String owner, String documents,
        List<VehicleFileResponse> carImages, List<VehicleFileResponse> documentRecords
) { }
