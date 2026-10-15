package com.vehiclerental.mak.employees.dto.response;

import java.time.LocalDate;

public record EmployeeFileResponse(Long id, String kind, String type, String name, String fileName,
        LocalDate expiryDate, Long size, String url) {

}
