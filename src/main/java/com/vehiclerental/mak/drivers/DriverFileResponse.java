package com.vehiclerental.mak.drivers;

import java.time.LocalDate;

public record DriverFileResponse(Long id, String kind, String type, String name, String fileName,
        LocalDate issueDate, LocalDate expiryDate, Long size, String url) {
    static DriverFileResponse from(DriverFile file) {
        String url = file.getStorageKey() == null ? null : "/api/drivers/" + file.getDriver().getId() + "/files/" + file.getId();
        String name = "CERTIFICATION".equals(file.getKind()) ? file.getCertificateName() : file.getOriginalName();
        return new DriverFileResponse(file.getId(), file.getKind(), file.getDocumentType(), name,
                file.getOriginalName(), file.getIssueDate(), file.getExpiryDate(), file.getSizeBytes(), url);
    }
}
