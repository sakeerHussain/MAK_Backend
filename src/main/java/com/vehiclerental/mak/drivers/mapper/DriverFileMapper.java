package com.vehiclerental.mak.drivers.mapper;

import com.vehiclerental.mak.drivers.dto.response.DriverFileResponse;
import com.vehiclerental.mak.drivers.entity.DriverFile;

public final class DriverFileMapper {
    private DriverFileMapper() { }

    public static DriverFileResponse toResponse(DriverFile file) {
        String url = file.getStorageKey() == null ? null : "/api/drivers/" + file.getDriver().getId() + "/files/" + file.getId();
        String name = "CERTIFICATION".equals(file.getKind()) ? file.getCertificateName() : file.getOriginalName();
        return new DriverFileResponse(file.getId(), file.getKind(), file.getDocumentType(), name,
                file.getOriginalName(), file.getIssueDate(), file.getExpiryDate(), file.getSizeBytes(), url);
    }
}
