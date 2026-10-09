package com.vehiclerental.mak.employees;

import java.util.List;

public record EmployeeResponse(Long id, EmployeeRequest details, EmployeeFileResponse photo,
        List<EmployeeFileResponse> documents, int payrollCount) { }
