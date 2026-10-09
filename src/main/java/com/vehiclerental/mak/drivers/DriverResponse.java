package com.vehiclerental.mak.drivers;

import java.util.List;

public record DriverResponse(Long id, DriverRequest details, DriverFileResponse photo,
        List<DriverFileResponse> documents, List<DriverFileResponse> certifications,
        int bookingCount, int paymentCount) { }
