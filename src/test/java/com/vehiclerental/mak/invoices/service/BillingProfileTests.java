package com.vehiclerental.mak.invoices.service;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.rentals.entity.RentalRecord;
import com.vehiclerental.mak.rentals.repository.RentalRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BillingProfileTests {
 @Autowired BillingProfileService profiles;@Autowired InvoiceService invoices;@Autowired RentalRepository rentals;@Autowired JdbcTemplate jdbc;
 private Map<String,Object> original;
 @BeforeEach void remember(){original=new LinkedHashMap<>(profiles.get());}
 @AfterEach void restore(){original.put("version",profiles.get().get("version"));profiles.save(original,42);}
 private Map<String,Object> complete(){return new LinkedHashMap<>(Map.of("version",profiles.get().get("version"),"issuer",Map.of("name","Test Registered Seller","street","Test Road","city","Riyadh","country","Saudi Arabia"),"account",Map.of("accountName","Test Account","bankName","Test Bank","accountNumber","TEST-ONLY-100"),"paymentContact","Test Accounts","paymentInstructions","Test-only instructions"));}
 private Map<String,Object> invoice(Map<String,Object> profile){
  RentalRecord rental=new RentalRecord();rental.setKind("Individual");rental.setReference("PROFILE-"+UUID.randomUUID());rental.setBookingType("Quick");rental.setStatus("Completed");rental.setPickupAt(LocalDateTime.of(2026,1,1,9,0));rental.setReturnAt(LocalDateTime.of(2026,1,2,9,0));rental.setTotalAmount(new BigDecimal("100"));rental.setReceivedAmount(BigDecimal.ZERO);rental.setRefundedAmount(BigDecimal.ZERO);rental.setCreatedAt(Instant.now());rental.setPayload("{}");rentals.saveAndFlush(rental);
  var data=new LinkedHashMap<String,Object>(Map.of("kind","Individual","currency","SAR","sources",List.of(Map.of("kind","Individual","id",rental.getId())),"lines",List.of(Map.of("description","Rental","unit","Trip","quantity",1,"unitPrice",100,"taxPercent",0)),"billTo",Map.of("name","Buyer","email","buyer@example.com"),"paymentTerms","Net 7","dueDateMode","Terms"));
  data.put("bookingChargeReview",Map.of("confirmed",true));if(profile!=null)data.put("billingProfileVersion",profile.get("version"));return data;
 }
 @Test void seededProfileIsCompleteAndVersionedUpdatesRejectLostChanges(){
  assertEquals("Mishary Al Khonaini International Contracting Est.",original.get("issuer") instanceof Map<?,?> seller?seller.get("name"):null);
  assertEquals(true,original.get("ready"));var request=complete();long old=((Number)request.get("version")).longValue();var saved=profiles.save(request,42);
  assertEquals(old+1,((Number)saved.get("version")).longValue());assertEquals(42L,((Number)saved.get("updatedBy")).longValue());
  assertThrows(ApiException.class,()->profiles.save(request,43));
  assertEquals(1,jdbc.queryForObject("select count(*) from billing_profile_versions where version=?",Integer.class,saved.get("version")));
 }
 @Test void newDraftsSnapshotSavedProfileAndExistingDraftsAndIssuedInvoicesStayUnchanged(){
  var a=profiles.save(complete(),42);var request=invoice(a);var draft=invoices.create(request,false);long id=((Number)draft.get("id")).longValue();
  assertEquals("Test Registered Seller",((Map<?,?>)draft.get("issuer")).get("name"));assertEquals("Test Bank",((Map<?,?>)draft.get("account")).get("bankName"));assertEquals("Test Accounts",draft.get("paymentContact"));
  var next=complete();next.put("issuer",Map.of("name","Different Test Seller","street","Other Road","city","Riyadh","country","Saudi Arabia"));profiles.save(next,43);
  assertEquals("Test Registered Seller",((Map<?,?>)invoices.get(id).get("issuer")).get("name"));
  var issued=invoices.issue(id);assertEquals("Test Registered Seller",((Map<?,?>)issued.get("issuer")).get("name"));
  var oldVersion=invoices.create(invoice(a),false);assertEquals("Test Registered Seller",((Map<?,?>)oldVersion.get("issuer")).get("name"));
  var fresh=invoices.create(invoice(null),false);assertEquals("Different Test Seller",((Map<?,?>)fresh.get("issuer")).get("name"));
  assertThrows(ApiException.class,()->invoices.update(id,new LinkedHashMap<>(issued),false));
 }
 @Test void incompleteConfigurationCanBeSavedButCannotIssueAndBadIbanIsRejected(){
  var incomplete=new LinkedHashMap<String,Object>(Map.of("version",profiles.get().get("version"),"issuer",Map.of("name","Test Seller"),"account",Map.of()));
  var saved=profiles.save(incomplete,42);assertEquals(false,saved.get("ready"));assertFalse(((List<?>)saved.get("missingFields")).isEmpty());
  var draft=invoices.create(invoice(null),false);long id=((Number)draft.get("id")).longValue();assertThrows(ApiException.class,()->invoices.issue(id));
  var invalid=complete();invalid.put("account",Map.of("iban","SA0000000000000000000000"));assertThrows(ApiException.class,()->profiles.save(invalid,42));
 }
}
