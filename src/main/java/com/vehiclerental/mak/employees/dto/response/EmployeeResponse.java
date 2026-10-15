package com.vehiclerental.mak.employees.dto.response;

import com.vehiclerental.mak.employees.dto.request.EmployeeRequest;

import java.util.List;

public record EmployeeResponse(Long id, EmployeeRequest details, EmployeeFileResponse photo,
        List<EmployeeFileResponse> documents, int payrollCount) { }
