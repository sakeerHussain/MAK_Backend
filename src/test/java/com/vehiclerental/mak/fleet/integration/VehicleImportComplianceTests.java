package com.vehiclerental.mak.fleet.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.vehiclerental.mak.fleet.dto.request.VehicleRequest;
import com.vehiclerental.mak.fleet.service.VehicleFileService;
import com.vehiclerental.mak.fleet.service.VehicleImportService;
import com.vehiclerental.mak.fleet.service.VehicleService;
import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest
class VehicleImportComplianceTests {
    @Autowired VehicleImportService imports;
    @Autowired VehicleService vehicles;
    @Autowired VehicleFileService files;
    @Autowired VehicleRepository repository;

    private VehicleRequest bus(LocalDate registration, LocalDate insurance, LocalDate inspection, String type) {
        String key = UUID.randomUUID().toString();
        return new VehicleRequest("Leyland", "Oyster", "REG-" + key, 2026, "White", "VH-" + key,
                null, null, type, 35, "Manual", "Diesel", List.of(), null, new BigDecimal("500.00"), null, "SAR",
                "Owned", null, "Inactive", null, false, false, false,
                null, null, null, null, null, null, null, null, null, registration, insurance, inspection);
    }

    @Test void importsBusAndPersistsDatesWithoutFabricatingDocumentFiles() {
        LocalDate future = LocalDate.now(ZoneId.of("Asia/Riyadh")).plusYears(2);
        var result = imports.importRows(List.of(bus(future, future.plusDays(1), future.plusDays(2), "Bus")));
        assertEquals(1, result.successful());
        long id = result.rows().getFirst().id();
        var saved = vehicles.get(id);
        assertEquals("Bus", saved.details().type());
        assertEquals(future, repository.findById(id).orElseThrow().getRegistrationExpiryDate());
        assertEquals(future.plusDays(1), saved.details().insuranceExpiryDate());
        assertEquals(future.plusDays(2), saved.details().inspectionExpiryDate());
        assertEquals("Missing", saved.documents());
        assertTrue(saved.documentRecords().isEmpty());
        assertEquals(1, vehicles.list(saved.details().registration(), null, "Bus", null, 0, 20).getTotalElements());
        vehicles.update(id, saved.details());
        assertEquals(future, vehicles.get(id).details().registrationExpiryDate());
    }

    @Test void expiredRegisterDatesRemainVisibleAndRenewedAttachmentsTakePriority() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Riyadh"));
        var result = imports.importRows(List.of(bus(today.plusYears(1), today.minusDays(1), null, "Bus")));
        var saved = vehicles.get(result.rows().getFirst().id());
        assertEquals("Expired", saved.documents());
        byte[] png = new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10, 1};
        var file = new MockMultipartFile("file", "renewed.png", "image/png", png);
        files.uploadDocument(saved.id(), "Insurance", today.plusYears(1), file);
        assertEquals("Missing", vehicles.get(saved.id()).documents());
        for (String type : List.of("Istimara", "Fahas")) files.uploadDocument(saved.id(), type, today.plusYears(1), file);
        assertEquals("Complete", vehicles.get(saved.id()).documents());
        assertEquals(today.minusDays(1), vehicles.get(saved.id()).details().insuranceExpiryDate());
    }

    @Test void importKeepsOptionalDatesOptionalAndRejectsUnsupportedTypesPerRow() {
        var result = imports.importRows(List.of(bus(null, null, null, "Bus"), bus(null, null, null, "Unknown")));
        assertEquals(1, result.successful());
        assertEquals(1, result.failed());
        assertTrue(result.rows().get(1).error().contains("type"));
        assertNull(vehicles.get(result.rows().getFirst().id()).details().registrationExpiryDate());
        assertTrue(imports.template().contains("Registration Expiry Date,Insurance Expiry Date,Inspection Expiry Date"));
    }
}
