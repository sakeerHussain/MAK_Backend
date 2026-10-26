package com.vehiclerental.mak.invoices.service;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.rentals.entity.RentalRecord;
import com.vehiclerental.mak.rentals.repository.RentalRepository;
import com.vehiclerental.mak.invoices.repository.InvoiceRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
class InvoiceBookingChargesTests {
 @Autowired InvoiceService invoices;@Autowired RentalRepository rentals;@Autowired InvoiceRepository records;@Autowired ObjectMapper json;
 private RentalRecord rental(){var r=new RentalRecord();r.setKind("Individual");r.setBookingType("Quick");r.setStatus("Completed");r.setReference("CHARGES-"+UUID.randomUUID());r.setPickupAt(LocalDateTime.of(2026,1,1,9,0));r.setReturnAt(LocalDateTime.of(2026,1,3,9,0));r.setCreatedAt(Instant.now());r.setTotalAmount(new BigDecimal("299"));r.setReceivedAmount(new BigDecimal("30"));r.setRefundedAmount(BigDecimal.ZERO);r.setPayload("{\"unitPrice\":100,\"rateUnit\":\"Day\",\"extraKm\":30}");return rentals.saveAndFlush(r);}
 private List<Map<String,Object>> lines(RentalRecord r){return List.of(Map.of("description","Vehicle rental","sourceRef",r.getReference(),"quantity",2,"unit","Day","unitPrice",100,"taxPercent",15),Map.of("description","Extra kilometres","sourceRef",r.getReference(),"quantity",30,"unit","km","unitPrice",2,"taxPercent",15));}
 private Map<String,Object> request(RentalRecord r){var draft=new LinkedHashMap<String,Object>();draft.put("kind","Individual");draft.put("currency","SAR");draft.put("sources",List.of(Map.of("kind","Individual","id",r.getId())));draft.put("lines",lines(r));draft.put("billTo",Map.of("name","Charge Test Guest","email","guest@example.com"));return draft;}
 private long id(Map<String,Object> invoice){return ((Number)invoice.get("id")).longValue();}
 @Test void savedDraftRequiresExplicitReviewAndIssuesWithRentalExtrasAndTax(){var r=rental();var draft=invoices.create(request(r),false);long id=id(draft);assertEquals(true,((Map<?,?>)draft.get("bookingChargeReview")).get("required"));assertThrows(ApiException.class,()->invoices.issue(id));var reviewed=new LinkedHashMap<String,Object>(draft);reviewed.put("bookingChargeReview",Map.of("confirmed",true,"bookingTotal",1));var issued=invoices.update(id,reviewed,true);assertEquals("Issued",issued.get("status"));assertEquals(2,((List<?>)issued.get("lines")).size());assertEquals(0,new BigDecimal(((Map<?,?>)issued.get("bookingChargeReview")).get("bookingTotal").toString()).compareTo(new BigDecimal("299")));assertEquals(0,records.findById(id).orElseThrow().getTotalAmount().compareTo(new BigDecimal("299")));}
 @Test void omittedReviewAndMismatchedTotalCannotBypassIssuingViaApi(){var r=rental();assertThrows(ApiException.class,()->invoices.create(request(r),true));var draft=request(r);draft.put("bookingChargeReview",Map.of("confirmed",true,"required",false));draft.put("lines",List.of(Map.of("description","Rental","quantity",1,"unit","Trip","unitPrice",299,"taxPercent",15)));assertThrows(ApiException.class,()->invoices.create(draft,true));assertTrue(records.findForRental(r.getId()).isEmpty());}
 @Test void unknownTaxCanBeSavedButCannotBeIssuedEvenWhenConfirmed(){var r=rental();var draft=request(r);draft.put("lines",List.of(Map.of("description","Rental","quantity",1,"unit","Trip","unitPrice",299,"taxPercent","")));draft.put("bookingChargeReview",Map.of("confirmed",true));long id=id(invoices.create(draft,false));assertThrows(ApiException.class,()->invoices.issue(id));}
 @Test void oldSavedDraftWithoutReviewIsProtectedAndExistingIssuedRecordsStayUnchanged(){var r=rental();var draft=invoices.create(request(r),false);long id=id(draft);var record=records.findById(id).orElseThrow();var payload=json.readValue(record.getPayload(),new tools.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});payload.remove("bookingChargeReview");record.setPayload(json.writeValueAsString(payload));records.saveAndFlush(record);assertEquals(true,((Map<?,?>)invoices.get(id).get("bookingChargeReview")).get("required"));assertThrows(ApiException.class,()->invoices.issue(id));payload.put("bookingChargeReview",Map.of("confirmed",true));invoices.update(id,payload,true);Object snapshot=records.findById(id).orElseThrow().getPayload();invoices.get(id);assertEquals(snapshot,records.findById(id).orElseThrow().getPayload());}
}
