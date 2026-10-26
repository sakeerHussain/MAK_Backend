package com.vehiclerental.mak.invoices.service;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.rentals.entity.RentalRecord;
import com.vehiclerental.mak.rentals.repository.RentalRepository;
import com.vehiclerental.mak.rentals.service.RentalService;
import com.vehiclerental.mak.invoices.repository.InvoiceSourceRepository;
import com.vehiclerental.mak.dashboard.service.DashboardInsightsService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CreditNoteWorkflowTests {
 @Autowired InvoiceService invoices; @Autowired RentalRepository rentals; @Autowired RentalService rentalService;
 @Autowired InvoiceSourceRepository sources; @Autowired InvoicePdfService pdf; @Autowired DashboardInsightsService insights;
 private long rentalId;
 private List<Map<String,Object>> lines(){return List.of(Map.of("description","Rental","unit","Trip","quantity",1,"unitPrice",100,"taxPercent",15));}
 private Map<String,Object> create(BigDecimal paid,List<Map<String,Object>> lines,boolean issue){
  RentalRecord rental=new RentalRecord();rental.setKind("Individual");rental.setBookingType("Quick");rental.setStatus("Completed");rental.setReference("CREDIT-"+UUID.randomUUID());
  rental.setPickupAt(LocalDateTime.of(2026,1,1,9,0));rental.setReturnAt(LocalDateTime.of(2026,1,2,9,0));rental.setTotalAmount(lines.stream().map(line->{BigDecimal net=new BigDecimal(line.get("unitPrice").toString()).multiply(new BigDecimal(line.get("quantity").toString())).setScale(2,java.math.RoundingMode.HALF_UP);return net.add(net.multiply(new BigDecimal(line.getOrDefault("taxPercent",0).toString())).divide(new BigDecimal("100"),2,java.math.RoundingMode.HALF_UP));}).reduce(BigDecimal.ZERO,BigDecimal::add));rental.setReceivedAmount(paid);rental.setRefundedAmount(BigDecimal.ZERO);rental.setPayload("{}");rental.setCreatedAt(Instant.now());rentals.saveAndFlush(rental);rentalId=rental.getId();
  return invoices.create(Map.of("bookingChargeReview",Map.of("confirmed",true),"kind","Individual","currency","SAR","sources",List.of(Map.of("kind","Individual","id",rentalId)),"lines",lines,"issuer",Map.of("name","MAK","street","Road"),"billTo",Map.of("name","Guest","email","billing@example.com"),"dueDate",LocalDate.now(ZoneId.of("Asia/Riyadh")).plusDays(7).toString()),issue);
 }
 private long id(Map<String,Object> invoice){return ((Number)invoice.get("id")).longValue();}
 private Map<String,Object> credit(String key,Object amount){return Map.of("requestId",key,"reason","Rental service adjustment","lines",List.of(Map.of("lineIndex",0,"amount",amount)));}
 private Map<String,Object> payment(String kind,Object amount){return Map.of("kind",kind,"amount",amount,"date",LocalDate.now(ZoneId.of("Asia/Riyadh")).toString(),"method","Cash");}
 private BigDecimal number(Object value){return new BigDecimal(value.toString());}
 @SuppressWarnings("unchecked") private List<Map<String,Object>> notes(Map<String,Object> invoice){return (List<Map<String,Object>>)invoice.get("creditNotes");}
 @Test void paidPartialCreditThenRefundKeepsZeroDebtAndOriginalCharges(){
  Map<String,Object> invoice=create(new BigDecimal("115"),lines(),true);long id=id(invoice);Object originalLines=invoice.get("lines");
  assertThrows(ApiException.class,()->invoices.payment(id,payment("Refund",1)));
  Map<String,Object> credited=invoices.credit(id,credit(UUID.randomUUID().toString(),23),42);
  assertEquals(0,number(credited.get("creditedAmount")).compareTo(new BigDecimal("23")));
  assertEquals(originalLines,credited.get("lines"));assertEquals(0,number(notes(credited).getFirst().get("net")).compareTo(new BigDecimal("20")));
  assertEquals(0,number(notes(credited).getFirst().get("tax")).compareTo(new BigDecimal("3")));
  assertThrows(ApiException.class,()->invoices.payment(id,payment("Refund",24)));
  Map<String,Object> refunded=invoices.payment(id,payment("Refund",23));
  Map<?,?> summary=(Map<?,?>)refunded.get("bookingPaymentSummary");
  assertEquals(0,number(summary.get("invoicedAmount")).compareTo(new BigDecimal("92")));assertEquals(0,number(summary.get("refundedAmount")).compareTo(new BigDecimal("23")));
  assertTrue(sources.existsByIdRentalId(rentalId));assertThrows(ApiException.class,()->invoices.payment(id,payment("Payment",1)));
  invoices.credit(id,credit(UUID.randomUUID().toString(),92),42);Map<String,Object> settled=invoices.payment(id,payment("Refund",92));
  summary=(Map<?,?>)settled.get("bookingPaymentSummary");assertEquals(0,number(summary.get("invoicedAmount")).signum());assertEquals(0,number(summary.get("receivedAmount")).subtract(number(summary.get("refundedAmount"))).signum());
  assertEquals(0,number(rentalService.get("Individual",rentalId).get("invoicedAmount")).signum());
 }
 @Test void retriesAreIdempotentAndLimitsRejectOvercreditsAndDrafts(){
  long id=id(create(BigDecimal.ZERO,lines(),true));String key=UUID.randomUUID().toString();
  Map<String,Object> first=invoices.credit(id,credit(key,23),42);Map<String,Object> retry=invoices.credit(id,credit(key,23),42);
  assertEquals(1,notes(retry).size());assertEquals(notes(first).getFirst().get("number"),notes(retry).getFirst().get("number"));
  assertThrows(ApiException.class,()->invoices.credit(id,credit(key,24),42));assertThrows(ApiException.class,()->invoices.credit(id,credit(UUID.randomUUID().toString(),93),42));
  assertThrows(ApiException.class,()->invoices.credit(id,credit(UUID.randomUUID().toString(),0),42));
  assertThrows(ApiException.class,()->invoices.credit(id,Map.of("requestId",UUID.randomUUID().toString(),"reason","Valid reason","lines",List.of(Map.of("lineIndex",0,"amount",1),Map.of("lineIndex",0,"amount",1))),42));
  assertThrows(ApiException.class,()->invoices.payment(id,payment("Refund",1)));
  long draft=id(create(BigDecimal.ZERO,lines(),false));assertThrows(ApiException.class,()->invoices.credit(draft,credit(UUID.randomUUID().toString(),1),42));
 }
 @Test void splitCreditsPreserveVatCentsAndPdfLinks(){
  long id=id(create(BigDecimal.ZERO,List.of(Map.of("description","Rounding service","unit","Trip","quantity",1,"unitPrice",new BigDecimal("0.03"),"taxPercent",100)),true));
  Map<String,Object> credited=null;for(int i=0;i<6;i++)credited=invoices.credit(id,credit(UUID.randomUUID().toString(),new BigDecimal("0.01")),42);
  BigDecimal net=BigDecimal.ZERO,tax=BigDecimal.ZERO;for(var note:notes(credited)){net=net.add(number(note.get("net")));tax=tax.add(number(note.get("tax")));}
  assertEquals(0,net.compareTo(new BigDecimal("0.03")));assertEquals(0,tax.compareTo(new BigDecimal("0.03")));
  Map<String,Object> note=notes(credited).getFirst();long noteId=((Number)note.get("id")).longValue();
  assertThrows(ApiException.class,()->invoices.creditNote(id+999,noteId));
  try(var document=Loader.loadPDF(pdf.renderCreditNote(invoices.creditNote(id,noteId)))){String text=new PDFTextStripper().getText(document);assertTrue(text.contains("CREDIT NOTE"));assertTrue(text.contains(credited.get("invoiceNumber").toString()));assertTrue(text.contains(note.get("number").toString()));}catch(Exception e){throw new AssertionError(e);}
 }
 @Test void mixedTaxLinesAndUnpaidCreditAffectDashboardDebt(){
  Map<?,?> before=(Map<?,?>)insights.build(LocalDate.now(ZoneId.of("Asia/Riyadh")),7,Set.of("invoices.read")).get("financial");
  var originalLines=List.<Map<String,Object>>of(Map.of("description","Taxed rental","unit","Trip","quantity",1,"unitPrice",100,"taxPercent",15),Map.of("description","Zero rated","unit","Service","quantity",1,"unitPrice",50,"taxPercent",0));
  long id=id(create(new BigDecimal("30"),originalLines,true));
  Map<String,Object> invoice=invoices.credit(id,Map.of("requestId",UUID.randomUUID().toString(),"reason","Two service adjustments","lines",List.of(Map.of("lineIndex",0,"amount",new BigDecimal("57.50")),Map.of("lineIndex",1,"amount",25))),42);
  var note=notes(invoice).getFirst();assertEquals(0,number(note.get("net")).compareTo(new BigDecimal("75")));assertEquals(0,number(note.get("tax")).compareTo(new BigDecimal("7.50")));
  assertThrows(ApiException.class,()->invoices.payment(id,payment("Refund",1)));
  Map<?,?> after=(Map<?,?>)insights.build(LocalDate.now(ZoneId.of("Asia/Riyadh")),7,Set.of("invoices.read")).get("financial");
  assertEquals(0,number(after.get("outstandingBalance")).subtract(number(before.get("outstandingBalance"))).compareTo(new BigDecimal("52.50")));
  assertEquals(0,number(after.get("issuedValue")).subtract(number(before.get("issuedValue"))).compareTo(new BigDecimal("82.50")));
 }
}
