package com.vehiclerental.mak.fleet.service;

import com.vehiclerental.mak.fleet.entity.Vehicle;
import com.vehiclerental.mak.fleet.entity.VehicleFile;
import java.time.LocalDate;
import java.util.List;

/** Register dates are a fallback, never evidence that a document file exists. */
public final class VehicleCompliance {
    private VehicleCompliance() { }

    public static boolean hasExpired(Vehicle vehicle, List<VehicleFile> records, LocalDate today) {
        var documents = records.stream().filter(file -> "DOCUMENT".equals(file.getKind())).toList();
        if (documents.stream().anyMatch(file -> file.getExpiryDate() != null && file.getExpiryDate().isBefore(today))) return true;
        for (String type : List.of("Istimara", "Insurance", "Fahas")) {
            LocalDate date = switch (type) {
                case "Istimara" -> vehicle.getRegistrationExpiryDate();
                case "Insurance" -> vehicle.getInsuranceExpiryDate();
                default -> vehicle.getInspectionExpiryDate();
            };
            if (documents.stream().noneMatch(file -> type.equals(file.getDocumentType())) && date != null && date.isBefore(today)) return true;
        }
        return false;
    }
}
