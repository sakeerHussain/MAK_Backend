package com.vehiclerental.mak.vendors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.vehiclerental.mak.common.ApiException;
import com.vehiclerental.mak.drivers.Driver;
import com.vehiclerental.mak.drivers.DriverRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest
class VendorServiceTests {
    @Autowired VendorService vendors;
    @Autowired VendorFileService files;
    @Autowired DriverRepository drivers;

    private VendorRequest request(String name, List<VendorRequest.Contact> contacts) {
        return new VendorRequest("Business", name, "CR-123", "123456789012345", "Riyadh",
                "Transport Company", new BigDecimal("7.50"), "Active", 30,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), contacts,
                new VendorRequest.Bank("Test Beneficiary", "Bank", "SA0380000000608010167519", "12345", "BANKSA", "SAR"));
    }

    private VendorRequest.Contact contact(String name) {
        return new VendorRequest.Contact(name, "+966501234567", name.toLowerCase().replace(" ", "") + "@example.com");
    }

    private VendorResponse create() {
        return vendors.create(request("Vendor " + UUID.randomUUID(), List.of(contact("Primary Person"), contact("Second Person"))));
    }

    @Test
    void createsListsEditsContactsAndArchives() {
        VendorResponse created = create();
        assertEquals("VEN-" + String.format("%03d", created.id()), created.vendorId());
        assertEquals("Primary Person", created.contacts().getFirst().name());
        assertEquals(1, vendors.list(created.name(), "Business", "Active", 0, 20).getTotalElements());
        VendorResponse updated = vendors.update(created.id(), request(created.name(), List.of(contact("Replacement"))));
        assertEquals(1, updated.contacts().size());
        assertEquals("Replacement", vendors.get(created.id()).contacts().getFirst().name());
        assertEquals("Inactive", vendors.updateStatus(created.id(), new VendorStatusRequest("Inactive")).status());
        vendors.archive(created.id());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> vendors.get(created.id())).status());
    }

    @Test
    void rejectsDuplicateNamesAndInvalidFields() {
        VendorResponse created = create();
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> vendors.create(
                request(created.name().toUpperCase(), List.of(contact("Primary Person"))))).status());
        VendorRequest invalid = new VendorRequest("Business", "Invalid " + UUID.randomUUID(), null, "123", null,
                null, new BigDecimal("101"), "Active", 30, null, null,
                List.of(contact("Primary Person")), null);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class, () -> vendors.create(invalid)).status());
    }

    @Test
    void storesReplacesAndRemovesAttachment() {
        VendorResponse vendor = create();
        byte[] png = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1, 2};
        MockMultipartFile upload = new MockMultipartFile("file", "profile.png", "image/png", png);
        VendorResponse.Attachment first = files.upload(vendor.id(), "Company Profile", upload);
        assertTrue(files.download(vendor.id(), first.id()).resource().exists());
        VendorResponse.Attachment replacement = files.upload(vendor.id(), "Company Profile", upload);
        assertEquals(1, vendors.get(vendor.id()).attachments().size());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> files.download(vendor.id(), first.id())).status());
        files.delete(vendor.id(), replacement.id());
        assertTrue(vendors.get(vendor.id()).attachments().isEmpty());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> files.upload(vendor.id(), "Unsupported", upload)).status());
    }

    @Test
    void linkedDriverBlocksRenameAndArchiveButAllowsDeactivation() {
        VendorResponse vendor = create();
        Driver driver = new Driver();
        String key = UUID.randomUUID().toString().substring(0, 8);
        driver.setEmployeeId("LINK-" + key);
        driver.setName("Linked Driver"); driver.setPhone("+966500" + key);
        driver.setPhoneKey("966500" + key); driver.setCategory("Chauffeur"); driver.setDesignation("Driver");
        driver.setEmploymentType("Vendor"); driver.setVendor(vendor.name()); driver.setStatus("Active");
        driver.setDailyRate(new BigDecimal("100"));
        drivers.saveAndFlush(driver);
        assertEquals(1, vendors.get(vendor.id()).referenceCount());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> vendors.update(vendor.id(),
                request(vendor.name() + " Renamed", List.of(contact("Primary Person"))))).status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> vendors.archive(vendor.id())).status());
        assertEquals("Inactive", vendors.updateStatus(vendor.id(), new VendorStatusRequest("Inactive")).status());
        assertFalse(vendors.get(vendor.id()).name().endsWith("Renamed"));
    }
}
