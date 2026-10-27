package com.vehiclerental.mak.payroll;

import com.vehiclerental.mak.auth.entity.Account;
import com.vehiclerental.mak.auth.repository.AccountRepository;
import com.vehiclerental.mak.auth.security.AccountPrincipal;
import com.vehiclerental.mak.auth.service.RoleService;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.employees.entity.Employee;
import com.vehiclerental.mak.employees.repository.EmployeeRepository;
import com.vehiclerental.mak.employees.service.EmployeeService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static com.vehiclerental.mak.payroll.PayrollData.*;

@SpringBootTest @Transactional
class PayrollWorkflowTests {
    @Autowired PayrollService payroll;
    @Autowired EmployeeRepository employees;
    @Autowired EmployeeService employeeService;
    @Autowired AccountRepository accounts;
    @Autowired RoleService roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired WebApplicationContext context;
    Account admin; Employee employee; MockMvc mvc;
    @BeforeEach void setup() {
        admin = account("Admin", null);
        employee = new Employee(); employee.setEmployeeId("PAY-"+UUID.randomUUID().toString().substring(0,8)); employee.setName("Payroll Test Staff");
        employee.setPhone("+966500000001"); employee.setPhoneKey(UUID.randomUUID().toString()); employee.setCategory("Technical"); employee.setJobTitle("Driver"); employee.setDepartment("Transport"); employee.setEmploymentType("Full-time"); employee.setStatus("Active"); employee.setMonthlySalary(bd("9999")); employees.saveAndFlush(employee);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    Account account(String role, Long custom) { var a=new Account(); a.setName("Payroll test"); a.setEmail(UUID.randomUUID()+"@example.invalid"); a.setRole(role); a.setCustomRoleId(custom); a.setPasswordHash("test-no-login"); a.setStatus("Active"); a.setCreatedAt(Instant.now()); return accounts.saveAndFlush(a); }
    MockHttpSession session(Account a) { var s=new MockHttpSession(); s.setAttribute("SPRING_SECURITY_CONTEXT",new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(new AccountPrincipal(a.getId(),a.getSessionVersion()),null,List.of()))); return s; }
    MockHttpSession custom(String... grants) { var role=roles.save(null,new LinkedHashMap<>(Map.of("name","Payroll role "+UUID.randomUUID(),"permissions",List.of(grants))),admin.getId()); return session(account("Viewer",((Number)role.get("id")).longValue())); }
    static BigDecimal bd(String s) { return new BigDecimal(s); }
    ProfileRequest profile(String period) { return new ProfileRequest(null,"EMPLOYEE",employee.getId(),period,bd("800"),bd("800"),"","","","",List.of(new Component("Food",bd("400"),"CASH"),new Component("Profession",bd("300"),"CASH"),new Component("Performance",bd("300"),"CASH")),""); }
    ProfileRequest change(ProfileRequest p, Long version, String month, BigDecimal basic, BigDecimal bank, List<Component> components) { return new ProfileRequest(version,p.staffType(),p.staffId(),month,basic,bank,p.accountName(),p.bankName(),p.iban(),p.accountNumber(),components,p.notes()); }
    Map<String,Object> saveProfile() { return payroll.saveProfile(null,profile("2026-07"),admin.getId()); }
    long id(Map<String,Object> record) { return ((Number)record.get("id")).longValue(); }
    long payee(Map<String,Object> profile) { return ((Number)profile.get("payeeId")).longValue(); }
    Map<String,Object> prepare(Map<String,Object> profile, String period) { return payroll.prepare(new DraftRequest(period,List.of(payee(profile))),admin.getId()).getFirst(); }
    PayrollCalculator.Totals totals(Map<String,Object> draft) { return (PayrollCalculator.Totals)draft.get("totals"); }

    @Test void exampleCalculatesEntitlementWithoutTreatingBankAllocationAsDeduction() {
        var saved=saveProfile(); var draft=prepare(saved,"2026-07");
        assertEquals(bd("1800.00"), totals(draft).cashEarnings()); assertEquals(bd("1800.00"), totals(draft).netCash()); assertEquals(bd("800.00"), totals(draft).fixedBankAmount()); assertEquals(bd("0.00"), totals(draft).deductions()); assertEquals("Draft",draft.get("status"));
        assertEquals(bd("9999"), employees.findById(employee.getId()).orElseThrow().getMonthlySalary());
    }
    @Test void noncashBenefitsDoNotIncreaseCashEntitlementAndAdjustmentsUseDecimalArithmetic() {
        var p=profile("2026-07"); p=change(p,null,p.effectiveMonth(),p.basicSalary(),p.fixedBankAmount(),List.of(new Component("Room",bd("500"),"BENEFIT"),new Component("Food",bd("400"),"CASH")));
        var saved=payroll.saveProfile(null,p,admin.getId()); var draft=prepare(saved,"2026-07");
        var updated=payroll.update(id(draft),new DraftUpdate(0L,List.of(new Adjustment("Bonus","ADDITION",bd("0.10"),"BANK","Approved B1"),new Adjustment("Extra food","ADDITION",bd("0.20"),"CASH","Approved B2"),new Adjustment("Manual recovery","DEDUCTION",bd("100"),"CASH","Approved D1")),"Review"),admin.getId());
        assertEquals(bd("1200.30"),totals(updated).cashEarnings()); assertEquals(bd("1100.30"),totals(updated).netCash()); assertEquals(bd("500.00"),totals(updated).benefits());
    }
    @Test void snapshotsSurviveProfileEditsAndNewEffectiveVersions() {
        var saved=saveProfile(); var july=prepare(saved,"2026-07"); var p=profile("2026-07");
        payroll.saveProfile(id(saved),change(p,0L,"2026-07",bd("900"),bd("800"),p.components()),admin.getId());
        payroll.saveProfile(null,change(p,null,"2026-08",bd("1200"),bd("800"),p.components()),admin.getId());
        assertEquals(bd("2200.00"),totals(prepare(saved,"2026-08")).netCash());
        @SuppressWarnings("unchecked") var reloaded=(List<Map<String,Object>>)payroll.list("2026-07").get("drafts");
        assertEquals(bd("1800.00"),totals(reloaded.getFirst()).netCash()); assertTrue(reloaded.getFirst().get("warnings").toString().contains("original salary snapshot")); assertEquals(id(july),id(reloaded.getFirst()));
    }
    @Test void noProfileBeforeEffectiveMonthAndNoDuplicateStaffMonth() {
        var saved=saveProfile(); assertThrows(ApiException.class,()->prepare(saved,"2026-06")); prepare(saved,"2026-07");
        assertEquals(409,assertThrows(ApiException.class,()->prepare(saved,"2026-07")).status().value());
        assertEquals(1L,jdbc.queryForObject("select count(*) from payroll_drafts where payee_id=?",Long.class,payee(saved)));
    }
    @Test void staleUpdatesAndStaleDiscardAreRejected() {
        var draft=prepare(saveProfile(),"2026-07"); payroll.update(id(draft),new DraftUpdate(0L,List.of(),"First change"),admin.getId());
        assertEquals(409,assertThrows(ApiException.class,()->payroll.update(id(draft),new DraftUpdate(0L,List.of(),"Lost change"),admin.getId())).status().value());
        assertThrows(ApiException.class,()->payroll.discard(id(draft),0,admin.getId()));
    }
    @Test void discardAllowsReprepareWithCurrentSalaryAndPreservesAudit() {
        var saved=saveProfile(); var draft=prepare(saved,"2026-07"); payroll.discard(id(draft),0,admin.getId());
        assertNotEquals(id(draft),id(prepare(saved,"2026-07")));
        assertTrue(jdbc.queryForObject("select count(*) from auth_audit where actor_id=? and event='PAYROLL_DRAFT_DISCARDED'",Long.class,admin.getId())>0);
    }
    @Test void validationPreventsNegativePayInventedAmountsAndBankPaymentDeductions() {
        var draft=prepare(saveProfile(),"2026-07");
        assertThrows(ApiException.class,()->payroll.update(id(draft),new DraftUpdate(0L,List.of(new Adjustment("Too much","DEDUCTION",bd("1800.01"),"CASH","Verified")),""),admin.getId()));
        assertThrows(ApiException.class,()->payroll.update(id(draft),new DraftUpdate(0L,List.of(new Adjustment("Payroll","DEDUCTION",bd("800"),"BANK","Already paid")),""),admin.getId()));
        assertThrows(ApiException.class,()->payroll.update(id(draft),new DraftUpdate(0L,List.of(new Adjustment("Bonus","ADDITION",bd("0.001"),"CASH","Verified")),""),admin.getId()));
        assertThrows(ApiException.class,()->payroll.update(id(draft),new DraftUpdate(0L,List.of(new Adjustment("Bonus","ADDITION",bd("100"),"CASH","")),""),admin.getId()));
    }
    @Test void profileRequiresAmountsAndRejectsInvalidBankAllocation() {
        var p=profile("2026-07");
        assertThrows(ApiException.class,()->payroll.saveProfile(null,change(p,null,"2026-07",null,bd("0"),p.components()),admin.getId()));
        assertThrows(ApiException.class,()->payroll.saveProfile(null,change(p,null,"2026-07",bd("800"),bd("1801"),p.components()),admin.getId()));
        assertThrows(ApiException.class,()->payroll.saveProfile(null,profile("2026-13"),admin.getId()));
    }
    @Test void inactiveStaffCannotBePreparedAndStaffWithPayrollCannotBeDeleted() {
        var saved=saveProfile(); assertThrows(ApiException.class,()->employeeService.archive(employee.getId()));
        employee.setStatus("Inactive");employees.saveAndFlush(employee); assertThrows(ApiException.class,()->prepare(saved,"2026-07"));
    }
    @Test void batchesAreAtomicWhenOneSelectedStaffAlreadyHasDraft() {
        var saved=saveProfile();prepare(saved,"2026-07");
        assertThrows(ApiException.class,()->payroll.prepare(new DraftRequest("2026-07",List.of(payee(saved),9999999L)),admin.getId()));
        assertEquals(1L,jdbc.queryForObject("select count(*) from payroll_drafts where payee_id=?",Long.class,payee(saved)));
    }
    @Test void duplicateStaffNumberAcrossEmployeeAndDriverCannotCreateSecondPayee() {
        saveProfile();
        jdbc.update("insert into drivers(employee_id,name,phone,phone_key,category,designation,employment_type,status,daily_rate,booking_count,payment_count,archived) values(?,?,?,?,?,?,?,?,?,0,0,false)",employee.getEmployeeId(),"Driver duplicate","+966500000002",UUID.randomUUID().toString(),"Light","Driver","Full-time","Active",bd("100"));
        long driverId=jdbc.queryForObject("select id from drivers where employee_id=?",Long.class,employee.getEmployeeId());
        var p=profile("2026-07");var duplicate=new ProfileRequest(null,"DRIVER",driverId,p.effectiveMonth(),p.basicSalary(),p.fixedBankAmount(),"","","","",p.components(),"");
        assertEquals(409,assertThrows(ApiException.class,()->payroll.saveProfile(null,duplicate,admin.getId())).status().value());
    }
    @Test void payrollReadDoesNotRequireEmployeeReadAndCannotWrite() throws Exception {
        var reader=custom("payroll.read");
        assertEquals(200,mvc.perform(get("/api/payroll?period=2026-07").session(reader)).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get("/api/employees").session(reader)).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post("/api/payroll/profiles").session(reader).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsString(profile("2026-07")))).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get("/api/payroll?period=2026-07").session(session(account("Operations",null)))).andReturn().getResponse().getStatus());
    }
    @Test void invoicePermissionDoesNotGrantPayrollAndFinanceCanAccess() throws Exception {
        assertEquals(403,mvc.perform(get("/api/payroll?period=2026-07").session(custom("invoices.read","invoices.edit"))).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(get("/api/payroll?period=2026-07").session(session(account("Finance",null)))).andReturn().getResponse().getStatus());
    }
    @Test void createEditAndDiscardGrantsAreDistinctAndCsrfIsRequired() throws Exception {
        var creator=custom("payroll.read","payroll.create");
        var response=mvc.perform(post("/api/payroll/profiles").session(creator).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsString(profile("2026-07")))).andReturn().getResponse();assertEquals(200,response.getStatus());
        var saved=json.readValue(response.getContentAsString(),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){});
        assertEquals(403,mvc.perform(put("/api/payroll/profiles/"+id(saved)).session(creator).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsString(profile("2026-07")))).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post("/api/payroll/drafts").session(creator).contentType("application/json").content(json.writeValueAsString(new DraftRequest("2026-07",List.of(payee(saved)))))).andReturn().getResponse().getStatus());
        var draft=prepare(saved,"2026-07");
        assertEquals(403,mvc.perform(delete("/api/payroll/drafts/"+id(draft)+"?version=0").session(custom("payroll.read","payroll.edit")).with(csrf().asHeader())).andReturn().getResponse().getStatus());
        assertEquals(204,mvc.perform(delete("/api/payroll/drafts/"+id(draft)+"?version=0").session(custom("payroll.read","payroll.archive")).with(csrf().asHeader())).andReturn().getResponse().getStatus());
    }
}
