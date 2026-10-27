package com.vehiclerental.mak.payroll;

import java.math.BigDecimal;
import java.util.List;
import java.time.LocalDate;

/** Typed inputs: salary components describe entitlement, never a payment. */
public final class PayrollData {
    private PayrollData() {}
    public record Component(String name, BigDecimal amount, String settlement) {}
    public record ProfileRequest(Long version, String staffType, Long staffId, String effectiveMonth,
        BigDecimal basicSalary, BigDecimal fixedBankAmount, String accountName, String bankName,
        String iban, String accountNumber, List<Component> components, String notes) {}
    public record Adjustment(String name, String kind, BigDecimal amount, String settlement, String reason) {}
    public record DraftRequest(String period, List<Long> payeeIds) {}
    public record DraftUpdate(Long version, List<Adjustment> adjustments, String notes) {}
    public record ApprovalRequest(Long version, String reviewNote, boolean reviewed) {}
    public record CorrectionRequest(Long version, String reason) {}
    public record ReceivingAccount(String accountName, String bankName, String iban, String accountNumber) {}
    public record PaymentRequest(Long version, String requestKey, String method, BigDecimal amount, LocalDate paidOn, String reference, String notes, ReceivingAccount account, boolean confirmedPaid) {}
}
