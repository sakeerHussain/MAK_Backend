package com.vehiclerental.mak.fleet;

import java.time.LocalDate;

public record VehicleFileResponse(
        Long id, String name, String contentType, long size,
        String type, LocalDate expiryDate, String url
) {
    static VehicleFileResponse from(VehicleFile file) {
        return new VehicleFileResponse(file.getId(), file.getOriginalName(), file.getContentType(),
                file.getSizeBytes(), file.getDocumentType(), file.getExpiryDate(),
                "/api/vehicles/" + file.getVehicle().getId() + "/files/" + file.getId());
    }
}
