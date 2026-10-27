package com.vehiclerental.mak.rentals.integration;
import static org.junit.jupiter.api.Assertions.*;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.fleet.entity.Vehicle;
import com.vehiclerental.mak.fleet.repository.*;
import com.vehiclerental.mak.rentals.service.RentalService;
import com.vehiclerental.mak.RentalReadyFixture;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
@SpringBootTest
class RentalSafetyTests {
 @Autowired RentalService rentals;
 @Autowired com.vehiclerental.mak.rentals.repository.RentalRepository records;
 @Autowired VehicleRepository vehicles;
 @Autowired VehicleFileRepository files;
 Vehicle car(boolean onSite, boolean docs) {
  var v=new Vehicle();String key=UUID.randomUUID().toString();v.setRegistration(key);v.setVehicleId(key);v.setMake("Toyota");v.setModel("Camry");v.setYear(2026);v.setType("Sedan");v.setSeats(5);v.setTransmission("Automatic");v.setFuelType("Petrol");v.setDailyRate(BigDecimal.valueOf(100));v.setRateCurrency("SAR");v.setOwnership("Owned");v.setListedForBooking(true);v.setStatus("Available");v.setOnSite(onSite);vehicles.saveAndFlush(v);if(docs)RentalReadyFixture.documents(files,v);return v;
 }
 Map<String,Object> request(Vehicle v,String status){
  var now=LocalDateTime.now(ZoneId.of("Asia/Riyadh")).minusMinutes(1);
  return new LinkedHashMap<>(Map.ofEntries(Map.entry("bookingType","Quick"),Map.entry("status",status),Map.entry("customerName","Safety Test"),Map.entry("customerPhone","+966500000001"),Map.entry("customerEmail","safety@example.com"),Map.entry("emailNotifications",false),Map.entry("driverMode","Self-drive"),Map.entry("vehicleId",v.getId()),Map.entry("pickupAt",now.toString()),Map.entry("returnAt",now.plusDays(1).toString()),Map.entry("pickupLocation","Khobar"),Map.entry("destination","Dammam"),Map.entry("totalAmount",100)));
 }
 long id(Map<String,Object> row){return ((Number)row.get("id")).longValue();}
 @Test void registerDatesNeverReplaceTheFilesRequiredForHandover(){
  var v=car(true,false);var future=LocalDate.now(ZoneId.of("Asia/Riyadh")).plusYears(1);
  v.setRegistrationExpiryDate(future);v.setInsuranceExpiryDate(future);v.setInspectionExpiryDate(future);vehicles.saveAndFlush(v);
  assertThrows(ApiException.class,()->rentals.create("Individual",request(v,"Active")));
  RentalReadyFixture.documents(files,v);
  assertEquals("Active",rentals.create("Individual",request(v,"Active")).get("status"));
 }
 @Test void reservationAllowedButOffsiteHandoverRejectedAtomically(){
  var v=car(false,true);var b=rentals.create("Individual",request(v,"Confirmed"));long id=id(b);
  assertThrows(ApiException.class,()->rentals.assign("Individual",id,Map.of("vehicleId",v.getId(),"handedOver",true)));
  assertEquals("Confirmed",rentals.get("Individual",id).get("status"));
  assertThrows(ApiException.class,()->rentals.transition("Individual",id,"Active",Map.of()));
  assertEquals("Confirmed",rentals.get("Individual",id).get("status"));
 }
 @Test void missingExpiredAndUndatedDocumentsBlockAllActivationPaths(){
  var missing=car(true,false);assertThrows(ApiException.class,()->rentals.create("Individual",request(missing,"Active")));
  var expired=car(true,true);var f=files.findByVehicleIdOrderByIdAsc(expired.getId()).getFirst();f.setExpiryDate(LocalDate.now(ZoneId.of("Asia/Riyadh")).minusDays(1));files.saveAndFlush(f);
  assertThrows(ApiException.class,()->rentals.create("Individual",request(expired,"Active")));
  f.setExpiryDate(null);files.saveAndFlush(f);assertThrows(ApiException.class,()->rentals.create("Individual",request(expired,"Active")));
  var b=rentals.create("Individual",request(expired,"Confirmed"));
  assertThrows(ApiException.class,()->rentals.update("Individual",id(b),Map.of("status","Active")));
  assertEquals("Confirmed",rentals.get("Individual",id(b)).get("status"));
 }
 @Test void readyVehicleCanStartAndCancelledBookingOnlyAcceptsBoundedRefunds(){
  var data=request(car(true,true),"Active");data.put("receivedAmount",50);
  var active=rentals.create("Individual",data);assertEquals("Active",active.get("status"));
  var reserved=request(car(true,true),"Confirmed");reserved.put("receivedAmount",50);long id=id(rentals.create("Individual",reserved));rentals.transition("Individual",id,"Cancelled",Map.of());
  assertThrows(ApiException.class,()->rentals.payment(id,"Payment",BigDecimal.ONE));
  assertThrows(ApiException.class,()->rentals.payment(id,"Refund",BigDecimal.valueOf(51)));
  var refunded=rentals.payment(id,"Refund",BigDecimal.valueOf(50));assertEquals(0,new BigDecimal(refunded.get("refundedAmount").toString()).compareTo(BigDecimal.valueOf(50)));
  assertThrows(ApiException.class,()->rentals.payment(id,"Refund",BigDecimal.ONE));
 }
 @Test void offsetTimestampIsNormalizedToKsaWallTime(){
  var data=request(car(true,true),"Confirmed");data.put("pickupAt","2026-10-25T10:00:00Z");data.put("returnAt","2026-10-26T10:00:00Z");
  var saved=rentals.create("Individual",data);assertTrue(saved.get("pickupAt").toString().startsWith("2026-10-25T13:00"));
 }

 @Test void unlistedVehiclesRejectNewReservationsAndHandoversWithoutSideEffects(){
  var v=car(true,true);v.setListedForBooking(false);vehicles.saveAndFlush(v);long before=records.count();
  for(String status:List.of("Confirmed","Active")) {
   var error=assertThrows(ApiException.class,()->rentals.create("Individual",request(v,status)));
   assertTrue(error.getMessage().contains("not listed for booking"));
  }
  assertEquals(before,records.count());assertEquals("Available",vehicles.findById(v.getId()).orElseThrow().getStatus());
 }
 @Test void unlistedReassignmentFailsThroughBothUpdateAndAssignmentPaths(){
  var original=car(true,true);long booking=id(rentals.create("Individual",request(original,"Confirmed")));
  var hidden=car(true,true);hidden.setListedForBooking(false);vehicles.saveAndFlush(hidden);
  var error=assertThrows(ApiException.class,()->rentals.update("Individual",booking,Map.of("vehicleId",hidden.getId())));
  assertTrue(error.getMessage().contains("not listed for booking"));
  assertThrows(ApiException.class,()->rentals.assign("Individual",booking,Map.of("vehicleId",hidden.getId(),"handedOver",true)));
  assertEquals(original.getId(),((Number)rentals.get("Individual",booking).get("vehicleId")).longValue());
  assertEquals("Confirmed",rentals.get("Individual",booking).get("status"));
  assertEquals("Available",vehicles.findById(hidden.getId()).orElseThrow().getStatus());
 }
 @Test void unlistingDoesNotDisruptAnExistingAssignmentHandoverOrReturn(){
  var v=car(true,true);long booking=id(rentals.create("Individual",request(v,"Confirmed")));
  v.setListedForBooking(false);vehicles.saveAndFlush(v);
  assertEquals("Retain assignment",rentals.update("Individual",booking,Map.of("notes","Retain assignment")).get("notes"));
  assertEquals("Confirmed",rentals.assign("Individual",booking,Map.of("vehicleId",v.getId())).get("status"));
  assertEquals("Active",rentals.transition("Individual",booking,"Active",Map.of()).get("status"));
  assertEquals("Completed",rentals.transition("Individual",booking,"Completed",Map.of()).get("status"));
  assertFalse(vehicles.findById(v.getId()).orElseThrow().isListedForBooking());
 }
 @Test void unassignedAdvanceBookingCannotAcquireAnUnlistedVehicle(){
  var hidden=car(true,true);hidden.setListedForBooking(false);vehicles.saveAndFlush(hidden);
  var data=request(hidden,"Confirmed");data.put("bookingType","Advance");data.remove("vehicleId");
  var tomorrow=LocalDateTime.now(ZoneId.of("Asia/Riyadh")).plusDays(1);data.put("pickupAt",tomorrow.toString());data.put("returnAt",tomorrow.plusDays(1).toString());
  long booking=id(rentals.create("Individual",data));var record=records.findById(booking).orElseThrow();
  record.setPickupAt(LocalDateTime.now(ZoneId.of("Asia/Riyadh")).minusMinutes(1));records.saveAndFlush(record);
  var error=assertThrows(ApiException.class,()->rentals.assign("Individual",booking,Map.of("vehicleId",hidden.getId())));
  assertTrue(error.getMessage().contains("not listed for booking"));
  assertNull(rentals.get("Individual",booking).get("vehicleId"));
 }

}
