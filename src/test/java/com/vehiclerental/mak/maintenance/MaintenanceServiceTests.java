package com.vehiclerental.mak.maintenance;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import com.vehiclerental.mak.common.ApiException;
import com.vehiclerental.mak.fleet.*;
import com.vehiclerental.mak.rentals.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class MaintenanceServiceTests {
 @Autowired MaintenanceService maintenance;@Autowired MaintenanceRepository jobs;@Autowired VehicleRepository vehicles;@Autowired VehicleService fleet;@Autowired RentalService rentals;@Autowired WebApplicationContext context;@Autowired ObjectMapper json;
 private Vehicle car(String status){String suffix=UUID.randomUUID().toString().substring(0,8);var v=new Vehicle();v.setRegistration("M-"+suffix);v.setVehicleId("V-"+suffix);v.setMake("Toyota");v.setModel("Camry");v.setYear(2025);v.setType("Sedan");v.setSeats(5);v.setTransmission("Automatic");v.setFuelType("Petrol");v.setDailyRate(new BigDecimal("200"));v.setRateCurrency("SAR");v.setOwnership("Owned");v.setStatus(status);v.setCurrentOdometerKm(12000L);v.setLastOilKm(10000L);v.setLastTyreKm(9000L);return vehicles.saveAndFlush(v);}
 private Map<String,Object> job(long vehicleId,String status){return new LinkedHashMap<>(Map.ofEntries(Map.entry("vehicleId",vehicleId),Map.entry("workType","Maintenance Log"),Map.entry("workItem","Oil & Filter Change"),Map.entry("status",status),Map.entry("serviceDate",LocalDate.now().toString()),Map.entry("odometerKm",13000),Map.entry("nextServiceOdometerKm",18000),Map.entry("notes","Workshop visit"),Map.entry("completedTasks",status.equals("Completed")?List.of("Oil"):List.of()),Map.entry("expenseLines",List.of(Map.of("id","line-1","category","Spare Parts","amount","75.25","description","Oil filter"),Map.of("id","line-2","category","Workshop Labor","amount","100.50","description","Labor")))));}
 private Map<String,Object> save(Long id,Map<String,Object> data){return maintenance.save(id,data,new LinkedMultiValueMap<>());}
 @Test void multipleOpenJobsHoldVehicleUntilLastJobClosesAndResetOnlyCheckedCounters(){
  var vehicle=car("Available");var a=save(null,job(vehicle.getId(),"Open"));var b=save(null,job(vehicle.getId(),"In Progress"));
  assertEquals("Under Maintenance",vehicles.findById(vehicle.getId()).orElseThrow().getStatus());assertThrows(ApiException.class,()->fleet.updateStatus(vehicle.getId(),new VehicleStatusRequest("Available")));
  long aId=((Number)a.get("id")).longValue();var completed=job(vehicle.getId(),"Completed");save(aId,completed);
  var updated=vehicles.findById(vehicle.getId()).orElseThrow();assertEquals("Under Maintenance",updated.getStatus());assertEquals(13000,updated.getLastOilKm());assertEquals(5000,updated.getOilIntervalKm());assertEquals(9000,updated.getLastTyreKm());assertEquals(13000,updated.getCurrentOdometerKm());
  assertThrows(ApiException.class,()->save(aId,completed));save(((Number)b.get("id")).longValue(),job(vehicle.getId(),"Cancelled"));assertEquals("Available",vehicles.findById(vehicle.getId()).orElseThrow().getStatus());assertEquals(new BigDecimal("175.75"),maintenance.get(aId).get("totalAmount"));
 }
 @Test void restoresInactiveCarsAndMovesOpenJobsSafely(){
  var a=car("Inactive");var b=car("Available");var open=save(null,job(a.getId(),"Open"));long id=((Number)open.get("id")).longValue();save(id,job(b.getId(),"Open"));assertEquals("Inactive",vehicles.findById(a.getId()).orElseThrow().getStatus());assertEquals("Under Maintenance",vehicles.findById(b.getId()).orElseThrow().getStatus());save(id,job(b.getId(),"Cancelled"));assertEquals("Available",vehicles.findById(b.getId()).orElseThrow().getStatus());
 }
 @Test void completedReadingsNeverReduceOdometerOrResetUnselectedTasks(){
  var vehicle=car("Available");var data=job(vehicle.getId(),"Completed");data.put("odometerKm",9000);data.put("nextServiceOdometerKm",14000);save(null,data);var updated=vehicles.findById(vehicle.getId()).orElseThrow();assertEquals(12000,updated.getCurrentOdometerKm());assertEquals(10000,updated.getLastOilKm());assertNull(updated.getLastServiceKm());
 }
 @Test void rejectsActiveRentalsAndBookingAssignmentToMaintenanceVehicle(){
  var vehicle=car("Available");var start=java.time.LocalDateTime.now().minusHours(1);var booking=new LinkedHashMap<String,Object>(Map.ofEntries(Map.entry("bookingType","Quick"),Map.entry("status","Active"),Map.entry("vehicleId",vehicle.getId()),Map.entry("driverMode","Self-drive"),Map.entry("customerName","Guest"),Map.entry("customerPhone","+966500000000"),Map.entry("customerEmail","guest@example.com"),Map.entry("pickupAt",start.toString()),Map.entry("returnAt",start.plusDays(1).toString()),Map.entry("pickupLocation","Khobar"),Map.entry("destination","Dammam"),Map.entry("totalAmount",200)));
  var rental=rentals.create("Individual",booking);assertThrows(ApiException.class,()->save(null,job(vehicle.getId(),"Open")));rentals.transition("Individual",((Number)rental.get("id")).longValue(),"Completed",Map.of());save(null,job(vehicle.getId(),"Open"));assertThrows(ApiException.class,()->rentals.create("Individual",booking));
 }
 @Test void receiptsPersistReplaceRemoveAndRollbackWithInvalidExpense(){
  var vehicle=car("Available");var data=job(vehicle.getId(),"Open");var upload=new LinkedMultiValueMap<String,org.springframework.web.multipart.MultipartFile>();upload.add("receipt-line-1",new MockMultipartFile("receipt-line-1","receipt.pdf","application/pdf","%PDF-1.7 receipt".getBytes()));var saved=maintenance.save(null,data,upload);long id=((Number)saved.get("id")).longValue();assertTrue(maintenance.receipt(id,"line-1").resource().exists());
  @SuppressWarnings("unchecked")var expenses=(List<Map<String,Object>>)saved.get("expenseLines");data.put("expenseLines",List.of(expenses.get(1)));save(id,data);assertThrows(ApiException.class,()->maintenance.receipt(id,"line-1"));
  var invalid=job(vehicle.getId(),"Open");invalid.put("expenseLines",List.of(Map.of("id","line-1","category","Spare Parts","amount",-1,"description","bad")));assertThrows(ApiException.class,()->maintenance.save(null,invalid,upload));
 }
 @Test void multipartApiValidatesWorkItemsAndServesReceipts()throws Exception{
  var mvc=MockMvcBuilders.webAppContextSetup(context).build();var data=job(car("Available").getId(),"Completed");var record=new MockMultipartFile("record","","application/json",json.writeValueAsBytes(data));var receipt=new MockMultipartFile("receipt-line-1","invoice.pdf","application/pdf","%PDF-1.7 receipt".getBytes());var result=mvc.perform(multipart("/api/maintenance").file(record).file(receipt)).andExpect(status().isOk()).andReturn();var tree=json.readTree(result.getResponse().getContentAsString());String url=tree.get("expenseLines").get(0).get("receipt").get("url").asString();mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
  data.put("workItem","Unknown");mvc.perform(post("/api/maintenance").contentType("application/json").content(json.writeValueAsBytes(data))).andExpect(status().isBadRequest());
 }
}
