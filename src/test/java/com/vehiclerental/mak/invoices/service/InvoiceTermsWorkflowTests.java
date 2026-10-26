package com.vehiclerental.mak.invoices.service;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.customers.dto.request.CustomerRequest;
import com.vehiclerental.mak.customers.service.CustomerService;
import com.vehiclerental.mak.rentals.repository.RentalRepository;
import com.vehiclerental.mak.rentals.entity.RentalRecord;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class InvoiceTermsWorkflowTests {
 @Autowired InvoiceService invoices;@Autowired RentalRepository rentals;@Autowired CustomerService customers;
 private CustomerRequest company(String name,String terms){return new CustomerRequest("Corporate",name,"+966500000008","billing@example.com","Riyadh","Active","","","Accounts","Main Road","Riyadh","Saudi Arabia",terms,BigDecimal.ZERO,0,15);}
 private Map<String,Object> request(long company){
  RentalRecord rental=new RentalRecord();rental.setKind("Corporate");rental.setBookingType("Quick");rental.setStatus("Completed");rental.setReference("TERMS-"+UUID.randomUUID());rental.setCustomerId(company);
  rental.setPickupAt(LocalDateTime.of(2026,1,1,9,0));rental.setReturnAt(LocalDateTime.of(2026,1,2,9,0));rental.setTotalAmount(new BigDecimal("100"));rental.setReceivedAmount(BigDecimal.ZERO);rental.setRefundedAmount(BigDecimal.ZERO);rental.setPayload("{}");rental.setCreatedAt(Instant.now());rentals.saveAndFlush(rental);
  return new LinkedHashMap<>(Map.of("kind","Corporate","currency","SAR","dueDateMode","Terms","dueDate","2000-01-01","sources",List.of(Map.of("kind","Corporate","id",rental.getId())),"lines",List.of(Map.of("description","Rental","unit","Trip","quantity",1,"unitPrice",100)),"issuer",Map.of("name","MAK","street","Road"),"billTo",Map.of("name","Buyer","email","billing@example.com","street","Road","city","Riyadh","country","Saudi Arabia")));
 }
 @Test void companyTermsAreSnapshottedAndIssuedScheduleIsLocked(){
  String name="Terms "+UUID.randomUUID();long company=customers.create(company(name,"Net 60")).id();
  Map<String,Object> draft=invoices.create(request(company),false);long id=((Number)draft.get("id")).longValue();
  assertEquals("Net 60",draft.get("paymentTerms"));assertEquals(LocalDate.now(ZoneId.of("Asia/Riyadh")).plusDays(60).toString(),draft.get("dueDate"));
  customers.update(company,company(name,"Net 7"));Map<String,Object> issued=invoices.issue(id);
  assertEquals("Net 60",issued.get("paymentTerms"));LocalDate issueDay=Instant.parse(issued.get("issuedAt").toString()).atZone(ZoneId.of("Asia/Riyadh")).toLocalDate();assertEquals(issueDay.plusDays(60).toString(),issued.get("dueDate"));
  assertThrows(ApiException.class,()->invoices.update(id,new LinkedHashMap<>(issued),false));assertEquals(issued.get("dueDate"),invoices.get(id).get("dueDate"));
 }
 @Test void customTermsNeedManualDateAndReasonBeforeIssue(){
  long company=customers.create(company("Manual "+UUID.randomUUID(),"End of month")).id();Map<String,Object> request=request(company);request.put("dueDateMode","Manual");request.put("dueDate",LocalDate.now(ZoneId.of("Asia/Riyadh")).plusDays(15).toString());
  Map<String,Object> draft=invoices.create(request,false);long id=((Number)draft.get("id")).longValue();assertThrows(ApiException.class,()->invoices.issue(id));
  request.putAll(draft);
  request.put("dueDateOverrideReason","Settlement date agreed in contract");Map<String,Object> issued=invoices.update(id,request,true);
  assertEquals("Manual",issued.get("dueDateMode"));assertEquals("End of month",issued.get("paymentTerms"));assertEquals(request.get("dueDate"),issued.get("dueDate"));assertEquals(request.get("dueDateOverrideReason"),issued.get("dueDateOverrideReason"));
 }
}
