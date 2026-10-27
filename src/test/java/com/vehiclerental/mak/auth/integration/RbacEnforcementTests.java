package com.vehiclerental.mak.auth.integration;

import com.vehiclerental.mak.auth.entity.*;
import com.vehiclerental.mak.auth.repository.*;
import com.vehiclerental.mak.auth.service.*;
import com.vehiclerental.mak.auth.dto.request.*;
import com.vehiclerental.mak.auth.security.*;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.notifications.email.service.EmailService;
import com.vehiclerental.mak.invoices.service.InvoiceService;
import com.vehiclerental.mak.rentals.entity.RentalRecord;
import com.vehiclerental.mak.rentals.repository.RentalRepository;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.util.*;import java.time.*;import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** Enforce module and sensitive-action boundaries using isolated records and mocked email. */
@SpringBootTest @Transactional
class RbacEnforcementTests {
 @Autowired RoleService roles; @Autowired AccountService service; @Autowired AccountRepository accounts;
 @Autowired AuthControlRepository controls; @Autowired AuthAuditRepository audits; @Autowired InvitationRepository invitations;
 @Autowired WebApplicationContext context; @Autowired InvoiceService invoices; @Autowired RentalRepository rentals; @Autowired ObjectMapper json; @Autowired com.vehiclerental.mak.invoices.repository.InvoiceRepository invoiceRecords;
 @Autowired com.vehiclerental.mak.fleet.repository.VehicleRepository fleetVehicles;
 @Autowired com.vehiclerental.mak.fleet.service.VehicleFileService fleetFiles;
 Account admin; MockMvc mvc;
 final String[] modules={"fleet","drivers","employees","vendors","customers","bookings","corporate-trips","maintenance","inspections","invoices"};
 final String[] resources={"vehicles","drivers","employees","vendors","customers","bookings","corporate-trips","maintenance","inspections","invoices"};
 @BeforeEach void setup(){controls.findById(1).orElseThrow().setBootstrapDone(true);admin=account("Admin",null);mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();}
 Account account(String role,Long roleId){var a=new Account();a.setName("Audit account");a.setEmail("audit-"+UUID.randomUUID()+"@example.invalid");a.setPasswordHash("not-a-login-password");a.setRole(role);a.setCustomRoleId(roleId);a.setStatus("Active");a.setCreatedAt(Instant.now());return accounts.saveAndFlush(a);}
 MockHttpSession session(Account a){var s=new MockHttpSession();s.setAttribute("SPRING_SECURITY_CONTEXT",new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(new AccountPrincipal(a.getId(),a.getSessionVersion()),null,service.permissions(a).stream().map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList())));return s;}
 Account custom(String... grants){var r=roles.save(null,new HashMap<>(Map.of("name","Audit "+UUID.randomUUID(),"permissions",List.of(grants))),admin.getId());return account("Viewer",((Number)r.get("id")).longValue());}
 int call(MockHttpSession s,String method,String path,String body)throws Exception{MockHttpServletRequestBuilder request=switch(method){case "GET"->get(path);case "POST"->post(path);case "PUT"->put(path);case "PATCH"->patch(path);case "DELETE"->delete(path);default->throw new IllegalArgumentException();};if(s!=null)request.session(s);if(!"GET".equals(method))request.with(csrf().asHeader()).contentType("application/json").content(body==null?"{}":body);int code=mvc.perform(request).andReturn().getResponse().getStatus();System.out.println("RBAC_AUDIT,"+method+","+path+","+code);return code;}
 void denied(MockHttpSession s,String method,String path)throws Exception{assertEquals(s==null?401:403,call(s,method,path,null),method+" "+path);}
 void reachesHandler(MockHttpSession s,String method,String path)throws Exception{int code=call(s,method,path,null);assertTrue(code>=200&&code<500&&code!=401&&code!=403,"Expected authorized handler, got "+code+" for "+method+" "+path);}
 @Test void allTenModulesEnforceNoAccessReadCreateAndEdit()throws Exception{
  var none=session(custom());
  for(int i=0;i<modules.length;i++){
   String module=modules[i],path="/api/"+resources[i];
   denied(null,"GET",path);denied(none,"GET",path);denied(none,"POST",path);denied(none,"PUT",path+"/999999");
   var reader=session(custom(module+".read"));assertEquals(200,call(reader,"GET",path,null),module+" list");denied(reader,"POST",path);denied(reader,"PUT",path+"/999999");
   var creator=session(custom(module+".read",module+".create"));reachesHandler(creator,"POST",path);denied(creator,"PUT",path+"/999999");
   var editor=session(custom(module+".read",module+".edit"));denied(editor,"POST",path);reachesHandler(editor,"PUT",path+"/999999");
  }
 }
 @Test void builtInRoleReadMatrixMatchesCatalog()throws Exception{
  for(String name:List.of("Admin","Manager","Operations","Finance","Viewer")){
   var a=account(name,null);var s=session(a);var grants=service.permissions(a);
   for(int i=0;i<modules.length;i++)assertEquals(grants.contains(modules[i]+".read")?200:403,call(s,"GET","/api/"+resources[i],null),name+" "+modules[i]);
   for(var item:Map.of("/api/users","users.manage","/api/security-audit","audit.read","/api/users/role-definitions","users.manage").entrySet())assertEquals(grants.contains(item.getValue())?200:403,call(s,"GET",item.getKey(),null),name+" "+item.getKey());
  }
 }
 @Test void nestedFilesImportsStatusQuotesAndArchiveAreProtected()throws Exception{
  String[][] probes={{"fleet","/api/vehicles/999999/files/999999"},{"drivers","/api/drivers/999999/files/999999"},{"employees","/api/employees/999999/files/999999"},{"vendors","/api/vendors/999999/files/999999"}};
  for(var item:probes){var reader=session(custom(item[0]+".read"));denied(reader,"DELETE",item[1]);reachesHandler(session(custom(item[0]+".read",item[0]+".archive")),"DELETE",item[1]);denied(session(custom()),"GET",item[1]);reachesHandler(reader,"GET",item[1]);}
  for(String module:List.of("drivers","employees","vendors")){var path="/api/"+module+"/999999";denied(session(custom(module+".read",module+".edit")),"DELETE",path);reachesHandler(session(custom(module+".read",module+".archive")),"DELETE",path);}
  for(int i:List.of(0,1,2)){var reader=session(custom(modules[i]+".read"));denied(reader,"POST","/api/"+resources[i]+"/import");reachesHandler(session(custom(modules[i]+".read",modules[i]+".create")),"POST","/api/"+resources[i]+"/import");}
  var customerReader=session(custom("customers.read"));denied(customerReader,"POST","/api/customers/999999/quotes");denied(customerReader,"POST","/api/quotes/no-quote/approve");reachesHandler(session(custom("customers.read","customers.create")),"POST","/api/customers/999999/quotes");denied(session(custom("customers.read","customers.edit")),"POST","/api/customers/999999/quotes");reachesHandler(session(custom("customers.read","customers.edit")),"POST","/api/quotes/no-quote/reject");
  var bookingReader=session(custom("bookings.read"));denied(bookingReader,"PATCH","/api/bookings/999999/status");denied(bookingReader,"PATCH","/api/bookings/999999/assignment");
  reachesHandler(session(custom("bookings.read","bookings.edit")),"PATCH","/api/bookings/999999/status");
  var corporateReader=session(custom("corporate-trips.read"));denied(corporateReader,"PATCH","/api/corporate-trips/999999/status");reachesHandler(session(custom("corporate-trips.read","corporate-trips.edit")),"PATCH","/api/corporate-trips/999999/status");
 }
 @Test void importDraftEndpointsSeparateReadCreateEditAndArchive() throws Exception {
  String path="/api/vehicles/import-drafts";
  var reader=session(custom("fleet.read"));var creator=session(custom("fleet.read","fleet.create"));var editor=session(custom("fleet.read","fleet.edit"));var archiver=session(custom("fleet.read","fleet.archive"));
  denied(null,"GET",path);assertEquals(200,call(reader,"GET",path,null));
  denied(reader,"POST",path);denied(editor,"POST",path);reachesHandler(creator,"POST",path);
  denied(reader,"PUT",path+"/999999");denied(creator,"PUT",path+"/999999");reachesHandler(editor,"PUT",path+"/999999");
  denied(editor,"POST",path+"/999999/complete");reachesHandler(creator,"POST",path+"/999999/complete");
  for(String encoded:List.of("/api/vehicles/import-drafts/%31/complete","/api/vehicles/%69mport-drafts")) {
   mvc.perform(post(java.net.URI.create(encoded)).session(editor).with(csrf().asHeader()).contentType("application/json").content("{}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
  }
  denied(creator,"DELETE",path+"/999999?version=0");denied(editor,"DELETE",path+"/999999?version=0");reachesHandler(archiver,"DELETE",path+"/999999?version=0");
 }
 @Test void financeStandardActionsAreSeparatelyEnforced()throws Exception{
  var reader=session(custom("invoices.read"));var editor=session(custom("invoices.read","invoices.edit","invoices.create"));
  for(var s:List.of(reader,editor)){denied(s,"POST","/api/invoices/999999/issue");denied(s,"POST","/api/invoices/999999/credit-notes");denied(s,"PUT","/api/invoices/billing-profile");denied(s,"POST","/api/invoices/999999/discard");assertEquals(403,call(s,"POST","/api/invoices/999999/payments","{\"kind\":\"Refund\",\"amount\":1}"));}
  reachesHandler(session(custom("invoices.read","invoices.issue")),"POST","/api/invoices/999999/issue");
  reachesHandler(session(custom("invoices.read","invoices.credit")),"POST","/api/invoices/999999/credit-notes");
  reachesHandler(session(custom("invoices.read","billing-profile.write")),"PUT","/api/invoices/billing-profile");
  reachesHandler(session(custom("invoices.read","invoices.archive")),"POST","/api/invoices/999999/discard");
  assertEquals(403,call(session(custom("bookings.read","bookings.edit")),"POST","/api/bookings/999999/payments","{\"kind\":\"Refund\",\"amount\":1}"));
  assertEquals(404,call(session(custom("invoices.read","payments.refund")),"POST","/api/invoices/999999/payments","{\"kind\":\"Refund\",\"amount\":1}"));
 }
 @Test void staleSessionsInactiveAccountsAndAdminEndpointsAreProtected()throws Exception{
  var a=custom("fleet.read");var s=session(a);service.update(admin.getId(),a.getId(),new AccountAccessRequest("Viewer","Inactive",null,a.getSessionVersion()));assertEquals(401,call(s,"GET","/api/vehicles",null));
  var changed=custom("fleet.read");var old=session(changed);var definition=roles.list().stream().filter(r->Objects.equals(r.get("id"),changed.getCustomRoleId())).findFirst().orElseThrow();var payload=new HashMap<String,Object>(definition);payload.put("permissions",new ArrayList<>((Collection<String>)definition.get("permissions")));roles.save(changed.getCustomRoleId(),payload,admin.getId());assertEquals(401,call(old,"GET","/api/vehicles",null));
  var reader=session(custom("fleet.read"));for(String p:List.of("/api/users","/api/users/role-definitions","/api/users/invitations","/api/security-audit","/api/notifications/summary","/api/emails?kind=Invoice","/api/unknown"))denied(reader,"GET",p);
  denied(reader,"POST","/api/users/role-definitions");denied(reader,"PATCH","/api/users/999999/access");denied(reader,"POST","/api/users/invitations");
  assertEquals(200,call(session(custom("users.manage")),"GET","/api/users/role-definitions",null));
  assertEquals(200,call(session(custom("bookings.read","bookings.create")),"GET","/api/notifications/summary",null));
  var dashboard=mvc.perform(get("/api/dashboard").session(session(custom()))).andReturn().getResponse();assertEquals(200,dashboard.getStatus());var data=json.readTree(dashboard.getContentAsString());assertTrue(data.path("recentBookings").isEmpty());assertFalse(data.path("access").path("finance").asBoolean());assertFalse(data.path("access").path("fleet").asBoolean());
 }
 String inviteToken(EmailService mock){var url=ArgumentCaptor.forClass(String.class);verify(mock,atLeastOnce()).staffInvitation(anyLong(),anyString(),anyString(),anyString(),url.capture());String value=url.getValue();return value.substring(value.indexOf("invitation=")+11);}
 @Test void invitationResendRevokeExpiryDuplicateAndDisabledMailUseMockOnly(){
  var email=mock(EmailService.class);var sender=new InvitationService(invitations,accounts,controls,audits,roles,email,true,"http://localhost:5173");
  var body=Map.<String,Object>of("name","Audit invitation","email","invite-"+UUID.randomUUID()+"@example.invalid","role","Viewer");var result=sender.invite(body,admin.getId());long id=((Number)result.get("id")).longValue();String first=inviteToken(email);assertThrows(ApiException.class,()->sender.invite(body,admin.getId()));
  clearInvocations(email);sender.update(id,"resend",admin.getId());String second=inviteToken(email);assertNotEquals(first,second);assertThrows(ApiException.class,()->sender.preview(first));assertEquals(body.get("email"),sender.preview(second).get("email"));
  invitations.findById(id).orElseThrow().setExpiresAt(Instant.now().minusSeconds(1));assertThrows(ApiException.class,()->sender.preview(second));assertEquals("Expired",((List<Map<String,Object>>)sender.list().get("invitations")).stream().filter(i->Objects.equals(i.get("id"),id)).findFirst().orElseThrow().get("status"));
  sender.update(id,"resend",admin.getId());sender.update(id,"revoke",admin.getId());assertThrows(ApiException.class,()->sender.preview(inviteToken(email)));
  var disabled=new InvitationService(invitations,accounts,controls,audits,roles,email,false,"http://localhost:5173");assertThrows(ApiException.class,()->disabled.invite(Map.of("name","Blocked","email","blocked@example.invalid","role","Viewer"),admin.getId()));
 }
 Map<String,Object> invoiceRequest(){var r=new RentalRecord();r.setKind("Individual");r.setBookingType("Quick");r.setStatus("Completed");r.setReference("AUDIT-"+UUID.randomUUID());r.setPickupAt(LocalDateTime.of(2026,1,1,9,0));r.setReturnAt(LocalDateTime.of(2026,1,2,9,0));r.setTotalAmount(new BigDecimal("115"));r.setReceivedAmount(new BigDecimal("115"));r.setRefundedAmount(BigDecimal.ZERO);r.setPayload("{}");r.setCreatedAt(Instant.now());rentals.saveAndFlush(r);return Map.of("bookingChargeReview",Map.of("confirmed",true),"kind","Individual","currency","SAR","sources",List.of(Map.of("kind","Individual","id",r.getId())),"lines",List.of(Map.of("description","Rental","unit","Trip","quantity",1,"unitPrice",100,"taxPercent",15)),"issuer",Map.of("name","MAK","street","Road"),"billTo",Map.of("name","Audit Guest","email","audit@example.invalid"),"dueDate",LocalDate.now(ZoneId.of("Asia/Riyadh")).plusDays(7).toString());}

 @Test void everyTrueBooleanSpellingRequiresIssuePermissionOnCreateAndUpdate()throws Exception{
  var creator=session(custom("invoices.read","invoices.create"));
  var editor=session(custom("invoices.read","invoices.edit"));
  var draft=invoices.create(invoiceRequest(),false);long draftId=((Number)draft.get("id")).longValue();
  for(String value:List.of("true","TRUE","1","yes","on"," true ")){
   mvc.perform(post("/api/invoices").param("issue",value).session(creator).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsString(invoiceRequest()))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
   mvc.perform(put("/api/invoices/"+draftId).param("issue",value).session(editor).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsString(draft))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
  }
  assertEquals("Draft",invoices.get(draftId).get("status"));
  var issuer=session(custom("invoices.read","invoices.create","invoices.issue"));
  var issued=mvc.perform(post("/api/invoices").param("issue","on").session(issuer).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsString(invoiceRequest()))).andReturn().getResponse();
  assertEquals(200,issued.getStatus());assertEquals("Issued",json.readTree(issued.getContentAsString()).path("status").asString());
  var issuingEditor=session(custom("invoices.read","invoices.edit","invoices.issue"));
  var updated=mvc.perform(put("/api/invoices/"+draftId).param("issue","yes").session(issuingEditor).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsString(draft))).andReturn().getResponse();
  assertEquals(200,updated.getStatus());assertEquals("Issued",json.readTree(updated.getContentAsString()).path("status").asString());
 }
 @Test void falseBooleanSpellingsStillAllowDraftCreationWithoutIssueGrant()throws Exception{
  var creator=session(custom("invoices.read","invoices.create"));
  for(String value:List.of("false","FALSE","0","no","off")){
   var result=mvc.perform(post("/api/invoices").param("issue",value).session(creator).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsString(invoiceRequest()))).andReturn().getResponse();
   assertEquals(200,result.getStatus());assertEquals("Draft",json.readTree(result.getContentAsString()).path("status").asString());
  }
 }
 @Test void refundNormalizationCannotBypassGrantAndCreditOnlyRefundOnlyRolesWork()throws Exception{
  var invoice=invoices.create(invoiceRequest(),true);long id=((Number)invoice.get("id")).longValue();
  var creditRequest=Map.of("requestId",UUID.randomUUID().toString(),"reason","Service adjustment","lines",List.of(Map.of("lineIndex",0,"amount",23)));
  var creditOnly=session(custom("invoices.read","invoices.credit"));
  assertEquals(200,call(creditOnly,"POST","/api/invoices/"+id+"/credit-notes",json.writeValueAsString(creditRequest)));
  var editor=session(custom("invoices.read","invoices.edit"));
  for(String kind:List.of("Refund"," Refund ","\tRefund\n")){
   assertEquals(403,call(editor,"POST","/api/invoices/"+id+"/payments",json.writeValueAsString(Map.of("kind",kind,"amount",1,"date",LocalDate.now(ZoneId.of("Asia/Riyadh")).toString(),"method","Cash"))));
  }
  assertEquals(0,new BigDecimal(invoiceRecords.findById(id).orElseThrow().getPaidAmount().toString()).compareTo(new BigDecimal("115")));
  var refundOnly=session(custom("invoices.read","payments.refund"));
  assertEquals(200,call(refundOnly,"POST","/api/invoices/"+id+"/payments",json.writeValueAsString(Map.of("kind"," Refund ","amount",1,"date",LocalDate.now(ZoneId.of("Asia/Riyadh")).toString(),"method","Cash"))));
  assertEquals(0,new BigDecimal(invoiceRecords.findById(id).orElseThrow().getPaidAmount().toString()).compareTo(new BigDecimal("114")));
 }
 @Test void catalogOnlyOffersSupportedRemovalActions(){
  for(String module:List.of("customers","bookings","corporate-trips","maintenance","inspections")){
   assertFalse(RolePolicy.catalog().contains(module+".archive"));
   assertThrows(ApiException.class,()->roles.save(null,new HashMap<>(Map.of("name","Unsupported "+UUID.randomUUID(),"permissions",List.of(module+".read",module+".archive"))),admin.getId()));
  }
  for(String module:List.of("fleet","drivers","employees","vendors","invoices"))assertTrue(RolePolicy.catalog().contains(module+".archive"));
 }

 @Test void fleetDocumentsOpenInlineOrDownloadWithTheSameReadPermission()throws Exception{
  var vehicle=new com.vehiclerental.mak.fleet.entity.Vehicle();String key=UUID.randomUUID().toString();
  vehicle.setRegistration("PDF-"+key);vehicle.setVehicleId("PDF-"+key);vehicle.setMake("QA");vehicle.setModel("File access");
  vehicle.setYear(2026);vehicle.setType("Sedan");vehicle.setSeats(5);vehicle.setTransmission("Automatic");vehicle.setFuelType("Petrol");
  vehicle.setDailyRate(BigDecimal.TEN);vehicle.setRateCurrency("SAR");vehicle.setOwnership("Owned");vehicle.setStatus("Inactive");
  fleetVehicles.saveAndFlush(vehicle);
  String filename="Istimara café 2026.pdf";byte[] pdf="%PDF-1.7\nQA isolated file fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  var document=fleetFiles.uploadDocument(vehicle.getId(),"Others",null,new org.springframework.mock.web.MockMultipartFile("file",filename,"application/pdf",pdf));
  var reader=session(custom("fleet.read"));var unrelated=session(custom("employees.read"));
  for(boolean download:List.of(false,true)){
   String path=document.url()+(download?"?download=true":"");
   var response=mvc.perform(get(path).session(reader)).andReturn().getResponse();
   assertEquals(200,response.getStatus());assertEquals("application/pdf",response.getContentType());assertArrayEquals(pdf,response.getContentAsByteArray());
   var disposition=org.springframework.http.ContentDisposition.parse(response.getHeader("Content-Disposition"));
   assertEquals(download?"attachment":"inline",disposition.getType());assertEquals(filename,disposition.getFilename());
   assertEquals(401,mvc.perform(get(path)).andReturn().getResponse().getStatus());
   assertEquals(403,mvc.perform(get(path).session(unrelated)).andReturn().getResponse().getStatus());
   assertEquals(404,mvc.perform(get("/api/vehicles/999999/files/"+document.id()).param("download",String.valueOf(download)).session(reader)).andReturn().getResponse().getStatus());
  }
  byte[] png={(byte)137,80,78,71,13,10,26,10,1,2};
  var image=fleetFiles.uploadImage(vehicle.getId(),new org.springframework.mock.web.MockMultipartFile("file","car.png","image/png",png));
  for(boolean download:List.of(false,true)){
   var response=mvc.perform(get(image.url()).param("download",String.valueOf(download)).session(reader)).andReturn().getResponse();
   assertEquals(200,response.getStatus());assertEquals("image/png",response.getContentType());assertArrayEquals(png,response.getContentAsByteArray());
   assertEquals(download?"attachment":"inline",org.springframework.http.ContentDisposition.parse(response.getHeader("Content-Disposition")).getType());
  }
 }

 @Test void fleetUtilizationIsAnAggregateProtectedByFleetRead()throws Exception{
  var reader=session(custom("fleet.read"));var unrelated=session(custom("bookings.read"));
  for(String period:List.of("LAST_7_DAYS","LAST_30_DAYS","THIS_MONTH")){
   var response=mvc.perform(get("/api/vehicles/utilization").param("period",period).session(reader)).andReturn().getResponse();
   assertEquals(200,response.getStatus());var data=json.readTree(response.getContentAsString());
   assertEquals(period,data.path("period").asText());assertEquals("Asia/Riyadh",data.path("timeZone").asText());
   assertTrue(data.has("fleetSize"));assertTrue(data.has("rentedSeconds"));assertFalse(data.has("rentals"));assertFalse(data.has("customerName"));
  }
  assertEquals(401,mvc.perform(get("/api/vehicles/utilization")).andReturn().getResponse().getStatus());
  assertEquals(403,mvc.perform(get("/api/vehicles/utilization").session(unrelated)).andReturn().getResponse().getStatus());
  assertEquals(400,mvc.perform(get("/api/vehicles/utilization").param("period","not-a-period").session(reader)).andReturn().getResponse().getStatus());
 }
 @Test void utilizationQueryIncludesBothRentalKindsButNotUnassignedRecords(){
  var vehicle=new com.vehiclerental.mak.fleet.entity.Vehicle();String key=UUID.randomUUID().toString();
  vehicle.setRegistration("USE-"+key);vehicle.setVehicleId("USE-"+key);vehicle.setMake("QA");vehicle.setModel("Utilization");vehicle.setYear(2026);vehicle.setType("Bus");vehicle.setSeats(35);vehicle.setTransmission("Manual");vehicle.setFuelType("Diesel");vehicle.setDailyRate(BigDecimal.TEN);vehicle.setRateCurrency("SAR");vehicle.setOwnership("Owned");vehicle.setStatus("Inactive");fleetVehicles.saveAndFlush(vehicle);
  for(String kind:List.of("Individual","Corporate"))for(String status:List.of("Active","Completed","Pending","Confirmed","Cancelled")){
   var row=new RentalRecord();row.setReference("USE-"+UUID.randomUUID());row.setKind(kind);row.setBookingType("Quick");row.setStatus(status);row.setVehicleId(vehicle.getId());row.setPickupAt(LocalDateTime.now().minusDays(1));row.setReturnAt(LocalDateTime.now());row.setTotalAmount(BigDecimal.TEN);row.setReceivedAmount(BigDecimal.ZERO);row.setRefundedAmount(BigDecimal.ZERO);row.setPayload("{}");row.setCreatedAt(Instant.now());rentals.saveAndFlush(row);
  }
  var found=rentals.utilizationSources().stream().filter(row->vehicle.getId().equals(row.vehicleId())).toList();
  assertEquals(4,found.size());assertTrue(found.stream().allMatch(row->Set.of("Active","Completed").contains(row.status())));
  var orphan=new RentalRecord();orphan.setReference("USE-"+UUID.randomUUID());orphan.setKind("Individual");orphan.setBookingType("Quick");orphan.setStatus("Active");orphan.setVehicleId(null);orphan.setPickupAt(LocalDateTime.now().minusDays(1));orphan.setReturnAt(LocalDateTime.now());orphan.setTotalAmount(BigDecimal.TEN);orphan.setReceivedAmount(BigDecimal.ZERO);orphan.setRefundedAmount(BigDecimal.ZERO);orphan.setPayload("{}");orphan.setCreatedAt(Instant.now());rentals.saveAndFlush(orphan);
  assertFalse(rentals.utilizationSources().stream().anyMatch(row->row.vehicleId()==null));
 }
}
