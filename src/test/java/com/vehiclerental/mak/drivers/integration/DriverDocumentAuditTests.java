package com.vehiclerental.mak.drivers.integration;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.drivers.entity.Driver;
import com.vehiclerental.mak.drivers.entity.DriverFile;
import com.vehiclerental.mak.drivers.repository.DriverRepository;
import com.vehiclerental.mak.drivers.repository.DriverFileRepository;
import com.vehiclerental.mak.drivers.service.DriverFileService;
import com.vehiclerental.mak.drivers.service.DriverCompliance;
import com.vehiclerental.mak.drivers.dto.request.CertificationRequest;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DriverDocumentAuditTests {
 @Autowired DriverRepository drivers;
 @Autowired DriverFileRepository records;
 @Autowired DriverFileService files;
 @Autowired com.vehiclerental.mak.dashboard.service.DashboardInsightsService dashboard;
 final LocalDate today=LocalDate.now(ZoneId.of("Asia/Riyadh"));
 Driver driver() {
  var d=new Driver();String key=UUID.randomUUID().toString();d.setEmployeeId(key);d.setName("Document Audit");d.setPhone("0500000001");d.setPhoneKey(key);d.setCategory("Chauffeur");d.setDesignation("Driver");d.setEmploymentType("Full-time");d.setStatus("Active");d.setDailyRate(BigDecimal.TEN);return drivers.saveAndFlush(d);
 }
 MockMultipartFile pdf(){return new MockMultipartFile("file","audit.pdf","application/pdf","%PDF-1.4 audit fixture".getBytes());}
 void ready(Driver d){for(var type:List.of("Iqama","License copy"))files.uploadDocument(d.getId(),type,today.plusYears(2),pdf());}
 String issue(Driver d){return DriverCompliance.bookingIssue(drivers.findById(d.getId()).orElseThrow(),records.findByDriverIdOrderByIdAsc(d.getId()),today.plusDays(2));}
 @Test void rejectsUnsupportedEmptyOversizedSpoofedFilesAndUndatedRequiredCopies() {
  var d=driver();
  assertThrows(ApiException.class,()->files.uploadDocument(d.getId(),"Unknown",today,pdf()));
  for(var type:List.of("Iqama","License copy"))assertThrows(ApiException.class,()->files.uploadDocument(d.getId(),type,null,pdf()));
  assertThrows(ApiException.class,()->files.uploadDocument(d.getId(),"Iqama",today,new MockMultipartFile("file","empty.pdf","application/pdf",new byte[0])));
  assertThrows(ApiException.class,()->files.uploadDocument(d.getId(),"Iqama",today,new MockMultipartFile("file","large.pdf","application/pdf",new byte[5*1024*1024+1])));
  assertThrows(ApiException.class,()->files.uploadDocument(d.getId(),"Iqama",today,new MockMultipartFile("file","fake.pdf","application/pdf","<html>not a PDF</html>".getBytes())));
  assertThrows(ApiException.class,()->files.uploadPhoto(d.getId(),pdf()));
  assertTrue(records.findByDriverIdOrderByIdAsc(d.getId()).isEmpty());
 }
 @Test void renewedReplacementDownloadsAndRequiredRemovalBlocksAssignment() {
  var d=driver();ready(d);assertNull(issue(d));
  var old=records.findByDriverIdAndKindAndDocumentType(d.getId(),"DOCUMENT","License copy").orElseThrow();
  var replacement=files.uploadDocument(d.getId(),"License copy",today.plusYears(3),pdf());
  assertEquals(2,records.findByDriverIdOrderByIdAsc(d.getId()).size());
  assertFalse(records.existsById(old.getId()));assertTrue(files.download(d.getId(),replacement.id()).resource().exists());
  files.delete(d.getId(),replacement.id());assertTrue(issue(d).contains("License copy"));
 }
 @Test void certificatesValidateDatesAndLimitButAreNotPartOfBookingCompliance() {
  var d=driver();ready(d);
  assertThrows(ApiException.class,()->files.addCertification(d.getId(),new CertificationRequest("",null,null),null));
  assertThrows(ApiException.class,()->files.addCertification(d.getId(),new CertificationRequest("Invalid",today,today.minusDays(1)),null));
  for(int i=0;i<10;i++)files.addCertification(d.getId(),new CertificationRequest("Expired "+i,today.minusYears(2),today.minusYears(1)),null);
  assertThrows(ApiException.class,()->files.addCertification(d.getId(),new CertificationRequest("Eleventh",null,null),null));
  assertNull(issue(d)); // Documents are complete although every optional certification is expired.
 }
 @Test void undatedOptionalCopyRetainsKnownExpiryAsNonblockingWarning() {
  var d=driver();ready(d);d=drivers.findById(d.getId()).orElseThrow();d.setPassportExpiry(today.minusDays(1));drivers.saveAndFlush(d);
  assertNull(issue(d));
  files.uploadDocument(d.getId(),"Passport copy",null,pdf());
  assertEquals(today.minusDays(1),drivers.findById(d.getId()).orElseThrow().getPassportExpiry());
  assertEquals(today.minusDays(1),records.findByDriverIdAndKindAndDocumentType(d.getId(),"DOCUMENT","Passport copy").orElseThrow().getExpiryDate());
  assertNull(issue(d));
 }
 @Test void dashboardUsesRenewedCopyInsteadOfLegacyProfileDate() {
  var d=driver();ready(d);
  int before=((Number)((Map<?,?>)dashboard.build(today,30,Set.of("drivers.read")).get("alerts")).get("expired")).intValue();
  d=drivers.findById(d.getId()).orElseThrow();d.setIqamaExpiry(today.minusDays(1));drivers.saveAndFlush(d);
  assertNull(issue(d));
  int after=((Number)((Map<?,?>)dashboard.build(today,30,Set.of("drivers.read")).get("alerts")).get("expired")).intValue();
  assertEquals(before,after);
  // One authoritative date yields one alert, with the same 30-day warning boundary.
  long dueBefore=((Number)((Map<?,?>)dashboard.build(today,30,Set.of("drivers.read")).get("alerts")).get("dueSoon")).longValue();
  files.uploadDocument(d.getId(),"Iqama",today.plusDays(30),pdf());
  long dueAfter=((Number)((Map<?,?>)dashboard.build(today,30,Set.of("drivers.read")).get("alerts")).get("dueSoon")).longValue();
  assertEquals(dueBefore+1,dueAfter);
  files.uploadDocument(d.getId(),"Iqama",today.plusDays(31),pdf());
  assertEquals(dueBefore,((Number)((Map<?,?>)dashboard.build(today,30,Set.of("drivers.read")).get("alerts")).get("dueSoon")).longValue());
 }
 @Test void unknownPassportAndContractExpiryRejectedAndGenericReplacementKeepsKnownDate() {
  var d=driver();ready(d);
  for(var type:List.of("Passport copy","Contract copy"))assertThrows(ApiException.class,()->files.uploadDocument(d.getId(),type,null,pdf()));
  files.uploadDocument(d.getId(),"PCC",today.minusDays(1),pdf());
  var replacement=files.uploadDocument(d.getId(),"PCC",null,pdf());assertEquals(today.minusDays(1),replacement.expiryDate());assertNull(issue(d));
 }
 @Test void legacyUndatedPassportIsNonblockingAndRenewalClearsWarning() {
  var d=driver();ready(d);
  var legacy=new DriverFile();legacy.setDriver(d);legacy.setKind("DOCUMENT");legacy.setDocumentType("Passport copy");legacy.setStorageKey(UUID.randomUUID().toString());legacy.setOriginalName("passport.pdf");legacy.setContentType("application/pdf");legacy.setSizeBytes(12L);legacy.setCreatedAt(Instant.now());records.saveAndFlush(legacy);
  assertNull(issue(d));
  d=drivers.findById(d.getId()).orElseThrow();d.setPassportExpiry(today.minusDays(1));drivers.saveAndFlush(d);assertNull(issue(d));
  int before=((Number)((Map<?,?>)dashboard.build(today,30,Set.of("drivers.read")).get("alerts")).get("total")).intValue();
  files.uploadDocument(d.getId(),"Passport copy",today.plusYears(2),pdf());assertNull(issue(d));
  int after=((Number)((Map<?,?>)dashboard.build(today,30,Set.of("drivers.read")).get("alerts")).get("total")).intValue();assertEquals(before-1,after);
 }

 @Test @org.springframework.transaction.annotation.Transactional void optionalExpiryIsReviewWhileIqamaStillBlocksBooking() {
  var d=driver();ready(d);d=drivers.findById(d.getId()).orElseThrow();d.setName("Optional Warning "+d.getId());d.setInsuranceExpiry(today.minusDays(1));drivers.saveAndFlush(d);
  assertNull(issue(d));
  var alerts=(Map<?,?>)dashboard.build(today,30,Set.of("drivers.read")).get("alerts");
  var items=(java.util.List<?>)alerts.get("items");
  // Isolate this driver's warning from other optional fixtures by making it the only active driver temporarily.
  for(var other:drivers.findByArchivedFalseAndStatus("Active"))if(!other.getId().equals(d.getId())) {other.setStatus("Inactive");drivers.saveAndFlush(other);}
  alerts=(Map<?,?>)dashboard.build(today,30,Set.of("drivers.read")).get("alerts");
  items=(java.util.List<?>)alerts.get("items");assertEquals(1,items.size());var warning=(Map<?,?>)items.getFirst();assertEquals("Review",warning.get("severity"));assertTrue(warning.get("detail").toString().contains("does not block booking"));
  files.uploadDocument(d.getId(),"Iqama",today.minusDays(1),pdf());assertTrue(issue(d).contains("Iqama"));
 }
}
