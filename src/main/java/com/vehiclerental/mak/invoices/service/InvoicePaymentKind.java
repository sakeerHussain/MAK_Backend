package com.vehiclerental.mak.invoices.service;

import com.vehiclerental.mak.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A single interpretation shared by payment authorization and processing. */
public enum InvoicePaymentKind {
    PAYMENT("Payment", "invoices.edit"), REFUND("Refund", "payments.refund");
    private final String value;
    private final String permission;
    InvoicePaymentKind(String value, String permission) { this.value=value; this.permission=permission; }
    public String value() { return value; }
    public String permission() { return permission; }
    public static InvoicePaymentKind parse(Object raw) {
        if (raw instanceof String text) for (var kind : values()) if (kind.value.equals(text.trim())) return kind;
        throw new ApiException(HttpStatus.BAD_REQUEST, "Choose Payment or Refund.");
    }
}
