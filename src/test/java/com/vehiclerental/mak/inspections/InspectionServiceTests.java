package com.vehiclerental.mak.inspections;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import com.vehiclerental.mak.common.ApiException;
import com.vehiclerental.mak.fleet.*;
import com.vehiclerental.mak.rentals.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class InspectionServiceTests {
    @Autowired InspectionService service; @Autowired InspectionRepository inspections;
    @Autowired VehicleRepository vehicles; @Autowired RentalRepository rentals;
    @Autowired WebApplicationContext context; @Autowired ObjectMapper json;
    @Value("${app.upload-dir}") String uploadDirectory;
    private Vehicle car(){
        String suffix=UUID.randomUUID().toString().substring(0,8);var v=new Vehicle();
        v.setRegistration("I-"+suffix);v.setVehicleId("VI-"+suffix);v.setMake("Toyota");v.setModel("Camry");v.setYear(2025);v.setType("Sedan");v.setSeats(5);
        v.setTransmission("Automatic");v.setFuelType("Petrol");v.setDailyRate(new BigDecimal("200"));v.setRateCurrency("SAR");v.setOwnership("Owned");v.setStatus("Available");v.setCurrentOdometerKm(12000L);v.setLastOilKm(10000L);return vehicles.saveAndFlush(v);
    }
    private Map<String,Object> data(long car){return new LinkedHashMap<>(Map.of("vehicleId",car,"type","Pre-trip","condition","Good","inspectionDate",LocalDate.now().toString(),"inspector","  Test Inspector  ","odometerKm",13000,"fuelLevel",75,"notes","  Condition checked  ","photos",List.of()));}
    private Map<String,Object> save(Long id,Map<String,Object> data){return service.save(id,json.convertValue(data,InspectionRequest.class),new LinkedMultiValueMap<>());}
    private RentalRecord rental(long car,String kind){
        var r=new RentalRecord();r.setReference("TEST-"+UUID.randomUUID());r.setKind(kind);r.setBookingType("Quick");r.setStatus("Completed");r.setVehicleId(car);
        r.setPickupAt(LocalDateTime.now().minusDays(1));r.setReturnAt(LocalDateTime.now());r.setTotalAmount(BigDecimal.TEN);r.setReceivedAmount(BigDecimal.ZERO);r.setRefundedAmount(BigDecimal.ZERO);r.setPayload("{}");r.setCreatedAt(Instant.now());return rentals.saveAndFlush(r);
    }
    private long id(Map<String,Object> saved){return ((Number)saved.get("id")).longValue();}
    private MockMultipartFile image(String key){return new MockMultipartFile(key,"front.png","image/png",new byte[]{(byte)137,80,78,71,13,10,26,10,0});}
    private long storedFiles()throws Exception{Path root=Path.of(uploadDirectory,"inspections");if(!Files.exists(root))return 0;try(var files=Files.walk(root)){return files.filter(Files::isRegularFile).count();}}

    @Test void recordsPersistUpdateAndKeepServerReferenceAndMonotonicFleetReadings(){
        var vehicle=car();var input=data(vehicle.getId());input.put("inspectionId","FORGED");var saved=save(null,input);
        assertTrue(saved.get("inspectionId").toString().startsWith("INS-"));assertEquals("Test Inspector",saved.get("inspector"));assertEquals("Condition checked",saved.get("notes"));
        assertEquals(13000L,vehicles.findById(vehicle.getId()).orElseThrow().getCurrentOdometerKm());
        input.put("odometerKm",11000);input.put("condition","Damaged");input.put("notes","Recorded damage");save(id(saved),input);
        var refreshed=vehicles.findById(vehicle.getId()).orElseThrow();assertEquals(13000L,refreshed.getCurrentOdometerKm());assertEquals(10000L,refreshed.getLastOilKm());assertEquals("Available",refreshed.getStatus());
        assertEquals("Recorded damage",service.get(id(saved)).get("notes"));assertEquals(saved.get("inspectionId"),service.get(id(saved)).get("inspectionId"));
        assertTrue(service.list().stream().anyMatch(i->i.get("id").equals(saved.get("id"))));assertThrows(ApiException.class,()->service.get(Long.MAX_VALUE));
    }
    @Test void validatesBothIndividualAndCorporateBookingLinksAgainstCar(){
        var a=car();var b=car();var individual=rental(a.getId(),"Individual");var corporate=rental(a.getId(),"Corporate");
        for(var booking:List.of(individual,corporate)){var input=data(a.getId());input.put("bookingId",booking.getId());assertEquals(booking.getId(),save(null,input).get("bookingId"));}
        var wrong=data(b.getId());wrong.put("bookingId",individual.getId());assertThrows(ApiException.class,()->save(null,wrong));
        wrong.put("bookingId",Long.MAX_VALUE);assertThrows(ApiException.class,()->save(null,wrong));
        assertThrows(ApiException.class,()->save(null,data(Long.MAX_VALUE)));
    }
    @Test void photoRetentionRemovalAndOwnershipAreEnforced()throws Exception{
        var input=data(car().getId());input.put("photos",List.of(Map.of("uploadKey","photo0"),Map.of("uploadKey","photo1")));
        var files=new LinkedMultiValueMap<String,org.springframework.web.multipart.MultipartFile>();files.add("photo0",image("photo0"));files.add("photo1",image("photo1"));
        var saved=service.save(null,json.convertValue(input,InspectionRequest.class),files);
        @SuppressWarnings("unchecked") var photos=(List<Map<String,Object>>)saved.get("photos");String first=photos.get(0).get("id").toString();String second=photos.get(1).get("id").toString();
        assertTrue(service.photo(id(saved),first).resource().exists());
        var foreign=data(car().getId());foreign.put("photos",List.of(Map.of("id",first)));assertThrows(ApiException.class,()->save(null,foreign));
        input.put("photos",List.of(Map.of("id",second)));save(id(saved),input);assertThrows(ApiException.class,()->service.photo(id(saved),first));assertTrue(service.photo(id(saved),second).resource().exists());
        input.put("photos",List.of());save(id(saved),input);assertThrows(ApiException.class,()->service.photo(id(saved),second));
    }
    @Test void failedPhotoBatchRollsBackDatabaseFleetAndStoredFiles()throws Exception{
        var vehicle=car();var input=data(vehicle.getId());input.put("photos",List.of(Map.of("uploadKey","photo0"),Map.of("uploadKey","photo1")));
        long records=inspections.count();long disk=storedFiles();var files=new LinkedMultiValueMap<String,org.springframework.web.multipart.MultipartFile>();files.add("photo0",image("photo0"));
        files.add("photo1",new MockMultipartFile("photo1","fake.jpg","image/jpeg","%PDF-1.7 disguised".getBytes()));
        assertThrows(ApiException.class,()->service.save(null,json.convertValue(input,InspectionRequest.class),files));
        assertEquals(records,inspections.count());assertEquals(disk,storedFiles());assertEquals(12000L,vehicles.findById(vehicle.getId()).orElseThrow().getCurrentOdometerKm());
        files.remove("photo1");assertThrows(ApiException.class,()->service.save(null,json.convertValue(input,InspectionRequest.class),files));
    }
    @Test void multipartCreateEditGetAndPhotoRemovalWorkThroughApi()throws Exception{
        var mvc=MockMvcBuilders.webAppContextSetup(context).build();var input=data(car().getId());input.put("photos",List.of(Map.of("uploadKey","photo0")));
        var result=mvc.perform(multipart("/api/inspections").file(new MockMultipartFile("record","","application/json",json.writeValueAsBytes(input))).file(image("photo0"))).andExpect(status().isCreated()).andReturn();
        var tree=json.readTree(result.getResponse().getContentAsString());String path="/api/inspections/"+tree.get("id").asLong();String url=tree.get("photos").get(0).get("url").asString();
        mvc.perform(get(path)).andExpect(status().isOk());mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("inline")));
        input.put("photos",List.of());input.put("condition","Fair");mvc.perform(multipart(path).file(new MockMultipartFile("record","","application/json",json.writeValueAsBytes(input))).with(r->{r.setMethod("PUT");return r;})).andExpect(status().isOk());mvc.perform(get(url)).andExpect(status().isNotFound());
    }
    @Test void apiRejectsMalformedFieldsAndExcessPhotos()throws Exception{
        var mvc=MockMvcBuilders.webAppContextSetup(context).build();var original=data(car().getId());
        for(var entry:Map.<String,Object>of("inspector"," ","type","Unknown","condition","Unsafe","odometerKm",new BigDecimal("12.5"),"fuelLevel",new BigDecimal("25.5"),"inspectionDate","2026-02-31").entrySet()){
            var invalid=new LinkedHashMap<>(original);invalid.put(entry.getKey(),entry.getValue());mvc.perform(post("/api/inspections").contentType("application/json").content(json.writeValueAsBytes(invalid))).andExpect(status().isBadRequest());
        }
        var invalid=new LinkedHashMap<>(original);invalid.put("fuelLevel",33);mvc.perform(post("/api/inspections").contentType("application/json").content(json.writeValueAsBytes(invalid))).andExpect(status().isBadRequest());
        invalid.put("fuelLevel",75);invalid.put("photos",Collections.nCopies(11,Map.of("uploadKey","photo0")));mvc.perform(post("/api/inspections").contentType("application/json").content(json.writeValueAsBytes(invalid))).andExpect(status().isBadRequest());
    }
}
