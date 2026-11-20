package com.vehiclerental.mak.enquiries;
import com.vehiclerental.mak.auth.entity.*;import com.vehiclerental.mak.auth.repository.*;import com.vehiclerental.mak.auth.service.*;import com.vehiclerental.mak.auth.security.*;
import com.vehiclerental.mak.fleet.entity.Vehicle;import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import java.util.*;import java.time.*;import java.math.BigDecimal;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.context.SpringBootTest;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.*;import org.springframework.test.web.servlet.setup.MockMvcBuilders;import org.springframework.web.context.WebApplicationContext;import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;import org.springframework.security.core.context.SecurityContextImpl;import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
@SpringBootTest(properties="app.public-enquiries.rate-limit=1000") @Transactional
class EnquiryWorkflowTests {
 @Autowired WebApplicationContext context;@Autowired EnquiryRepository records;@Autowired EnquiryService service;@Autowired EnquiryEventRepository events;@Autowired ObjectMapper json;@Autowired AccountRepository accounts;@Autowired AccountService accountService;@Autowired RoleService roles;@Autowired AuthControlRepository controls;@Autowired VehicleRepository vehicles;@Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
 MockMvc mvc;Account admin;
 @BeforeEach void setup(){mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();controls.findById(1).orElseThrow().setBootstrapDone(true);admin=account("Admin",null);}
 Account account(String role,Long custom){var a=new Account();a.setName("Enquiry QA");a.setEmail(UUID.randomUUID()+"@example.invalid");a.setRole(role);a.setCustomRoleId(custom);a.setPasswordHash("not-a-login");a.setStatus("Active");a.setCreatedAt(Instant.now());return accounts.saveAndFlush(a);}
 MockHttpSession session(Account a){var s=new MockHttpSession();s.setAttribute("SPRING_SECURITY_CONTEXT",new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(new AccountPrincipal(a.getId(),a.getSessionVersion()),null,accountService.permissions(a).stream().map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList())));return s;}
 Account custom(String... grants){var r=roles.save(null,new HashMap<>(Map.of("name","Inbox QA "+UUID.randomUUID(),"permissions",List.of(grants))),admin.getId());return account("Viewer",((Number)r.get("id")).longValue());}
 Map<String,Object> contact(){var m=new LinkedHashMap<String,Object>();m.put("submissionId",UUID.randomUUID().toString());m.put("name","QA sender");m.put("email","qa@example.invalid");m.put("phone","+966500000000");m.put("subject","General question");m.put("message","QA contact <script>alert(1)</script>");m.put("website","");return m;}
 Map<String,Object> rental(){var m=contact();m.put("category","Bus");m.put("preference","With a driver");m.put("location","QA Al Khobar");m.put("company","QA Company");m.put("pickupDate",LocalDate.now(ZoneId.of("Asia/Riyadh")).plusDays(10).toString());m.put("returnDate",LocalDate.now(ZoneId.of("Asia/Riyadh")).plusDays(12).toString());return m;}
 EnquiryRequest dto(Map<String,Object> data){return json.convertValue(data,EnquiryRequest.class);}
 long id(Map<String,Object> data){return records.findBySubmissionId(data.get("submissionId").toString()).orElseThrow().getId();}
 String send(String path,Map<String,Object> data,int status)throws Exception{return mvc.perform(post(path).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsString(data))).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();}
 @Test void anonymousContactPersistsAndReturnsOnlyReceiptWithoutCreatingBookingOrCustomer()throws Exception{
  long customers=jdbc.queryForObject("select count(*) from customers",Long.class),rentals=jdbc.queryForObject("select count(*) from rental_records",Long.class);
  var data=contact();String receipt=send("/api/public/contact-messages",data,201);assertTrue(receipt.contains("MAK-ENQ-"));assertFalse(receipt.contains("qa@example"));assertFalse(receipt.contains("status"));var saved=service.get(id(data));assertEquals("Contact",saved.kind());assertEquals("New",saved.status());assertEquals(data.get("message"),saved.message());
  assertEquals(customers,jdbc.queryForObject("select count(*) from customers",Long.class));assertEquals(rentals,jdbc.queryForObject("select count(*) from rental_records",Long.class));
  mvc.perform(get("/api/enquiries")).andExpect(status().isUnauthorized());mvc.perform(get("/api/enquiries/"+saved.id())).andExpect(status().isUnauthorized());mvc.perform(get("/api/public/contact-messages")).andExpect(status().isUnauthorized());
 }
 @Test void publicFleetRoutesRemainUnavailableWhileIntegrationIsWithheld()throws Exception{
  for(String path:List.of("/api/public/vehicles","/api/public/vehicles/1/images/1")){
   mvc.perform(get(path)).andExpect(status().isUnauthorized());
   mvc.perform(get(path).session(session(admin))).andExpect(status().isForbidden());
  }
 }
 @Test void publicFormRequiresCsrfAndSupportsAnonymousCsrfSession()throws Exception{
  mvc.perform(post("/api/public/contact-messages").contentType("application/json").content(json.writeValueAsString(contact()))).andExpect(status().isForbidden());
  var tokenResult=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();var token=json.readTree(tokenResult.getResponse().getContentAsString());
  mvc.perform(post("/api/public/contact-messages").session((MockHttpSession)tokenResult.getRequest().getSession()).header(token.get("headerName").asText(),token.get("token").asText()).contentType("application/json").content(json.writeValueAsString(contact()))).andExpect(status().isCreated());
 }
 @Test void retriesAreIdempotentAndCannotReplaceOriginalMessage()throws Exception{var data=contact();long count=records.count();String first=send("/api/public/contact-messages",data,201);assertEquals(first,send("/api/public/contact-messages",data,201));assertEquals(count+1,records.count());data.put("message","A changed message");send("/api/public/contact-messages",data,409);assertNotEquals("A changed message",service.get(id(data)).message());}
 @Test void validatesRequiredLengthsEmailDatesAndHoneypot()throws Exception{
  for(var entry:Map.of("name"," ","email","invalid-email","message","x".repeat(4001),"website","spam.example").entrySet()){var data=contact();data.put(entry.getKey(),entry.getValue());send("/api/public/contact-messages",data,400);}
  var data=rental();data.put("phone"," ");send("/api/public/rental-enquiries",data,400);
  data=rental();data.put("pickupDate",LocalDate.now(ZoneId.of("Asia/Riyadh")).minusDays(1).toString());send("/api/public/rental-enquiries",data,400);
  data=rental();data.put("returnDate",LocalDate.now(ZoneId.of("Asia/Riyadh")).plusDays(1).toString());send("/api/public/rental-enquiries",data,400);
  data=rental();data.remove("pickupDate");send("/api/public/rental-enquiries",data,400);
 }
 @Test void rentalDatesAndVehicleSnapshotAreRetainedAndHiddenVehicleIsRejected()throws Exception{
  var v=new Vehicle();String key=UUID.randomUUID().toString();v.setRegistration(key);v.setVehicleId(key);v.setMake("QA");v.setModel("Bus");v.setYear(2024);v.setType("Bus");v.setSeats(30);v.setTransmission("Manual");v.setFuelType("Diesel");v.setOwnership("Owned");v.setStatus("Available");v.setShowOnPublicSite(true);vehicles.saveAndFlush(v);
  var data=rental();data.put("vehicleId",v.getId());send("/api/public/rental-enquiries",data,201);var e=service.get(id(data));assertEquals("QA Bus",e.vehicleName());assertEquals(v.getId(),e.vehicleId());assertEquals("With a driver",e.preference());assertEquals(data.get("pickupDate"),e.pickupDate().toString());
  v.setShowOnPublicSite(false);vehicles.saveAndFlush(v);data.put("submissionId",UUID.randomUUID().toString());send("/api/public/rental-enquiries",data,400);v.setShowOnPublicSite(true);v.setStatus("Inactive");vehicles.saveAndFlush(v);send("/api/public/rental-enquiries",data,400);
 }
 @Test void inboxReadAndEditAreIndependentlyEnforcedIncludingHistory()throws Exception{
  var data=contact();send("/api/public/contact-messages",data,201);long id=id(data);var reader=session(custom("enquiries.read"));var outsider=session(custom("customers.read"));var editor=session(custom("enquiries.read","enquiries.edit"));
  mvc.perform(get("/api/enquiries").session(reader)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
  mvc.perform(get("/api/enquiries/"+id+"/history").session(outsider)).andExpect(status().isForbidden());mvc.perform(get("/api/enquiries").session(outsider)).andExpect(status().isForbidden());
  String change="{\"status\":\"In progress\",\"internalNote\":\"Call customer\",\"version\":0}";
  mvc.perform(patch("/api/enquiries/"+id).session(reader).with(csrf()).contentType("application/json").content(change)).andExpect(status().isForbidden());
  mvc.perform(patch("/api/enquiries/"+id).session(editor).with(csrf()).contentType("application/json").content(change)).andExpect(status().isOk());
  mvc.perform(patch("/api/enquiries/"+id).session(editor).with(csrf()).contentType("application/json").content(change)).andExpect(status().isConflict());
  assertEquals(1,events.findByEnquiryIdOrderByIdDesc(id).size());assertEquals("In progress",service.get(id).status());assertNotNull(service.get(id).updatedBy());
  mvc.perform(post("/api/enquiries").session(editor).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
 }
 @Test void workflowCanCloseReopenAndMarkSpamWithVersionedAudit()throws Exception{var data=contact();send("/api/public/contact-messages",data,201);long id=id(data);for(String state:List.of("In progress","Closed","New","Spam")){var before=service.get(id);var saved=service.update(id,new EnquiryService.Change(state,"QA review",before.version()),admin.getId());assertEquals(state,saved.status());assertTrue(saved.version()>before.version());}assertEquals(4,events.findByEnquiryIdOrderByIdDesc(id).size());assertThrows(com.vehiclerental.mak.common.exception.ApiException.class,()->service.update(id,new EnquiryService.Change("Confirmed booking","",service.get(id).version()),admin.getId()));}
 @Test void inboxFiltersAndPaginationAreStableAndSearchEscapesWildcards()throws Exception{var c=contact();c.put("name","QA 100% sender");send("/api/public/contact-messages",c,201);var r=rental();r.put("name","QA 1000 sender");send("/api/public/rental-enquiries",r,201);assertEquals(1,service.list(0,20,"Contact","New","QA 100%").totalElements());assertEquals(1,service.list(0,20,"Rental","New","QA 1000").totalElements());var result=service.list(0,1,"","","QA 100");assertEquals(2,result.totalElements());assertEquals(id(r),result.content().getFirst().id());assertEquals(id(c),service.list(1,1,"","","QA 100").content().getFirst().id());assertThrows(com.vehiclerental.mak.common.exception.ApiException.class,()->service.list(0,101,"","",""));}
 @Test void builtInRolesHaveDeliberateInboxAccessAndCustomCatalogOffersOnlyReadAndEdit(){for(String role:List.of("Admin","Manager","Operations")){assertTrue(roles.resolve(role,null).containsAll(Set.of("enquiries.read","enquiries.edit")),role);}for(String role:List.of("Viewer","Finance"))assertFalse(roles.resolve(role,null).contains("enquiries.read"));assertTrue(RolePolicy.catalog().containsAll(Set.of("enquiries.read","enquiries.edit")));assertFalse(RolePolicy.catalog().contains("enquiries.create"));assertFalse(RolePolicy.catalog().contains("enquiries.archive"));}
}
