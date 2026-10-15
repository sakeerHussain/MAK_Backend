package com.vehiclerental.mak.drivers.dto.response;

import com.vehiclerental.mak.drivers.dto.request.DriverRequest;

import java.util.List;

public record DriverResponse(Long id, DriverRequest details, DriverFileResponse photo,
        List<DriverFileResponse> documents, List<DriverFileResponse> certifications,
        int bookingCount, int paymentCount) { }
