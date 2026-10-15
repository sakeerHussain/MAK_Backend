package com.vehiclerental.mak.fleet.dto.response;

import java.time.LocalDate;

public record VehicleFileResponse(
        Long id, String name, String contentType, long size,
        String type, LocalDate expiryDate, String url
) {

}
