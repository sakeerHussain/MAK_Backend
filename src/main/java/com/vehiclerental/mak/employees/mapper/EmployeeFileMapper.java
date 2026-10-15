package com.vehiclerental.mak.employees.mapper;

import com.vehiclerental.mak.employees.dto.response.EmployeeFileResponse;
import com.vehiclerental.mak.employees.entity.EmployeeFile;

public final class EmployeeFileMapper {
    private EmployeeFileMapper() { }

    public static EmployeeFileResponse toResponse(EmployeeFile file) {
        String url = file.getStorageKey() == null ? null : "/api/employees/" + file.getEmployee().getId() + "/files/" + file.getId();
        String name = file.getOriginalName();
        return new EmployeeFileResponse(file.getId(), file.getKind(), file.getDocumentType(), name,
                file.getOriginalName(), file.getExpiryDate(), file.getSizeBytes(), url);
    }
}
