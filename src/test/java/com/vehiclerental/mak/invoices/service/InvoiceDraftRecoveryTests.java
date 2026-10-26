package com.vehiclerental.mak.invoices.service;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.customers.dto.request.CustomerRequest;
import com.vehiclerental.mak.customers.service.CustomerService;
import com.vehiclerental.mak.invoices.repository.*;
import com.vehiclerental.mak.rentals.entity.RentalRecord;
import com.vehiclerental.mak.rentals.repository.RentalRepository;
import com.vehiclerental.mak.rentals.service.RentalService;
import com.vehiclerental.mak.notifications.email.repository.EmailRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class InvoiceDraftRecoveryTests {
    @Autowired InvoiceService invoices;
    @Autowired InvoiceRepository records;
    @Autowired InvoiceSourceRepository sources;
    @Autowired InvoicePaymentRepository payments;
    @Autowired RentalRepository rentals;
    @Autowired RentalService rentalService;
    @Autowired CustomerService customers;
    @Autowired EmailRepository emails;
    private RentalRecord rental(String kind,Long company) {
        RentalRecord rental=new RentalRecord(); rental.setKind(kind); rental.setReference("RECOVERY-"+UUID.randomUUID());
        rental.setBookingType("Quick"); rental.setStatus("Completed"); rental.setCustomerId(company);
        rental.setPickupAt(LocalDateTime.of(2026,1,1,9,0)); rental.setReturnAt(LocalDateTime.of(2026,1,2,9,0));
        rental.setTotalAmount(new BigDecimal("100")); rental.setReceivedAmount(new BigDecimal("30")); rental.setRefundedAmount(new BigDecimal("5"));
        rental.setPayload("{}"); rental.setCreatedAt(Instant.now()); return rentals.saveAndFlush(rental);
    }
    private Map<String,Object> draft(String kind,RentalRecord... linked) {
        return Map.of("bookingChargeReview",Map.of("confirmed",true),"kind",kind,"currency","SAR","sources",Arrays.stream(linked).map(r -> Map.of("kind",kind,"id",r.getId())).toList(),
            "lines",List.of(Map.of("description","Rental","unit","Trip","quantity",linked.length,"unitPrice",100,"taxPercent",0)),
            "issuer",Map.of("name","MAK","street","Main Road"),"billTo",Map.of("name","Buyer","email","billing@example.com","street","Road","city","Riyadh","country","Saudi Arabia"),
            "dueDate",LocalDate.now(ZoneId.of("Asia/Riyadh")).plusDays(30).toString());
    }
    private long id(Map<String,Object> invoice) { return ((Number)invoice.get("id")).longValue(); }
    @Test void discardReleasesIndividualAndPreservesMoneyAndHistory() {
        RentalRecord rental=rental("Individual",null); Map<String,Object> request=draft("Individual",rental);
        long id=id(invoices.create(request,false));
        assertTrue(sources.existsByIdRentalId(rental.getId()));
        assertThrows(ApiException.class,()->invoices.create(request,false));
        int paymentCount=payments.findByInvoiceIdOrderByIdAsc(id).size();
        Map<String,Object> discarded=invoices.discard(id,42L);
        assertEquals("Discarded",discarded.get("status")); assertEquals(42L,((Number)discarded.get("discardedBy")).longValue()); assertNotNull(discarded.get("discardedAt"));
        assertEquals(1,((List<?>)discarded.get("sources")).size()); assertFalse(sources.existsByIdRentalId(rental.getId()));
        assertEquals(paymentCount,payments.findByInvoiceIdOrderByIdAsc(id).size());
        assertTrue(invoices.list().stream().noneMatch(i->id(i)==id)); assertTrue(records.existsById(id));
        RentalRecord unchanged=rentals.findById(rental.getId()).orElseThrow();
        assertEquals(0,unchanged.getReceivedAmount().compareTo(new BigDecimal("30"))); assertEquals(0,unchanged.getRefundedAmount().compareTo(new BigDecimal("5")));
        assertFalse(rentalService.get("Individual",rental.getId()).containsKey("invoiceId"));
        long replacement=id(invoices.create(request,false));
        assertEquals(0,new BigDecimal(invoices.get(replacement).get("payments") instanceof List<?> list ? ((Map<?,?>)list.getFirst()).get("amount").toString() : "-1").compareTo(new BigDecimal("25")));
        assertEquals(discarded.get("discardedAt"),invoices.discard(id,43L).get("discardedAt"));
        assertEquals(replacement,records.findForRental(rental.getId()).orElseThrow().getId());
        assertThrows(ApiException.class,()->invoices.issue(id));
        assertThrows(ApiException.class,()->invoices.update(id,request,false));
        assertThrows(ApiException.class,()->invoices.payment(id,Map.of("kind","Payment","amount",1)));
        assertTrue(emails.findByRecordKindAndRecordIdOrderByIdDesc("Invoice",id).isEmpty());
    }
    @Test void discardedConsolidationCanBeSplitIntoSeparateInvoices() {
        long company=customers.create(new CustomerRequest("Corporate","Recovery "+UUID.randomUUID(),"+966500000005","billing@example.com","Riyadh","Active","","","Accounts","Main Road","Riyadh","Saudi Arabia","Net 30",BigDecimal.ZERO,0,15)).id();
        RentalRecord first=rental("Corporate",company), second=rental("Corporate",company);
        long combined=id(invoices.create(draft("Corporate",first,second),false)); invoices.discard(combined,42L);
        assertFalse(sources.existsByIdRentalId(first.getId())); assertFalse(sources.existsByIdRentalId(second.getId()));
        long a=id(invoices.create(draft("Corporate",first),false)), b=id(invoices.create(draft("Corporate",second),false));
        assertNotEquals(a,b); assertEquals(a,records.findForRental(first.getId()).orElseThrow().getId()); assertEquals(b,records.findForRental(second.getId()).orElseThrow().getId());
    }
    @Test void issuedInvoiceCannotBeDiscardedAndKeepsReservations() {
        RentalRecord rental=rental("Individual",null); long id=id(invoices.create(draft("Individual",rental),true));
        int count=payments.findByInvoiceIdOrderByIdAsc(id).size();
        assertThrows(ApiException.class,()->invoices.discard(id,42L));
        assertEquals("Issued",invoices.get(id).get("status")); assertTrue(sources.existsByIdRentalId(rental.getId()));
        assertEquals(count,payments.findByInvoiceIdOrderByIdAsc(id).size());
    }
}
