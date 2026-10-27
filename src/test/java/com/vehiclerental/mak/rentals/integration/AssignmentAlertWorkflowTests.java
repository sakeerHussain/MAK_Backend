package com.vehiclerental.mak.rentals.integration;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.customers.entity.Customer;
import com.vehiclerental.mak.customers.repository.CustomerRepository;
import com.vehiclerental.mak.fleet.entity.Vehicle;
import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import com.vehiclerental.mak.rentals.service.RentalService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @Transactional
class AssignmentAlertWorkflowTests {
 @Autowired RentalService rentals;
 @Autowired CustomerRepository customers;
 @Autowired VehicleRepository vehicles;
 final LocalDateTime now=LocalDateTime.now(ZoneId.of("Asia/Riyadh")).withSecond(0).withNano(0);
 long id(Map<String,Object> row){return ((Number)row.get("id")).longValue();}
 Map<String,Object> request(String kind){
  var pickup=now.plusDays(5);var data=new LinkedHashMap<String,Object>();
  data.putAll(Map.of("bookingType","Advance","status","Confirmed","pickupAt",pickup.toString(),"returnAt",pickup.plusDays(1).toString(),"assignmentAlertAt",pickup.minusHours(4).toString(),"totalAmount",100,"pickupLocation","Khobar","destination","Riyadh","emailNotifications",false,"requestedType","Sedan"));
  if(kind.equals("Individual"))data.putAll(Map.of("customerName","Assignment QA","customerPhone","0500000001","customerEmail","assignment@example.invalid","driverMode","Self-drive"));
  else {
   var c=new Customer();c.setType("Corporate");c.setName("Assignment QA");c.setNameKey(UUID.randomUUID().toString());c.setPhone("0500000001");c.setEmail("qa@example.invalid");c.setStatus("Active");c.setCreatedAt(Instant.now());customers.saveAndFlush(c);
   data.putAll(Map.of("companyId",c.getId(),"contactName","QA","contactPhone","0500000001","contactEmail","qa@example.invalid","projectManager","QA","projectCode","QA","serviceMode","Self-drive","passengerCount",1,"unitPrice",100,"quantity",1));
   data.put("taxPercent",0);data.put("passengers",List.of(Map.of("name","QA","phone","0500000001")));data.put("stops",List.of(Map.of("location","Khobar","waitingMinutes",0),Map.of("location","Riyadh","waitingMinutes",0)));
  }
  return data;
 }
 Vehicle car(){var v=new Vehicle();String key=UUID.randomUUID().toString();v.setRegistration(key);v.setVehicleId(key);v.setMake("Toyota");v.setModel("Camry");v.setYear(2026);v.setType("Sedan");v.setSeats(5);v.setTransmission("Automatic");v.setFuelType("Petrol");v.setDailyRate(BigDecimal.valueOf(100));v.setRateCurrency("SAR");v.setOwnership("Owned");v.setListedForBooking(true);v.setStatus("Available");v.setOnSite(true);return vehicles.saveAndFlush(v);}
 @ParameterizedTest @ValueSource(strings={"Individual","Corporate"})
 void savesKsaTimeAndRejectsInvalidOrLateAlerts(String kind){
  var data=request(kind);data.put("assignmentAlertAt",now.plusDays(5).minusHours(7).atOffset(ZoneOffset.UTC).toString());
  var saved=rentals.create(kind,data);assertEquals(now.plusDays(5).minusHours(4).toString(),saved.get("assignmentAlertAt"));assertEquals("Asia/Riyadh",saved.get("timeZone"));assertNotNull(saved.get("assignmentAlertReviewedAt"));
  long id=id(saved);assertThrows(ApiException.class,()->rentals.update(kind,id,Map.of("assignmentAlertAt",now.plusDays(6).toString())));assertThrows(ApiException.class,()->rentals.update(kind,id,Map.of("assignmentAlertAt","")));assertThrows(ApiException.class,()->rentals.update(kind,id,Map.of("assignmentAlertAt","invalid")));
 }
 @ParameterizedTest @ValueSource(strings={"Individual","Corporate"})
 void pickupRescheduleNeedsExplicitReviewForExactlyTheNewTime(String kind){
  var saved=rentals.create(kind,request(kind));long id=id(saved);var pickup=now.plusDays(7);
  assertThrows(ApiException.class,()->rentals.update(kind,id,Map.of("pickupAt",pickup.toString(),"returnAt",pickup.plusDays(1).toString())));
  var change=new LinkedHashMap<String,Object>(Map.of("pickupAt",pickup.toString(),"returnAt",pickup.plusDays(1).toString(),"assignmentAlertAt",pickup.minusHours(4).toString(),"assignmentAlertReviewedForPickup",saved.get("pickupAt")));
  assertThrows(ApiException.class,()->rentals.update(kind,id,change));
  change.put("assignmentAlertReviewedForPickup",pickup.toString());change.put("assignmentAlertReviewedAt","forged");
  var rescheduled=rentals.update(kind,id,change);assertEquals(pickup.toString(),rescheduled.get("pickupAt"));assertEquals(pickup.toString(),rescheduled.get("assignmentAlertReviewedForPickup"));assertNotEquals("forged",rescheduled.get("assignmentAlertReviewedAt"));
  assertThrows(ApiException.class,()->rentals.update(kind,id,Map.of("pickupAt",pickup.plusHours(1).toString(),"assignmentAlertAt",pickup.minusHours(4).toString(),"assignmentAlertReviewedForPickup",pickup.toString())));
 }
 @Test void returnOnlyEditAndNotesDoNotRequireAnotherReview(){var b=rentals.create("Individual",request("Individual"));var saved=rentals.update("Individual",id(b),Map.of("notes","Reviewed","returnAt",now.plusDays(8).toString()));assertEquals(b.get("assignmentAlertAt"),saved.get("assignmentAlertAt"));assertEquals(b.get("assignmentAlertReviewedAt"),saved.get("assignmentAlertReviewedAt"));}
 @ParameterizedTest @ValueSource(strings={"Individual","Corporate"})
 void previousDayAssignmentsOpenAtTheDeadlineButHandoverStillWaits(String kind){
  var data=request(kind);data.put("assignmentAlertAt",now.minusMinutes(1).toString());long id=id(rentals.create(kind,data));var v=car();
  var assigned=rentals.assign(kind,id,Map.of("vehicleId",v.getId(),"handedOver",false));assertEquals(v.getId(),((Number)assigned.get("vehicleId")).longValue());assertEquals("Confirmed",assigned.get("status"));
  assertThrows(ApiException.class,()->rentals.assign(kind,id,Map.of("vehicleId",v.getId(),"handedOver",true)));
  assertThrows(ApiException.class,()->rentals.transition(kind,id,"Active",Map.of()));
 }
 @Test void futureDeadlineBlocksAssignmentUntilDue(){long id=id(rentals.create("Individual",request("Individual")));var v=car();assertThrows(ApiException.class,()->rentals.assign("Individual",id,Map.of("vehicleId",v.getId())));assertThrows(ApiException.class,()->rentals.update("Individual",id,Map.of("vehicleId",v.getId())));}
}
