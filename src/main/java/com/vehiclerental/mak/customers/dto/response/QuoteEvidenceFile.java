package com.vehiclerental.mak.customers.dto.response;

public record QuoteEvidenceFile(String fileName, String contentType, byte[] fileData) { }
