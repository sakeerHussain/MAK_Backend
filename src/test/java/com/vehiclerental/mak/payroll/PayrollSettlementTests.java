package com.vehiclerental.mak.payroll;

import com.vehiclerental.mak.auth.entity.Account;
import com.vehiclerental.mak.auth.repository.AccountRepository;
import com.vehiclerental.mak.auth.security.*;
import com.vehiclerental.mak.auth.service.RoleService;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.employees.entity.Employee;
import com.vehiclerental.mak.employees.repository.EmployeeRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static com.vehiclerental.mak.payroll.PayrollData.*;

@SpringBootTest @Transactional
class PayrollSettlementTests {
    @Autowired PayrollService payroll; @Autowired PayrollSettlementService settlement;
    @Autowired EmployeeRepository employees; @Autowired AccountRepository accounts;
    @Autowired RoleService roles; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    @Autowired WebApplicationContext context;
    Account admin; Employee employee; MockMvc mvc; long payrollId;
    @BeforeEach void setup() {
        admin=account("Admin",null);
        employee=new Employee();employee.setEmployeeId("PAYSET-"+UUID.randomUUID().toString().substring(0,8));employee.setName("Payroll settlement QA");employee.setPhone("+966500000088");employee.setPhoneKey(UUID.randomUUID().toString());employee.setCategory("Technical");employee.setJobTitle("Driver");employee.setDepartment("Transport");employee.setEmploymentType("Full-time");employee.setStatus("Active");employee.setMonthlySalary(bd("9999"));employees.saveAndFlush(employee);
        var profile=payroll.saveProfile(null,new ProfileRequest(null,"EMPLOYEE",employee.getId(),"2026-07",bd("800"),bd("800"),"","","","",List.of(new Component("Food",bd("400"),"CASH"),new Component("Profession",bd("300"),"CASH"),new Component("Performance",bd("300"),"CASH"),new Component("Room provided",bd("500"),"BENEFIT")),""),admin.getId());
        payrollId=((Number)payroll.prepare(new DraftRequest("2026-07",List.of(((Number)profile.get("payeeId")).longValue())),admin.getId()).getFirst().get("id")).longValue();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void cleanupCommittedFixture() {
        if(TransactionSynchronizationManager.isActualTransactionActive())return;
        var payees=jdbc.queryForList("select id from payroll_payees where staff_type='EMPLOYEE' and staff_id=?",employee.getId());
        for(var payee:payees){var drafts=jdbc.queryForList("select id from payroll_drafts where payee_id=?",payee.get("id"));for(var draft:drafts){jdbc.update("delete from payroll_payments where payroll_id=?",draft.get("id"));jdbc.update("update payroll_drafts set approval_id=null where id=?",draft.get("id"));jdbc.update("delete from payroll_approvals where payroll_id=?",draft.get("id"));jdbc.update("delete from payroll_drafts where id=?",draft.get("id"));}jdbc.update("delete from payroll_profiles where payee_id=?",payee.get("id"));jdbc.update("delete from payroll_payees where id=?",payee.get("id"));}
        employees.deleteById(employee.getId());
    }
    static BigDecimal bd(String value){return new BigDecimal(value);}
    Account account(String role,Long custom){var a=new Account();a.setName("Payroll tester");a.setEmail(UUID.randomUUID()+"@example.invalid");a.setPasswordHash("test-no-login");a.setRole(role);a.setCustomRoleId(custom);a.setStatus("Active");a.setCreatedAt(Instant.now());return accounts.saveAndFlush(a);}
    MockHttpSession session(Account a){var s=new MockHttpSession();s.setAttribute("SPRING_SECURITY_CONTEXT",new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(new AccountPrincipal(a.getId(),a.getSessionVersion()),null,List.of())));return s;}
    MockHttpSession custom(String... permissions){var r=roles.save(null,new LinkedHashMap<>(Map.of("name","Payroll test role "+UUID.randomUUID(),"permissions",List.of(permissions))),admin.getId());return session(account("Viewer",((Number)r.get("id")).longValue()));}
    long version(){return ((Number)payroll.get(payrollId).get("version")).longValue();}
    void approve(){settlement.approve(payrollId,new ApprovalRequest(version(),"Reviewed allowance split and monthly adjustments.",true),admin.getId());}
    PaymentRequest pay(long version,String key,String method,String amount,String reference){return new PaymentRequest(version,key,method,bd(amount),LocalDate.of(2026,7,31),reference,"Recorded QA payment",method.equals("BANK")?new ReceivingAccount("QA Employee","QA Test Bank","","QA-TEST-ACCOUNT"):null,true);}
    void pay(String method,String amount,String reference){settlement.record(payrollId,pay(version(),UUID.randomUUID().toString(),method,amount,reference),null,admin.getId());}
    @SuppressWarnings("unchecked") List<Map<String,Object>> payments(){return (List<Map<String,Object>>)payroll.get(payrollId).get("payments");}
    void equalsMoney(String field,String expected){assertEquals(0,((BigDecimal)payroll.get(payrollId).get(field)).compareTo(bd(expected)),field);}
    long paymentId(int index){return ((Number)payments().get(index).get("id")).longValue();}

    @Test void approvalLocksEntitlementWithoutCountingBankAllocationOrBenefitsAsPayments(){
        assertNull(payroll.get(payrollId).get("outstanding"));approve();
        assertEquals("Approved",payroll.get(payrollId).get("status"));assertEquals("Unpaid",payroll.get(payrollId).get("paymentStatus"));equalsMoney("approvedNetCash","1800");equalsMoney("paid","0");equalsMoney("outstanding","1800");
        assertThrows(ApiException.class,()->payroll.update(payrollId,new DraftUpdate(version(),List.of(),"Overwrite approved"),admin.getId()));assertThrows(ApiException.class,()->payroll.discard(payrollId,version(),admin.getId()));assertThrows(ApiException.class,this::approve);
    }
    @Test void splitPaymentsCalculateOutstandingAndPaidStatus(){
        approve();pay("BANK","800","BANK-001");equalsMoney("bankPaid","800");equalsMoney("cashPaid","0");equalsMoney("outstanding","1000");assertEquals("Partially paid",payroll.get(payrollId).get("status"));
        pay("CASH","1000","VOUCHER-001");equalsMoney("paid","1800");equalsMoney("outstanding","0");assertEquals("Paid",payroll.get(payrollId).get("status"));assertThrows(ApiException.class,()->pay("CASH","0.01","OVERPAY"));
    }
    @Test void paymentsRequireApprovalAndApprovalRequiresExplicitReview(){
        assertThrows(ApiException.class,()->pay("CASH","1","BEFORE-APPROVAL"));
        assertThrows(ApiException.class,()->settlement.approve(payrollId,new ApprovalRequest(version(),"Reviewed",false),admin.getId()));
        assertThrows(ApiException.class,()->settlement.approve(payrollId,new ApprovalRequest(version(),"",true),admin.getId()));
        payroll.update(payrollId,new DraftUpdate(0L,List.of(),"Changed"),admin.getId());
        assertThrows(ApiException.class,()->settlement.approve(payrollId,new ApprovalRequest(0L,"Stale approval",true),admin.getId()));
    }
    @Test void identicalNetworkRetryIsIdempotentButChangedPayloadIsRejected(){
        approve();String key=UUID.randomUUID().toString();var payment=pay(version(),key,"BANK","800","RETRY-001");settlement.record(payrollId,payment,null,admin.getId());long after=version();settlement.record(payrollId,payment,null,admin.getId());assertEquals(after,version());assertEquals(1,payments().size());equalsMoney("paid","800");
        assertEquals(409,assertThrows(ApiException.class,()->settlement.record(payrollId,pay(after,key,"BANK","801","RETRY-001"),null,admin.getId())).status().value());
    }
    @Test void repeatedPaymentReferenceIsRejectedAndReversalReleasesIt(){
        approve();pay("BANK","800"," REF-001 ");assertThrows(ApiException.class,()->pay("BANK","800","ref-001"));
        settlement.reverse(payrollId,paymentId(0),new CorrectionRequest(version(),"Wrong amount, correcting record only"),admin.getId());
        pay("BANK","800","REF-001");assertEquals(2,payments().size());assertNotNull(payments().getFirst().get("reversedAt"));equalsMoney("paid","800");
    }
    @Test void reversalRestoresBalanceAndPreservesOriginalPaymentAndProof() throws Exception {
        approve();var receipt=new MockMultipartFile("receipt","proof.pdf","application/pdf","%PDF-1.7\nQA proof".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        settlement.record(payrollId,pay(version(),UUID.randomUUID().toString(),"BANK","800","PDF-001"),receipt,admin.getId());long payment=paymentId(0);var before=settlement.receipt(payrollId,payment);assertEquals("application/pdf",before.contentType());
        settlement.reverse(payrollId,payment,new CorrectionRequest(version(),"External bank transaction was reversed"),admin.getId());equalsMoney("outstanding","1800");assertEquals("Unpaid",payroll.get(payrollId).get("paymentStatus"));assertNotNull(payments().getFirst().get("receipt"));assertEquals(before.resource().contentLength(),settlement.receipt(payrollId,payment).resource().contentLength());assertThrows(ApiException.class,()->settlement.reverse(payrollId,payment,new CorrectionRequest(version(),"Duplicate reversal"),admin.getId()));
    }
    @Test void paidPayrollCannotReopenUntilRecordedPaymentsAreReversed(){
        approve();pay("CASH","1800","CASH-ALL");assertThrows(ApiException.class,()->settlement.reopen(payrollId,new CorrectionRequest(version(),"Salary correction"),admin.getId()));
        settlement.reverse(payrollId,paymentId(0),new CorrectionRequest(version(),"Incorrect payment entry"),admin.getId());settlement.reopen(payrollId,new CorrectionRequest(version(),"Correct bonus before reapproval"),admin.getId());
        assertEquals("Draft",payroll.get(payrollId).get("status"));assertFalse((Boolean)payroll.get(payrollId).get("canDiscard"));assertThrows(ApiException.class,()->payroll.discard(payrollId,version(),admin.getId()));
        payroll.update(payrollId,new DraftUpdate(version(),List.of(new Adjustment("Bonus","ADDITION",bd("200"),"CASH","Approved correction")),"Corrected"),admin.getId());approve();equalsMoney("approvedNetCash","2000");equalsMoney("outstanding","2000");
        @SuppressWarnings("unchecked")var approvals=(List<Map<String,Object>>)payroll.get(payrollId).get("approvals");assertEquals(2,approvals.size());assertEquals(0,((BigDecimal)approvals.getFirst().get("netCash")).compareTo(bd("1800")));assertNotNull(approvals.getFirst().get("reopenedAt"));
    }
    @Test void correctionReasonIsRequiredAndStaleActionsAreRejected(){
        approve();assertThrows(ApiException.class,()->settlement.reopen(payrollId,new CorrectionRequest(version(),""),admin.getId()));pay("CASH","100","ONE");
        assertThrows(ApiException.class,()->settlement.reverse(payrollId,paymentId(0),new CorrectionRequest(0L,"Stale"),admin.getId()));assertThrows(ApiException.class,()->settlement.reverse(payrollId,paymentId(0),new CorrectionRequest(version(),""),admin.getId()));equalsMoney("paid","100");
    }
    @Test void zeroNetPayrollApprovesWithoutInventingAPayment(){
        payroll.update(payrollId,new DraftUpdate(version(),List.of(new Adjustment("Manual approved deduction","DEDUCTION",bd("1800"),"CASH","Verified zero entitlement example")),""),admin.getId());approve();assertEquals("No payment due",payroll.get(payrollId).get("paymentStatus"));equalsMoney("outstanding","0");assertTrue(payments().isEmpty());
    }
    @Test void paymentAmountDateMethodAndAccountValidation(){
        approve();var request=pay(version(),UUID.randomUUID().toString(),"BANK","800","VALIDATION");
        assertThrows(ApiException.class,()->settlement.record(payrollId,new PaymentRequest(request.version(),request.requestKey(),"BANK",request.amount(),request.paidOn(),request.reference(),"",null,true),null,admin.getId()));
        assertThrows(ApiException.class,()->settlement.record(payrollId,new PaymentRequest(request.version(),request.requestKey(),"BANK",request.amount(),request.paidOn(),request.reference(),"",new ReceivingAccount("Name","Bank","SA123",""),true),null,admin.getId()));
        assertThrows(ApiException.class,()->settlement.record(payrollId,new PaymentRequest(request.version(),request.requestKey(),"CASH",request.amount(),request.paidOn(),request.reference(),"",null,false),null,admin.getId()));
        assertThrows(ApiException.class,()->settlement.record(payrollId,new PaymentRequest(request.version(),request.requestKey(),"CASH",request.amount(),LocalDate.now(ZoneId.of("Asia/Riyadh")).plusDays(1),request.reference(),"",null,true),null,admin.getId()));
        for(String amount:List.of("0","-1","0.001","1800.01"))assertThrows(ApiException.class,()->pay("CASH",amount,"BAD-AMOUNT"));
        assertThrows(ApiException.class,()->settlement.record(payrollId,new PaymentRequest(request.version(),request.requestKey(),"BENEFIT",bd("1"),request.paidOn(),"NONCASH","",null,true),null,admin.getId()));assertTrue(payments().isEmpty());
    }
    @Test void activeAndInactiveStaffEligibilityIsRecheckedAtApproval(){employee.setStatus("Inactive");employees.saveAndFlush(employee);assertThrows(ApiException.class,this::approve);}
    @Test void receiptsCanBeAddedLaterButNotReplacedOrAttachedToAnotherPayroll() throws Exception {
        approve();pay("CASH","100","PROOF-LATER");long payment=paymentId(0);var receipt=new MockMultipartFile("receipt","voucher.png","image/png",new byte[]{(byte)137,80,78,71,13,10,26,10,1,2});settlement.attachReceipt(payrollId,payment,version(),receipt,admin.getId());assertNotNull(payments().getFirst().get("receipt"));assertThrows(ApiException.class,()->settlement.attachReceipt(payrollId,payment,version(),receipt,admin.getId()));assertThrows(ApiException.class,()->settlement.receipt(payrollId+9999,payment));
        var reader=custom("payroll.read");var response=mvc.perform(get("/api/payroll/drafts/"+payrollId+"/payments/"+payment+"/receipt?download=true").session(reader)).andReturn().getResponse();assertEquals(200,response.getStatus());assertTrue(response.getHeader("Content-Disposition").startsWith("attachment"));assertEquals("image/png",response.getContentType());assertEquals("private, no-store",response.getHeader("Cache-Control"));
        assertEquals(403,mvc.perform(get("/api/payroll/drafts/"+payrollId+"/payments/"+payment+"/receipt").session(custom("invoices.read"))).andReturn().getResponse().getStatus());
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void unsupportedReceiptRollsBackPaymentAndVersion() {
        approve();long before=version();assertThrows(ApiException.class,()->settlement.record(payrollId,pay(before,UUID.randomUUID().toString(),"CASH","100","BAD-PROOF"),new MockMultipartFile("receipt","file.txt","text/plain","Not a permitted proof file".getBytes(java.nio.charset.StandardCharsets.UTF_8)),admin.getId()));assertEquals(before,version());assertTrue(payments().isEmpty());equalsMoney("outstanding","1800");
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentPaymentsCannotOverpayTheSameSalary() throws Exception {
        approve();long before=version();var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try{var tasks=new ArrayList<Future<String>>();for(int i=0;i<2;i++){final int n=i;tasks.add(executor.submit(()->{start.await();try{settlement.record(payrollId,pay(before,UUID.randomUUID().toString(),"CASH","1000","CONCURRENT-"+n),null,admin.getId());return "saved";}catch(ApiException error){return "conflict";}}));}start.countDown();var results=List.of(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS));assertEquals(1,results.stream().filter("saved"::equals).count());assertEquals(1,payments().size());equalsMoney("paid","1000");equalsMoney("outstanding","800");}finally{executor.shutdownNow();}
    }
    @Test void approvalPaymentAndCorrectionPermissionsAreIndependent() throws Exception {
        String base="/api/payroll/drafts/"+payrollId;
        var editor=custom("payroll.read","payroll.edit");
        assertEquals(403,mvc.perform(post(base+"/approve").session(editor).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsString(new ApprovalRequest(version(),"Reviewed",true)))).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post(base+"/approve").session(custom("payroll.read","payroll.approve")).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsString(new ApprovalRequest(version(),"Reviewed",true)))).andReturn().getResponse().getStatus());
        var data=pay(version(),UUID.randomUUID().toString(),"CASH","100","PERMISSION");
        assertEquals(403,mvc.perform(post(base+"/payments").session(editor).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsString(data))).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post(base+"/payments").session(custom("payroll.read","payroll.pay")).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsString(data))).andReturn().getResponse().getStatus());
        String reverse=base+"/payments/"+paymentId(0)+"/reverse";String correction=json.writeValueAsString(new CorrectionRequest(version(),"Wrong payment record"));
        assertEquals(403,mvc.perform(post(reverse).session(custom("payroll.read","payroll.pay")).with(csrf().asHeader()).contentType("application/json").content(correction)).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post(reverse).session(custom("payroll.read","payroll.correct")).with(csrf().asHeader()).contentType("application/json").content(correction)).andReturn().getResponse().getStatus());
    }
    @Test void receiptUploadRequiresPaymentPermissionAndCsrf() throws Exception {
        approve();pay("CASH","100","UPLOAD");String path="/api/payroll/drafts/"+payrollId+"/payments/"+paymentId(0)+"/receipt";
        var file=new MockMultipartFile("receipt","proof.pdf","application/pdf","%PDF-1.7\nTest proof".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(403,mvc.perform(multipart(path).file(file).param("version",String.valueOf(version())).session(custom("payroll.read","payroll.edit")).with(csrf().asHeader())).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(multipart(path).file(file).param("version",String.valueOf(version())).session(custom("payroll.read","payroll.pay"))).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(multipart(path).file(file).param("version",String.valueOf(version())).session(custom("payroll.read","payroll.pay")).with(csrf().asHeader())).andReturn().getResponse().getStatus());
    }
    @Test void invoiceWriteCannotExpandIntoPayrollAuthorityAndCustomGrantsNeedPayrollRead(){
        var invoice=RolePolicy.expand(Set.of("invoices.write"));assertTrue(invoice.contains("invoices.issue"));assertFalse(invoice.contains("payroll.approve"));assertFalse(invoice.contains("payroll.pay"));
        for(String permission:List.of("payroll.approve","payroll.pay","payroll.correct"))assertThrows(ApiException.class,()->roles.save(null,new LinkedHashMap<>(Map.of("name","Missing view "+UUID.randomUUID(),"permissions",List.of(permission))),admin.getId()));
        var finance=roles.permissions(account("Finance",null));assertTrue(finance.containsAll(Set.of("payroll.approve","payroll.pay","payroll.correct")));assertFalse(roles.permissions(account("Manager",null)).contains("payroll.approve"));
    }
}
