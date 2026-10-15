package com.vehiclerental.mak.vendors.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record VendorResponse(long id, String vendorId, String vendorType, String name, String crNumber,
        String vatNumber, String nationalAddress, String classification, BigDecimal commission, String status,
        int creditTerms, LocalDate contractStart, LocalDate contractEnd, List<Contact> contacts,
        Bank bank, List<Attachment> attachments, long referenceCount) {
    public record Contact(String id, String name, String mobile, String email) { }
    public record Bank(String beneficiaryName, String bankName, String iban, String accountNumber,
            String swiftCode, String currency) { }
    public record Attachment(long id, String type, String name, long size, String url) {

    }
}
