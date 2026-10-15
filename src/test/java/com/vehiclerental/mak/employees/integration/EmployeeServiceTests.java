package com.vehiclerental.mak.employees.integration;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.employees.dto.request.EmployeeRequest;
import com.vehiclerental.mak.employees.dto.request.EmployeeStatusRequest;
import com.vehiclerental.mak.employees.repository.EmployeeRepository;
import com.vehiclerental.mak.employees.service.EmployeeFileService;
import com.vehiclerental.mak.employees.service.EmployeeImportService;
import com.vehiclerental.mak.employees.service.EmployeeSaveService;
import com.vehiclerental.mak.employees.service.EmployeeService;
import com.vehiclerental.mak.vendors.entity.Vendor;
import com.vehiclerental.mak.vendors.repository.VendorRepository;
import com.vehiclerental.mak.vendors.service.VendorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class EmployeeServiceTests {
 @Autowired EmployeeService employees;@Autowired EmployeeRepository repository;@Autowired EmployeeFileService files;
 @Autowired EmployeeSaveService saves;@Autowired EmployeeImportService imports;@Autowired ObjectMapper json;@Autowired WebApplicationContext context;
 @Autowired VendorRepository vendors;
 @Autowired VendorService vendorService;
 private Map<String,Object> details(){String suffix=UUID.randomUUID().toString().substring(0,8);Map<String,Object> r=new LinkedHashMap<>(Map.of("employeeId","EMP-"+suffix,"name","Test Employee","phone","+96650"+Integer.toUnsignedString(suffix.hashCode()),"jobTitle","Fleet Coordinator","department","Fleet","category","Technical","employmentType","Full-time","status","Active","monthlySalary",6500));r.put("hourlyRate",25);r.put("dailyRate",200);r.put("nationality","Indian");r.put("homeAddress","Kochi, India");r.put("passportNumber","P-123");r.put("joinedOn","2025-01-15");return r;}
 private EmployeeRequest request(Map<String,Object> data){return json.convertValue(data,EmployeeRequest.class);}
 private MockMultipartFile image(String key){return new MockMultipartFile(key,"profile.png","image/png",new byte[]{(byte)137,80,78,71,13,10,26,10,1,2});}
 @Test void createsFiltersUpdatesAndProtectsPayrollHistory(){
  var data=details();var saved=employees.create(request(data));assertEquals("Kochi, India",saved.details().homeAddress());assertEquals(1,employees.list(saved.details().employeeId(),"Fleet","Active",0,20).getTotalElements());
  data.put("name","Updated Employee");assertEquals("Updated Employee",employees.update(saved.id(),request(data)).details().name());
  var entity=repository.findById(saved.id()).orElseThrow();entity.setPayrollCount(1);repository.saveAndFlush(entity);assertThrows(ApiException.class,()->employees.archive(saved.id()));
  assertEquals("Inactive",employees.updateStatus(saved.id(),new EmployeeStatusRequest("Inactive")).details().status());
  var noHistory=employees.create(request(details()));employees.archive(noHistory.id());assertThrows(ApiException.class,()->employees.get(noHistory.id()));
 }
 @Test void detectsDuplicateIdsPhonesAndReportsPartialImport(){
  var one=details();var saved=employees.create(request(one));var duplicate=details();duplicate.put("employeeId",saved.details().employeeId().toLowerCase());assertThrows(ApiException.class,()->employees.create(request(duplicate)));
  var phoneDuplicate=details();phoneDuplicate.put("phone",saved.details().phone().replace("+",""));var finalDuplicate=phoneDuplicate;assertThrows(ApiException.class,()->employees.create(request(finalDuplicate)));
  var fresh=request(details());var result=imports.importRows(Arrays.asList(fresh,fresh,null));assertEquals(1,result.successful());assertEquals(2,result.failed());assertTrue(result.errorReportCsv().contains("Duplicate" )||result.errorReportCsv().contains("already exists"));
 }
 @Test void savesPhotosDocumentsAtomicallyAndRejectsBorrowedAttachments(){
  var data=details();var uploads=new LinkedMultiValueMap<String,org.springframework.web.multipart.MultipartFile>();uploads.add("photo",image("photo"));uploads.add("doc",image("doc"));
  var attachments=new LinkedHashMap<String,Object>();attachments.put("photo",null);attachments.put("documents",List.of(Map.of("type","Iqama","uploadKey","doc","expiryDate",LocalDate.now().plusYears(1).toString())));
  var saved=saves.save(null,request(data),attachments,uploads);assertNotNull(saved.photo());assertEquals(1,saved.documents().size());assertTrue(files.download(saved.id(),saved.photo().id()).resource().exists());
  var other=employees.create(request(details()));var borrowed=Map.<String,Object>of("photo",saved.photo().id(),"documents",List.of());assertThrows(ApiException.class,()->saves.save(other.id(),other.details(),borrowed,new LinkedMultiValueMap<>()));
  var bad=details();var badFiles=new LinkedMultiValueMap<String,org.springframework.web.multipart.MultipartFile>();badFiles.add("doc",new MockMultipartFile("doc","bad.pdf","application/pdf","not a pdf".getBytes()));
  assertThrows(ApiException.class,()->saves.save(null,request(bad),attachments,badFiles));assertEquals(0,employees.list(String.valueOf(bad.get("employeeId")),null,null,0,20).getTotalElements());
 }
 @Test void multipartEndpointsPersistFilesAndReturnReadableUrls()throws Exception{
  var mvc=MockMvcBuilders.webAppContextSetup(context).build();var data=details();
  var record=new MockMultipartFile("record","","application/json",json.writeValueAsBytes(data));
  var attachments=new MockMultipartFile("attachments","","application/json",json.writeValueAsBytes(Map.of("documents",List.of(Map.of("type","Contract copy","uploadKey","doc")))));
  var response=mvc.perform(multipart("/api/employees/save").file(record).file(attachments).file(image("doc"))).andExpect(status().isOk()).andReturn();
  var tree=json.readTree(response.getResponse().getContentAsString());String url=tree.get("documents").get(0).get("url").asString();mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
  long id=((Number)json.readValue(response.getResponse().getContentAsString(),Map.class).get("id")).longValue();
  var cleared=new MockMultipartFile("attachments","","application/json",json.writeValueAsBytes(Map.of("documents",List.of())));
  mvc.perform(multipart("/api/employees/"+id+"/save").file(record).file(cleared).with(request->{request.setMethod("PUT");return request;})).andExpect(status().isOk());
  assertTrue(employees.get(id).documents().isEmpty());mvc.perform(get(url)).andExpect(status().isNotFound());
  data.put("monthlySalary",-1);mvc.perform(post("/api/employees").contentType("application/json").content(json.writeValueAsBytes(data))).andExpect(status().isBadRequest());
 }
 @Test void linkedEmployeeProtectsVendorFromDeletion(){
  String name="Employee Supplier "+UUID.randomUUID();var vendor=new Vendor();vendor.setVendorType("Business");vendor.setName(name);vendor.setNameKey(name.toUpperCase());vendor.setCommission(java.math.BigDecimal.ZERO);vendor.setStatus("Active");vendor.setCreditTerms(30);vendor.setCurrency("SAR");vendors.saveAndFlush(vendor);
  var data=details();data.put("employmentType","Vendor");data.put("vendor",name);employees.create(request(data));
  assertThrows(ApiException.class,()->vendorService.archive(vendor.getId()));
 }
}
