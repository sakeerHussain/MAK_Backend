package com.vehiclerental.mak.fleet.mapper;

import com.vehiclerental.mak.fleet.dto.response.VehicleFileResponse;
import com.vehiclerental.mak.fleet.entity.VehicleFile;

public final class VehicleFileMapper {
    private VehicleFileMapper() { }

    public static VehicleFileResponse toResponse(VehicleFile file) {
        return new VehicleFileResponse(file.getId(), file.getOriginalName(), file.getContentType(),
                file.getSizeBytes(), file.getDocumentType(), file.getExpiryDate(),
                "/api/vehicles/" + file.getVehicle().getId() + "/files/" + file.getId());
    }
}
