package com.vehiclerental.mak.invoices.service;
import com.vehiclerental.mak.common.exception.ApiException;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceTermsTests {
 @Test void parsesTermsWithoutGuessingCustomAgreements(){
  for(String text:List.of("Net 60"," net60 days ","60 days")){assertEquals(60,InvoiceTerms.days(text));assertEquals("Net 60",InvoiceTerms.normalize(text));}
  assertEquals(0,InvoiceTerms.days("Due on receipt"));assertEquals("Due on receipt",InvoiceTerms.normalize("Net 0"));
  for(String text:List.of("End of month","","Net 999","-30","Net 1.5"))assertNull(InvoiceTerms.days(text));
 }
 @Test void recalculatesAtIssueAndIgnoresClientCalculatedDate(){
  Map<String,Object> draft=new LinkedHashMap<>(Map.of("dueDateMode","Terms","paymentTerms","Net 60","dueDate","2000-01-01"));
  InvoiceTerms.draft(draft,"Corporate",null,LocalDate.of(2026,10,25));assertEquals("2026-12-24",draft.get("dueDate"));
  InvoiceTerms.issue(draft,LocalDate.of(2026,11,1));assertEquals("2026-12-31",draft.get("dueDate"));assertEquals("2026-11-01",draft.get("termsBaseDate"));
 }
 @Test void receiptLeapYearAndDefaultsUseCalendarDays(){
  Map<String,Object> draft=new LinkedHashMap<>(Map.of("dueDateMode","Terms","paymentTerms","Due on receipt"));
  InvoiceTerms.draft(draft,"Individual",null,LocalDate.of(2026,12,31));InvoiceTerms.issue(draft,LocalDate.of(2027,1,1));assertEquals("2027-01-01",draft.get("dueDate"));
  draft=new LinkedHashMap<>(Map.of("dueDateMode","Terms"));InvoiceTerms.draft(draft,"Corporate","Net 60",LocalDate.of(2026,11,1));assertEquals("2026-12-31",draft.get("dueDate"));
  draft=new LinkedHashMap<>(Map.of("dueDateMode","Terms","paymentTerms","Net 30"));InvoiceTerms.draft(draft,"Individual",null,LocalDate.of(2024,2,1));assertEquals("2024-03-02",draft.get("dueDate"));
 }
 @Test void overridesKeepDateButRequireReasonAndNonPastDate(){
  Map<String,Object> draft=new LinkedHashMap<>(Map.of("dueDateMode","Manual","paymentTerms","Net 60","dueDate","2026-11-05","dueDateOverrideReason","Agreed fixed settlement date"));
  InvoiceTerms.draft(draft,"Corporate",null,LocalDate.of(2026,10,25));InvoiceTerms.issue(draft,LocalDate.of(2026,11,1));assertEquals("2026-11-05",draft.get("dueDate"));
  draft.put("dueDateOverrideReason","");assertThrows(ApiException.class,()->InvoiceTerms.issue(draft,LocalDate.of(2026,11,1)));
  draft.put("dueDateOverrideReason","Agreed date");draft.put("dueDate","2026-10-31");assertThrows(ApiException.class,()->InvoiceTerms.issue(draft,LocalDate.of(2026,11,1)));
 }
 @Test void customTermsNeedReviewAndLegacyDatesAreRetained(){
  Map<String,Object> custom=new LinkedHashMap<>(Map.of("paymentTerms","End of month","dueDate","2026-10-31"));InvoiceTerms.draft(custom,"Corporate",null,LocalDate.of(2026,10,25));assertEquals("Manual",custom.get("dueDateMode"));assertThrows(ApiException.class,()->InvoiceTerms.issue(custom,LocalDate.of(2026,10,25)));
  Map<String,Object> legacy=new LinkedHashMap<>(Map.of("dueDate","2026-11-12"));InvoiceTerms.draft(legacy,"Individual",null,LocalDate.of(2026,10,25));InvoiceTerms.issue(legacy,LocalDate.of(2026,11,1));assertEquals("Manual",legacy.get("dueDateMode"));assertEquals("2026-11-12",legacy.get("dueDate"));assertTrue(legacy.get("dueDateOverrideReason").toString().contains("Legacy"));
 }
}
