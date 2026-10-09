package com.vehiclerental.mak.employees;

import java.time.LocalDate;

public record EmployeeFileResponse(Long id, String kind, String type, String name, String fileName,
        LocalDate expiryDate, Long size, String url) {
    static EmployeeFileResponse from(EmployeeFile file) {
        String url = file.getStorageKey() == null ? null : "/api/employees/" + file.getEmployee().getId() + "/files/" + file.getId();
        String name = file.getOriginalName();
        return new EmployeeFileResponse(file.getId(), file.getKind(), file.getDocumentType(), name,
                file.getOriginalName(), file.getExpiryDate(), file.getSizeBytes(), url);
    }
}
