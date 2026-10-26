package com.vehiclerental.mak.invoices.service;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.invoices.entity.*;
import com.vehiclerental.mak.invoices.repository.*;
import com.vehiclerental.mak.rentals.entity.RentalRecord;
import com.vehiclerental.mak.rentals.repository.RentalRepository;
import com.vehiclerental.mak.notifications.email.service.EmailService;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvoiceDatePolicyTests {
    private InvoiceTime time(String instant) { return new InvoiceTime(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)); }
    private final InvoiceRepository invoices=mock(InvoiceRepository.class);
    private final InvoiceSourceRepository sources=mock(InvoiceSourceRepository.class);
    private final InvoicePaymentRepository payments=mock(InvoicePaymentRepository.class);
    private final RentalRepository rentals=mock(RentalRepository.class);
    private final EmailService emails=mock(EmailService.class);
    private final ObjectMapper json=new ObjectMapper();
    private InvoiceService service(InvoiceTime time) {
        when(invoices.saveAndFlush(any())).thenAnswer(call -> { InvoiceRecord record=call.getArgument(0); record.setId(1L); return record; });
        BillingProfileService profiles=mock(BillingProfileService.class);when(profiles.prefill(any())).thenAnswer(call -> call.getArgument(0));
        return new InvoiceService(invoices,sources,payments,rentals,json,emails,time,mock(InvoiceCreditNoteRepository.class),mock(com.vehiclerental.mak.customers.repository.CustomerRepository.class),profiles);
    }
    private InvoiceRecord record(String status,String dueDate) {
        InvoiceRecord record=new InvoiceRecord(); record.setId(1L); record.setKind("Corporate"); record.setStatus(status);
        record.setTotalAmount(new BigDecimal("100")); record.setPaidAmount(BigDecimal.ZERO); record.setCreatedAt(Instant.parse("2026-01-01T20:00:00Z"));
        record.setPayload(json.writeValueAsString(Map.of("dueDate",dueDate,"paymentInstructions","Test-only cash payment instructions","issuer",Map.of("name","MAK","street","Main Road","city","Riyadh","country","Saudi Arabia"),"billTo",Map.of("name","Buyer","email","billing@example.com","street","Road","city","Riyadh","country","Saudi Arabia"))));
        when(invoices.lockById(1L)).thenReturn(Optional.of(record)); return record;
    }
    @Test void calendarAndTimestampDatesIgnoreServerZone() {
        TimeZone original=TimeZone.getDefault();
        try {
            for(String zone:List.of("America/Los_Angeles","Pacific/Kiritimati","Asia/Calcutta","UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertEquals(LocalDate.of(2027,1,1),time("2026-12-31T21:00:00Z").today());
                InvoiceTime clock=time("2026-12-31T21:00:00Z");
                assertEquals("2027-01-01",clock.day("2026-12-31T21:30:00Z"));
                assertEquals("2027-01-01",clock.day("2027-01-01T06:30:00+09:00"));
                assertEquals("2026-12-31",clock.day("2026-12-31"));
                assertEquals("2026-12-31",clock.day("2026-12-31T23:30"));
                assertEquals("",clock.day(""));
            }
        } finally { TimeZone.setDefault(original); }
    }
    @Test void issueAllowsKsaTodayAndRejectsYesterday() {
        InvoiceService service=service(time("2026-01-01T21:30:00Z"));
        InvoiceRecord record=record("Draft","2026-01-01");
        assertThrows(ApiException.class,()->service.issue(1));
        record=record("Draft","2026-01-02");
        assertEquals("Issued",service.issue(1).get("status"));
        assertEquals(Instant.parse("2026-01-01T21:30:00Z"),record.getIssuedAt());
    }
    @Test void paymentsAcceptKsaTodayButRejectTomorrow() {
        InvoiceService service=service(time("2026-01-01T21:30:00Z")); record("Issued","2026-01-05");
        Map<String,Object> payment=new HashMap<>(Map.of("kind","Payment","amount",10,"date","2026-01-03","method","Cash"));
        assertThrows(ApiException.class,()->service.payment(1,payment));
        payment.put("date","2026-01-02"); service.payment(1,payment);
        ArgumentCaptor<InvoicePayment> capture=ArgumentCaptor.forClass(InvoicePayment.class);
        verify(payments).save(capture.capture()); assertEquals(LocalDate.of(2026,1,2),capture.getValue().getPaidOn());
    }
    @Test void openingBalanceAndActualReturnUseKsaDates() {
        InvoiceService service=service(time("2026-01-01T21:30:00Z"));
        RentalRecord rental=new RentalRecord(); rental.setId(7L); rental.setKind("Individual"); rental.setStatus("Completed"); rental.setReference("BK-7");
        rental.setReceivedAmount(new BigDecimal("20")); rental.setRefundedAmount(BigDecimal.ZERO);
        rental.setReturnAt(LocalDateTime.parse("2026-01-01T20:00")); rental.setPayload("{\"actualReturnAt\":\"2026-01-01T21:15:00Z\"}");
        when(rentals.lockById(7L)).thenReturn(Optional.of(rental));
        Map<String,Object> draft=service.create(Map.of("kind","Individual","currency","SAR","sources",List.of(Map.of("kind","Individual","id",7)),"lines",List.of(Map.of("description","Rental","unit","Trip","quantity",1,"unitPrice",100,"discountPercent",0,"taxPercent",0))),false);
        ArgumentCaptor<InvoicePayment> capture=ArgumentCaptor.forClass(InvoicePayment.class); verify(payments).save(capture.capture());
        assertEquals(LocalDate.of(2026,1,2),capture.getValue().getPaidOn());
        assertEquals("2026-01-02",((Map<?,?>)((List<?>)draft.get("sources")).getFirst()).get("serviceDate"));
    }
    @Test void pdfIssueDateUsesKsaWhileDueDateRemainsDateOnly() throws Exception {
        Map<String,Object> invoice=new HashMap<>(); invoice.put("id",1); invoice.put("status","Issued"); invoice.put("invoiceNumber","INV-1");
        invoice.put("issuedAt","2026-12-31T21:30:00Z"); invoice.put("dueDate","2027-01-15");
        try(var doc=Loader.loadPDF(new InvoicePdfService(time("2026-12-31T21:30:00Z")).render(invoice))) {
            String text=new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Issue date (KSA)")); assertTrue(text.contains("2027-01-01")); assertTrue(text.contains("2027-01-15"));
        }
    }
}
