package com.vehiclerental.mak.drivers.dto.response;

import java.time.LocalDate;

public record DriverFileResponse(Long id, String kind, String type, String name, String fileName,
        LocalDate issueDate, LocalDate expiryDate, Long size, String url) {

}
