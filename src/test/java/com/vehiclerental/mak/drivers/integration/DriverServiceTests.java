package com.vehiclerental.mak.drivers.integration;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.drivers.dto.request.CertificationRequest;
import com.vehiclerental.mak.drivers.dto.request.DriverRequest;
import com.vehiclerental.mak.drivers.dto.request.DriverStatusRequest;
import com.vehiclerental.mak.drivers.dto.response.DriverFileResponse;
import com.vehiclerental.mak.drivers.dto.response.DriverImportResult;
import com.vehiclerental.mak.drivers.dto.response.DriverResponse;
import com.vehiclerental.mak.drivers.entity.Driver;
import com.vehiclerental.mak.drivers.repository.DriverRepository;
import com.vehiclerental.mak.drivers.service.DriverFileService;
import com.vehiclerental.mak.drivers.service.DriverImportService;
import com.vehiclerental.mak.drivers.service.DriverService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest
class DriverServiceTests {
    @Autowired DriverService drivers;
    @Autowired DriverFileService files;
    @Autowired DriverImportService imports;
    @Autowired DriverRepository repository;

    private DriverRequest request(String employeeId, String phone) {
        return new DriverRequest(employeeId, "Amina Malik", phone, "amina@example.com", "Chauffeur", "Saudi Arabian",
                "Driver", "IQ-123", LocalDate.now().plusYears(2), 5, "Full-time", null, "Active", "C-25",
                LocalDate.of(2025, 1, 15), "+966501234567", new BigDecimal("25.00"), new BigDecimal("180.00"),
                new BigDecimal("3500.00"), "P-123", LocalDate.now().plusYears(2), "CT-77",
                LocalDate.now().plusYears(1), "LIC-77", LocalDate.now().plusYears(1), "O+", "IN-77",
                LocalDate.now().plusYears(1), "+966555000001", "+919900000001", "Riyadh, Saudi Arabia");
    }

    private DriverResponse create() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return drivers.create(request("DRV-" + suffix, "+96650" + String.valueOf(Math.abs(suffix.hashCode())).substring(0, 7)));
    }

    @Test
    void createsFiltersUpdatesAndArchivesDriver() {
        DriverResponse created = create();
        assertEquals("Chauffeur", created.details().category());
        assertEquals(1, drivers.list(created.details().employeeId(), null, "Active", 0, 20).getTotalElements());
        assertEquals("Inactive", drivers.updateStatus(created.id(), new DriverStatusRequest("Inactive")).details().status());
        assertEquals("Inactive", drivers.get(created.id()).details().status());
        drivers.archive(created.id());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> drivers.get(created.id())).status());
    }

    @Test
    void rejectsDuplicateEmployeeIdAndNormalizedPhone() {
        DriverResponse created = create();
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> drivers.create(
                request(created.details().employeeId().toLowerCase(), "+966599900001"))).status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> drivers.create(
                request("DRV-OTHER-" + created.id(), created.details().phone().replace("+", "")))).status());
    }

    @Test
    void persistsPhotoDocumentsAndCertifications() {
        DriverResponse created = create();
        byte[] png = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1, 2};
        MockMultipartFile image = new MockMultipartFile("file", "photo.png", "image/png", png);
        DriverFileResponse photo = files.uploadPhoto(created.id(), image);
        assertNotNull(photo.id());
        assertTrue(files.download(created.id(), photo.id()).resource().exists());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> files.uploadDocument(created.id(), "Iqama", null, image)).status());
        files.uploadDocument(created.id(), "Iqama", LocalDate.now().plusYears(1), image);
        files.uploadDocument(created.id(), "License copy", LocalDate.now().plusYears(1), image);
        DriverFileResponse certificate = files.addCertification(created.id(),
                new CertificationRequest("Defensive Driving", LocalDate.now().minusYears(1), LocalDate.now().plusYears(1)), null);
        assertEquals("Defensive Driving", certificate.name());
        assertEquals("Advanced Driving", files.updateCertification(created.id(), certificate.id(),
                new CertificationRequest("Advanced Driving", null, null)).name());
        assertEquals(2, drivers.get(created.id()).documents().size());
        assertEquals(1, drivers.get(created.id()).certifications().size());
        files.delete(created.id(), photo.id());
        assertEquals(null, drivers.get(created.id()).photo());
    }

    @Test
    void importsValidRowsAndReturnsRowErrors() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        DriverRequest valid = request("DRV-" + suffix, "+96651" + String.valueOf(Math.abs(suffix.hashCode())).substring(0, 7));
        DriverRequest duplicate = request("drv-" + suffix, "+966588800001");
        DriverImportResult result = imports.importRows(Arrays.asList(valid, duplicate, null));
        assertEquals(1, result.successful());
        assertEquals(2, result.failed());
        assertEquals(3, result.rows().size());
        assertTrue(result.errorReportCsv().contains("Employee ID already exists"));
    }

    @Test
    void historyPreventsRemovalButAllowsDeactivation() {
        DriverResponse created = create();
        Driver driver = repository.findById(created.id()).orElseThrow();
        driver.setBookingCount(1);
        repository.saveAndFlush(driver);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> drivers.archive(created.id())).status());
        assertEquals("Inactive", drivers.updateStatus(created.id(), new DriverStatusRequest("Inactive")).details().status());
    }

    @Test void renewedUploadSynchronizesProfileAndProfileEditsCannotRestoreStaleDates() {
        var d = create();
        var future = LocalDate.now(java.time.ZoneId.of("Asia/Riyadh")).plusYears(3);
        var upload = new MockMultipartFile("file", "renewed.pdf", "application/pdf", "%PDF-1.4 test".getBytes());
        files.uploadDocument(d.id(), "Iqama", future, upload);
        files.uploadDocument(d.id(), "License copy", future, upload);
        assertEquals(future, drivers.get(d.id()).details().iqamaExpiry());
        assertEquals(future, drivers.get(d.id()).details().licenceExpiry());
        drivers.update(d.id(), request(d.details().employeeId(), d.details().phone()));
        assertEquals(future, drivers.get(d.id()).details().licenceExpiry());
        assertEquals(future, drivers.get(d.id()).details().iqamaExpiry());
    }
}
