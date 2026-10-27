package com.vehiclerental.mak.payroll;

import com.vehiclerental.mak.auth.entity.Account;
import com.vehiclerental.mak.auth.repository.AccountRepository;
import com.vehiclerental.mak.auth.security.AccountPrincipal;
import com.vehiclerental.mak.auth.service.RoleService;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.employees.entity.Employee;
import com.vehiclerental.mak.employees.repository.EmployeeRepository;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.junit.jupiter.api.Assertions.*;
import static com.vehiclerental.mak.payroll.PayrollData.*;

@SpringBootTest @Transactional
class PayrollPayslipTests {
    @Autowired PayrollService payroll; @Autowired PayrollSettlementService settlement;
    @Autowired PayrollPayslipService payslips; @Autowired PayrollPayslipPdf pdf;
    @Autowired EmployeeRepository employees; @Autowired AccountRepository accounts;
    @Autowired RoleService roles; @Autowired JdbcTemplate jdbc; @Autowired WebApplicationContext context;
    Account admin; long id; MockMvc mvc;
    static BigDecimal bd(String s){return new BigDecimal(s);}
    @BeforeEach void setup(){
        admin=account("Admin",null);
        var employee=new Employee();employee.setEmployeeId("PAYSLIP-"+UUID.randomUUID().toString().substring(0,8));employee.setName("Payslip QA Employee");employee.setPhone("+966500000099");employee.setPhoneKey(UUID.randomUUID().toString());employee.setCategory("Technical");employee.setJobTitle("Driver");employee.setDepartment("Transport");employee.setEmploymentType("Full-time");employee.setStatus("Active");employee.setMonthlySalary(bd("0"));employees.saveAndFlush(employee);
        var profile=payroll.saveProfile(null,new ProfileRequest(null,"EMPLOYEE",employee.getId(),"2026-07",bd("800"),bd("800"),"PRIVATE ACCOUNT HOLDER","PRIVATE BANK","","PRIVATE ACCOUNT NUMBER",List.of(new Component("Food",bd("400"),"CASH"),new Component("Profession",bd("300"),"CASH"),new Component("Performance",bd("300"),"CASH"),new Component("Room provided",bd("500"),"BENEFIT")),"PRIVATE PROFILE NOTE"),admin.getId());
        id=((Number)payroll.prepare(new DraftRequest("2026-07",List.of(((Number)profile.get("payeeId")).longValue())),admin.getId()).getFirst().get("id")).longValue();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    Account account(String role,Long custom){var a=new Account();a.setName("Payroll approver");a.setEmail(UUID.randomUUID()+"@example.invalid");a.setPasswordHash("test-no-login");a.setRole(role);a.setCustomRoleId(custom);a.setStatus("Active");a.setCreatedAt(Instant.now());return accounts.saveAndFlush(a);}
    MockHttpSession custom(String permission){var r=roles.save(null,new LinkedHashMap<>(Map.of("name","Payslip QA "+UUID.randomUUID(),"permissions",List.of(permission))),admin.getId());var a=account("Viewer",((Number)r.get("id")).longValue());var s=new MockHttpSession();s.setAttribute("SPRING_SECURITY_CONTEXT",new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(new AccountPrincipal(a.getId(),a.getSessionVersion()),null,List.of())));return s;}
    long version(){return ((Number)payroll.get(id).get("version")).longValue();}
    void approve(){settlement.approve(id,new ApprovalRequest(version(),"PRIVATE APPROVAL REVIEW",true),admin.getId());}
    void pay(String method,String amount,String ref){settlement.record(id,new PaymentRequest(version(),UUID.randomUUID().toString(),method,bd(amount),LocalDate.of(2026,7,31),ref,"PRIVATE PAYMENT NOTE",method.equals("BANK")?new ReceivingAccount("PRIVATE ACCOUNT HOLDER","PRIVATE BANK","","PRIVATE ACCOUNT NUMBER"):null,true),null,admin.getId());}
    String text(byte[] bytes)throws Exception{try(var doc=Loader.loadPDF(bytes)){return new PDFTextStripper().getText(doc);}}
    byte[] export(String name)throws Exception{var bytes=pdf.render(payslips.snapshot(id));String dir=System.getProperty("payroll.pdf.qaDir");if(dir!=null){Files.createDirectories(Path.of(dir));Files.write(Path.of(dir,name+".pdf"),bytes);}return bytes;}
    @Test void draftsAndReopenedPayrollCannotExport(){
        assertEquals(409,assertThrows(ApiException.class,()->payslips.snapshot(id)).status().value());approve();settlement.reopen(id,new CorrectionRequest(version(),"Correct salary"),admin.getId());assertThrows(ApiException.class,()->payslips.snapshot(id));
        assertEquals(404,assertThrows(ApiException.class,()->payslips.snapshot(Long.MAX_VALUE)).status().value());
    }
    @Test void unpaidPayslipUsesApprovedSalaryAndDoesNotTreatBankAllocationOrBenefitsAsPayment()throws Exception{
        approve();var slip=payslips.snapshot(id);assertEquals("UNPAID",slip.paymentStatus());assertEquals(0,slip.outstanding().compareTo(bd("1800")));assertEquals(0,slip.paid().signum());
        // A mutable draft row must never be the source of approved salary figures.
        jdbc.update("update payroll_drafts set snapshot='{}',adjustments='[]',notes='PRIVATE DRAFT NOTE' where id=?",id);
        String result=text(export("unpaid"));for(String expected:List.of("UNPAID","Basic salary","Food","Profession","Performance","Room provided","1800.00","500.00","Outstanding cash salary","Fixed bank allocation","not a deduction","KSA","July 2026"))assertTrue(result.contains(expected),expected);
        for(String secret:List.of("PRIVATE ACCOUNT HOLDER","PRIVATE BANK","PRIVATE ACCOUNT NUMBER","PRIVATE PROFILE NOTE","PRIVATE APPROVAL REVIEW","PRIVATE DRAFT NOTE","SA08"))assertFalse(result.contains(secret),secret);
    }
    @Test void splitPaymentsAndReversalAreReflectedWithoutChangingApprovedEntitlement()throws Exception{
        approve();pay("BANK","800","QA-BANK-800");assertEquals("PARTIALLY PAID",payslips.snapshot(id).paymentStatus());assertEquals(0,payslips.snapshot(id).outstanding().compareTo(bd("1000")));String partial=text(export("partial"));assertTrue(partial.contains("QA-BANK-800"));assertFalse(partial.contains("PRIVATE PAYMENT NOTE"));
        pay("CASH","1000","QA-CASH-1000");assertEquals("PAID",payslips.snapshot(id).paymentStatus());assertEquals(0,payslips.snapshot(id).outstanding().signum());assertTrue(text(export("paid")).contains("Total payments recorded"));
        long payment=((Number)jdbc.queryForMap("select id from payroll_payments where payroll_id=? and method='CASH'",id).get("id")).longValue();settlement.reverse(id,payment,new CorrectionRequest(version(),"QA entry correction"),admin.getId());
        var reversed=payslips.snapshot(id);assertEquals(0,reversed.cashPaid().signum());assertEquals(0,reversed.outstanding().compareTo(bd("1000")));assertTrue(text(export("reversed")).contains("Cash / Reversed"));assertEquals(2,reversed.payments().size());
    }
    @Test void deductionsAndMonthlyBenefitsReconcileAndZeroNetIsNotPaid()throws Exception{
        payroll.update(id,new DraftUpdate(version(),List.of(new Adjustment("Bonus","ADDITION",bd("200.25"),"CASH","Reviewed"),new Adjustment("Deduction","DEDUCTION",bd("50.10"),"CASH","Reviewed"),new Adjustment("Meals provided","ADDITION",bd("100"),"BENEFIT","Reviewed")),""),admin.getId());approve();var slip=payslips.snapshot(id);assertEquals(0,slip.approval().totals().netCash().compareTo(bd("1950.15")));assertEquals(0,slip.approval().totals().benefits().compareTo(bd("600")));String result=text(export("adjusted"));assertTrue(result.contains("1950.15"));assertTrue(result.contains("Meals provided"));
        settlement.reopen(id,new CorrectionRequest(version(),"QA zero entitlement"),admin.getId());payroll.update(id,new DraftUpdate(version(),List.of(new Adjustment("Deduction","DEDUCTION",bd("1800"),"CASH","Reviewed")),""),admin.getId());approve();assertEquals("NO PAYMENT DUE",payslips.snapshot(id).paymentStatus());export("zero-net");
    }
    @Test void endpointRequiresPayrollReadAndReturnsPrivatePdfWithoutChangingLedger()throws Exception{
        approve();long before=version();var response=mvc.perform(get("/api/payroll/drafts/"+id+"/payslip").session(custom("payroll.read"))).andReturn().getResponse();assertEquals(200,response.getStatus());assertEquals("application/pdf",response.getContentType());assertTrue(response.getHeader("Content-Disposition").contains("MAK-payslip-2026-07-"+id+".pdf"));assertEquals("private, no-store",response.getHeader("Cache-Control"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));assertTrue(text(response.getContentAsByteArray()).contains("UNPAID"));assertEquals(before,version());assertTrue(payslips.snapshot(id).payments().isEmpty());
        assertEquals(403,mvc.perform(get("/api/payroll/drafts/"+id+"/payslip").session(custom("invoices.read"))).andReturn().getResponse().getStatus());assertEquals(401,mvc.perform(get("/api/payroll/drafts/"+id+"/payslip")).andReturn().getResponse().getStatus());
    }
    @Test void longRowsAndManyPaymentsPaginateWithoutLosingText()throws Exception{
        var changes=new ArrayList<Adjustment>();for(int i=1;i<=40;i++)changes.add(new Adjustment("Allowance "+i+" "+"Extended description ".repeat(8),"ADDITION",bd("1"),"CASH","Reviewed"));
        // Renderer accepts the validated service snapshot; use many rows to exercise pagination independently of form limits.
        approve();var source=payslips.snapshot(id);var salary=source.approval().salary();var totals=PayrollCalculator.calculate(salary,changes);var approval=new PayrollPayslipService.ApprovalSnapshot(salary,changes,"",totals);
        var large=new PayrollPayslipService.Payslip(source.id(),source.approvalId(),source.version(),source.reference(),source.period(),"Employee "+"Long name ".repeat(35),source.staffNumber(),approval,source.approvedAt(),source.approvedBy(),source.employer(),source.payments(),bd("0"),bd("0"),bd("0"),totals.netCash(),"UNPAID",source.generatedAt());var bytes=pdf.render(large);try(var doc=Loader.loadPDF(bytes)){assertTrue(doc.getNumberOfPages()>1);String result=new PDFTextStripper().getText(doc);assertTrue(result.contains("Allowance 40"));assertTrue(result.contains("Outstanding cash salary"));assertTrue(result.contains("Page 1 of"));}
        String dir=System.getProperty("payroll.pdf.qaDir");if(dir!=null)Files.write(Path.of(dir,"long-payslip.pdf"),bytes);
    }
}
