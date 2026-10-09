package com.vehiclerental.mak.auth;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.vehiclerental.mak.notifications.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties={"app.auth.bootstrap-code=test-bootstrap-code-123456789","app.auth.rate-limit=1000","app.mail.enabled=true"})
class AuthenticationTests {
 @Autowired WebApplicationContext context;@Autowired ObjectMapper json;@Autowired AccountService service;@Autowired AccountRepository accounts;@Autowired PasswordEncoder encoder;
 @Autowired AuthControlRepository controls;@Autowired AuthAuditRepository audits;@Autowired PasswordResetRepository resets;@Autowired EmailRepository emails;
 @MockitoBean org.springframework.mail.javamail.JavaMailSender mail;
 private MockMvc mvc;private static final String PASSWORD="Password123!test";
 @BeforeEach void setup(){mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();audits.deleteAll();resets.deleteAll();accounts.deleteAll();var control=controls.findById(1).orElseThrow();control.setBootstrapDone(false);controls.saveAndFlush(control);}
 private Account account(String role,String status){var a=new Account();a.setName("Test "+role);a.setEmail(UUID.randomUUID()+"@example.com");a.setPasswordHash(encoder.encode(PASSWORD));a.setRole(role);a.setStatus(status);a.setCreatedAt(Instant.now());return accounts.saveAndFlush(a);}
 private MockHttpSession login(Account account)throws Exception{return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(Map.of("email",account.getEmail(),"password",PASSWORD,"remember",false)))).andExpect(status().isOk()).andReturn().getRequest().getSession(false);}
 private Map<String,Object> signup(String email,String code){return Map.of("name","New User","email",email,"password",PASSWORD,"terms",true,"bootstrapCode",code);}
 @Test void dashboardRequiresLoginAndFiltersFinancialMetrics()throws Exception{
  mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
  var admin=login(account("Admin","Active"));
  mvc.perform(get("/api/dashboard?days=7").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.activity.length()").value(7)).andExpect(jsonPath("$.access.finance").value(true)).andExpect(jsonPath("$.timezone").value("Asia/Riyadh")).andExpect(jsonPath("$.financial.issuedValue").exists()).andExpect(jsonPath("$.operations.pendingHandovers").exists());
  mvc.perform(get("/api/dashboard?days=31").session(admin)).andExpect(status().isBadRequest());
  var viewer=login(account("Viewer","Active"));
  mvc.perform(get("/api/dashboard?days=30").session(viewer)).andExpect(status().isOk()).andExpect(jsonPath("$.activity.length()").value(30)).andExpect(jsonPath("$.access.finance").value(false)).andExpect(jsonPath("$.financial").doesNotExist()).andExpect(jsonPath("$.attention[?(@.page == 'Invoices')]").isEmpty());
 }
 @Test void publicEndpointsAndCsrfProtectAnonymousApiAccess()throws Exception{
  mvc.perform(get("/api/vehicles")).andExpect(status().isUnauthorized());mvc.perform(get("/api/employees/1/files/1")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/auth/me")).andExpect(status().isOk()).andExpect(jsonPath("$.user").isEmpty());mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
  mvc.perform(post("/api/auth/signup").contentType("application/json").content(json.writeValueAsBytes(signup("new@example.com","wrong")))).andExpect(status().isForbidden());
 }
 @Test void initialAdministratorRequiresCodeAndLaterSignupsCannotEscalate()throws Exception{
  mvc.perform(post("/api/auth/signup").with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(signup("setup@example.com","wrong")))).andDo(r->System.out.println("SIGNUP_VALIDATION: "+r.getResponse().getContentAsString())).andExpect(status().isForbidden());assertEquals(0,accounts.count());
  mvc.perform(post("/api/auth/signup").with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(signup("setup@example.com","test-bootstrap-code-123456789")))).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("Active"));
  var admin=accounts.findByEmail("setup@example.com").orElseThrow();assertEquals("Admin",admin.getRole());assertNotEquals(PASSWORD,admin.getPasswordHash());assertTrue(encoder.matches(PASSWORD,admin.getPasswordHash()));
  var pending=signup("pending@example.com","test-bootstrap-code-123456789");pending=new HashMap<>(pending);pending.put("role","Admin");pending.put("status","Active");
  mvc.perform(post("/api/auth/signup").with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(pending))).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("Pending"));
  var user=accounts.findByEmail("pending@example.com").orElseThrow();assertEquals("Viewer",user.getRole());assertEquals("Pending",user.getStatus());
  mvc.perform(post("/api/auth/login").with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(Map.of("email",user.getEmail(),"password",PASSWORD)))).andExpect(status().isForbidden());
  var session=login(admin);mvc.perform(get("/api/users").session(session)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("passwordHash"))));
  mvc.perform(patch("/api/users/"+user.getId()+"/access").session(session).with(csrf().asHeader()).contentType("application/json").content("{\"role\":\"Operations\",\"status\":\"Active\"}")).andExpect(status().isOk());assertNotNull(login(accounts.findById(user.getId()).orElseThrow()));
 }
 @Test void sessionCookiesFixationLogoutAndRememberChoiceWork()throws Exception{
  var admin=account("Admin","Active");var anonymous=new MockHttpSession();String old=anonymous.getId();
  var result=mvc.perform(post("/api/auth/login").session(anonymous).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(Map.of("email",admin.getEmail(),"password",PASSWORD,"remember",true)))).andExpect(status().isOk()).andReturn();
  var session=(MockHttpSession)result.getRequest().getSession(false);assertNotEquals(old,session.getId());assertEquals(30*24*3600,session.getMaxInactiveInterval());
  String cookie=result.getResponse().getHeader("Set-Cookie");assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Lax"));assertTrue(cookie.contains("Max-Age="));
  mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.user.role").value("Admin"));mvc.perform(post("/api/auth/logout").session(session).with(csrf().asHeader())).andExpect(status().isOk());assertTrue(session.isInvalid());mvc.perform(get("/api/vehicles")).andExpect(status().isUnauthorized());
 }
 @Test void viewerCanReadButCannotMutateManageAccountsOrReadPayrollDocuments()throws Exception{
  var session=login(account("Viewer","Active"));mvc.perform(get("/api/vehicles").session(session)).andExpect(status().isOk());mvc.perform(get("/api/bookings").session(session)).andExpect(status().isOk());
  mvc.perform(post("/api/vehicles").session(session).with(csrf().asHeader()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
  mvc.perform(get("/api/users").session(session)).andExpect(status().isForbidden());mvc.perform(get("/api/employees/1/files/1").session(session)).andExpect(status().isForbidden());mvc.perform(get("/api/invoices").session(session)).andExpect(status().isForbidden());
 }
 @Test void financeAndOperationsHaveSeparateWritePermissionsAndNoUnknownApis()throws Exception{
  var finance=login(account("Finance","Active"));mvc.perform(get("/api/invoices").session(finance)).andExpect(status().isOk());mvc.perform(post("/api/bookings").session(finance).with(csrf().asHeader()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
  var operations=login(account("Operations","Active"));mvc.perform(get("/api/inspections").session(operations)).andExpect(status().isOk());mvc.perform(post("/api/invoices").session(operations).with(csrf().asHeader()).contentType("application/json").content("{}")).andExpect(status().isForbidden());mvc.perform(get("/api/unknown").session(operations)).andExpect(status().isForbidden());
 }
 @Test void roleChangesAndDeactivationRevokeSessionsAndProtectLastAdministrator()throws Exception{
  var admin=account("Admin","Active");var viewer=account("Viewer","Active");var adminSession=login(admin);var userSession=login(viewer);
  mvc.perform(patch("/api/users/"+admin.getId()+"/access").session(adminSession).with(csrf().asHeader()).contentType("application/json").content("{\"role\":\"Viewer\",\"status\":\"Inactive\"}")).andExpect(status().isConflict());
  mvc.perform(patch("/api/users/"+viewer.getId()+"/access").session(adminSession).with(csrf().asHeader()).contentType("application/json").content("{\"role\":\"Finance\",\"status\":\"Active\"}")).andExpect(status().isOk());
  mvc.perform(get("/api/vehicles").session(userSession)).andExpect(status().isUnauthorized());var financeSession=login(accounts.findById(viewer.getId()).orElseThrow());
  service.update(admin.getId(),viewer.getId(),new AuthRequests.Access("Finance","Inactive"));mvc.perform(get("/api/invoices").session(financeSession)).andExpect(status().isUnauthorized());
 }
 @Test void wrongPasswordsTriggerPersistentLockoutAndInvalidDataFailsValidation()throws Exception{
  var account=account("Admin","Active");for(int i=0;i<5;i++)mvc.perform(post("/api/auth/login").with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(Map.of("email",account.getEmail(),"password","incorrect")))).andDo(r->System.out.println("LOGIN_VALIDATION: "+r.getResponse().getContentAsString())).andExpect(status().isUnauthorized());
  assertNotNull(accounts.findById(account.getId()).orElseThrow().getLockedUntil());mvc.perform(post("/api/auth/login").with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(Map.of("email",account.getEmail(),"password",PASSWORD)))).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/auth/signup").with(csrf().asHeader()).contentType("application/json").content("{\"name\":\"User\",\"email\":\"bad\",\"password\":\"short\",\"terms\":false}")).andExpect(status().isBadRequest());
 }
 @Test void resetEmailsArePrivateTokensExpireAndPasswordResetRevokesSessions()throws Exception{
  var account=account("Admin","Active");var session=login(account);
  mvc.perform(post("/api/auth/forgot-password").with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(Map.of("email",account.getEmail())))).andExpect(status().isOk());
  var message=emails.findByRecordKindAndRecordIdOrderByIdDesc("Auth",account.getId()).getFirst();String token=message.getBody().split("reset-password/")[1].split("\n")[0];assertEquals(43,token.length());assertFalse(resets.findAll().getFirst().getTokenHash().equals(token));
  mvc.perform(get("/api/emails").session(session).param("kind","Auth").param("recordId",account.getId().toString())).andExpect(status().isForbidden());
  String newPassword="ChangedPassword456!";mvc.perform(post("/api/auth/reset-password").with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(Map.of("token",token,"password",newPassword)))).andExpect(status().isOk());
  assertTrue(encoder.matches(newPassword,accounts.findById(account.getId()).orElseThrow().getPasswordHash()));mvc.perform(get("/api/vehicles").session(session)).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/auth/reset-password").with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(Map.of("token",token,"password",PASSWORD)))).andExpect(status().isBadRequest());
  service.forgot(account.getEmail());var reset=resets.findAll().getFirst();reset.setExpiresAt(Instant.now().minusSeconds(1));resets.save(reset);var expired=emails.findByRecordKindAndRecordIdOrderByIdDesc("Auth",account.getId()).getFirst().getBody().split("reset-password/")[1].split("\n")[0];
  mvc.perform(post("/api/auth/reset-password").with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(Map.of("token",expired,"password",PASSWORD)))).andExpect(status().isBadRequest());
 }
}
