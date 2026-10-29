package com.vehiclerental.mak.drivers.service;

import com.vehiclerental.mak.drivers.entity.Driver;
import com.vehiclerental.mak.drivers.entity.DriverFile;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Uploaded copies are authoritative; profile-only dates remain a fallback. */
public final class DriverCompliance {
    private DriverCompliance() { }
    public static final java.util.Set<String> REQUIRED_COPIES = java.util.Set.of("Iqama", "License copy");
    public static final java.util.Set<String> EXPIRING_COPIES = java.util.Set.of("Iqama", "License copy", "Passport copy", "Contract copy");
    public static Map<String,LocalDate> profileDates(Driver d) {
        Map<String,LocalDate> dates = new LinkedHashMap<>();
        dates.put("Iqama", d.getIqamaExpiry()); dates.put("License copy", d.getLicenceExpiry());
        dates.put("Passport copy", d.getPassportExpiry()); dates.put("Contract copy", d.getContractExpiry());
        dates.put("Insurance", d.getInsuranceExpiry());
        return dates;
    }
    public static Map<String,LocalDate> effectiveDates(Driver d, List<DriverFile> records) {
        var dates = profileDates(d);
        for (var file : records) if ("DOCUMENT".equals(file.getKind()) && file.getExpiryDate() != null)
            dates.put(file.getDocumentType(), file.getExpiryDate());
        return dates;
    }
    public static void syncDate(Driver d, String type, LocalDate expiry) {
        if (expiry == null) return; // An undated copy never clears a previously known profile expiry.
        switch (type) {
            case "Iqama" -> d.setIqamaExpiry(expiry);
            case "License copy" -> d.setLicenceExpiry(expiry);
            case "Passport copy" -> d.setPassportExpiry(expiry);
            case "Contract copy" -> d.setContractExpiry(expiry);
            default -> { }
        }
    }
    public static String bookingIssue(Driver d, List<DriverFile> records, LocalDate through) {
        if (d.isArchived() || !"Active".equals(d.getStatus())) return "Driver is inactive.";
        List<DriverFile> docs = records.stream().filter(f -> "DOCUMENT".equals(f.getKind())).toList();
        for (String type : List.of("Iqama", "License copy")) {
            var file = docs.stream().filter(f -> type.equals(f.getDocumentType())).findFirst().orElse(null);
            if (file == null || file.getStorageKey() == null || file.getOriginalName() == null || file.getOriginalName().isBlank() || file.getExpiryDate() == null)
                return "Driver assignment blocked: upload " + type + " with an expiry date.";
        }
        var dates = effectiveDates(d, docs);
        for (String type : REQUIRED_COPIES) {
            LocalDate expiry = dates.get(type);
            if (expiry != null && expiry.isBefore(through))
                return "Driver assignment blocked: " + type + " must be valid through " + through + " (KSA).";
        }
        return null;
    }
}
