package com.vehiclerental.mak.fleet.integration;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.fleet.dto.request.VehicleRequest;
import com.vehiclerental.mak.fleet.dto.request.VehicleStatusRequest;
import com.vehiclerental.mak.fleet.dto.response.VehicleFileResponse;
import com.vehiclerental.mak.fleet.dto.response.VehicleImportResult;
import com.vehiclerental.mak.fleet.dto.response.VehicleResponse;
import com.vehiclerental.mak.fleet.service.VehicleFileService;
import com.vehiclerental.mak.fleet.service.VehicleImportService;
import com.vehiclerental.mak.fleet.service.VehicleService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest
class VehicleServiceTests {
    @Autowired VehicleService vehicles;
    @Autowired VehicleFileService files;
    @Autowired VehicleImportService imports;
    @Autowired Validator validator;

    private VehicleRequest request(String registration, String businessId) {
        return new VehicleRequest("Toyota", "Camry", registration, 2024, "Silver", businessId,
                "D-12", "JT123456789012345", "Sedan", 5, "Automatic", "Hybrid", List.of("GPS", "Bluetooth"),
                new BigDecimal("12.00"), new BigDecimal("72.00"), new BigDecimal("1800.00"), "USD",
                "Owned", null, "Available", "Unassigned", true, true, false,
                10000L, 25000L, 30000L, 10000L, 25000L, 40000L, 10000L, 30000L, 10000L);
    }

    private VehicleResponse create() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return vehicles.create(request("rnt-" + suffix, "vh-" + suffix));
    }

    @Test
    void createsAndFiltersFleetRecords() {
        VehicleResponse created = create();
        assertTrue(created.details().registration().startsWith("RNT-"));
        assertTrue(created.details().vehicleId().startsWith("VH-"));
        assertEquals("Missing", created.documents());
        assertEquals("Company Fleet", created.owner());
        assertEquals(List.of("GPS", "Bluetooth"), vehicles.get(created.id()).details().featureTags());
        assertEquals(1, vehicles.list(created.details().registration(), "Available", "Sedan", true, 0, 20).getTotalElements());
        assertTrue(vehicles.summary().total() >= 1);
        assertEquals("In Use", vehicles.updateStatus(created.id(), new VehicleStatusRequest("In Use")).details().status());
    }

    @Test
    void rejectsDuplicatesAndInvalidMaintenanceReadings() {
        VehicleResponse created = create();
        VehicleRequest duplicate = request(created.details().registration().toLowerCase(), "VH-ANOTHER-" + created.id());
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> vehicles.create(duplicate)).status());
        VehicleRequest invalid = new VehicleRequest("Toyota", "Camry", "RNT-BAD", 2024, null, "VH-BAD", null, null,
                "Sedan", 5, "Automatic", "Petrol", List.of(), null, BigDecimal.TEN, null, "USD",
                "Vendor", null, "Available", null, true, true, false,
                10000L, 35000L, 30000L, null, null, null, null, null, null);
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, assertThrows(ApiException.class, () -> vehicles.create(invalid)).status());
        assertFalse(validator.validate(request("RNT-OK", "VH-OK")).size() > 0);
    }

    @Test
    void documentUploadsDriveTheConditionIndicatorAndCanBeDownloaded() {
        VehicleResponse created = create();
        byte[] png = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1, 2};
        MockMultipartFile image = new MockMultipartFile("file", "front.png", "image/png", png);
        VehicleFileResponse photo = files.uploadImage(created.id(), image);
        assertNotNull(photo.id());
        assertTrue(files.download(created.id(), photo.id()).resource().exists());
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> files.uploadDocument(created.id(), "Insurance", null, image)).status());
        for (String type : List.of("Istimara", "Insurance", "Fahas")) {
            files.uploadDocument(created.id(), type, LocalDate.now().plusYears(1), image);
        }
        assertEquals("Complete", vehicles.get(created.id()).documents());
        files.uploadDocument(created.id(), "Insurance", LocalDate.now().minusDays(1), image);
        assertEquals("Expired", vehicles.get(created.id()).documents());
        assertEquals(3, vehicles.get(created.id()).documentRecords().size());
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> files.uploadImage(created.id(),
                        new MockMultipartFile("file", "bad.txt", "text/plain", "not an image".getBytes()))).status());
        files.delete(created.id(), photo.id());
        assertEquals(0, vehicles.get(created.id()).carImages().size());
    }

    @Test
    void importsValidRowsAndReportsDuplicateAndInvalidRows() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        VehicleRequest valid = request("RNT-" + suffix, "VH-" + suffix);
        VehicleRequest duplicate = request("rnt-" + suffix, "VH-OTHER-" + suffix);
        VehicleImportResult result = imports.importRows(java.util.Arrays.asList(valid, duplicate, null));
        assertEquals(1, result.successful());
        assertEquals(2, result.failed());
        assertEquals(3, result.rows().size());
        assertTrue(result.errorReportCsv().contains("Registration number already exists"));
        assertTrue(result.errorReportCsv().contains("Vehicle row is empty"));
        assertTrue(imports.template().contains("Registration Number"));
    }
}
