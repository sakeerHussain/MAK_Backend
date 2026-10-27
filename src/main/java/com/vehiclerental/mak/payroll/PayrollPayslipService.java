package com.vehiclerental.mak.payroll;

import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.invoices.service.BillingProfileService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.vehiclerental.mak.payroll.PayrollData.*;
import static com.vehiclerental.mak.payroll.PayrollCalculator.*;

@Service
public class PayrollPayslipService {
    public record ApprovalSnapshot(ProfileRequest salary,List<Adjustment> adjustments,String notes,Totals totals) {}
    public record Payment(String method,BigDecimal amount,LocalDate paidOn,String reference,boolean reversed) {}
    public record Payslip(long id,long approvalId,long version,String reference,String period,String name,String staffNumber,
        ApprovalSnapshot approval,Instant approvedAt,String approvedBy,Map<String,String> employer,List<Payment> payments,
        BigDecimal bankPaid,BigDecimal cashPaid,BigDecimal paid,BigDecimal outstanding,String paymentStatus,Instant generatedAt) {
        public String filename(){return "MAK-payslip-"+period+"-"+id+".pdf";}
    }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BillingProfileService billing;
    public PayrollPayslipService(JdbcTemplate jdbc,ObjectMapper json,BillingProfileService billing){this.jdbc=jdbc;this.json=json;this.billing=billing;}

    /** Capture one consistent approved salary/payment position, then release the lock before PDF rendering. */
    @Transactional
    public Payslip snapshot(long id) {
        var records=jdbc.queryForList("select * from payroll_drafts where id=? for update",id);
        if(records.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"Payroll record not found.");
        var payroll=records.getFirst();
        if(payroll.get("approval_id")==null)throw new ApiException(HttpStatus.CONFLICT,"Approve this payroll before downloading a payslip. Reopened payroll must be approved again.");
        long approvalId=number(payroll.get("approval_id")),version=number(payroll.get("version"));
        var approved=jdbc.queryForMap("select a.*,u.name as approver_name from payroll_approvals a join auth_accounts u on u.id=a.approved_by where a.id=? and a.payroll_id=?",approvalId,id);
        var salary=json.readValue(approved.get("payload").toString(),ApprovalSnapshot.class);
        var payments=new ArrayList<Payment>();BigDecimal bank=ZERO,cash=ZERO;
        for(var payment:jdbc.queryForList("select method,amount,paid_on,reference,reversed_at from payroll_payments where payroll_id=? order by paid_on,id",id)) {
            BigDecimal amount=(BigDecimal)payment.get("amount");String method=payment.get("method").toString();boolean reversed=payment.get("reversed_at")!=null;
            payments.add(new Payment(method,amount,LocalDate.parse(payment.get("paid_on").toString()),payment.get("reference").toString(),reversed));
            if(!reversed){if("BANK".equals(method))bank=bank.add(amount);else cash=cash.add(amount);}
        }
        BigDecimal net=(BigDecimal)approved.get("net_cash"),paid=bank.add(cash),outstanding=net.subtract(paid);
        if(net.compareTo(salary.totals().netCash())!=0||outstanding.signum()<0)throw new ApiException(HttpStatus.CONFLICT,"Payroll figures need reconciliation before a payslip can be generated.");
        String status=net.signum()==0?"NO PAYMENT DUE":paid.signum()==0?"UNPAID":outstanding.signum()==0?"PAID":"PARTIALLY PAID";
        // Only employer identity is used. MAK's bank account and internal HR review notes are not employee payment details.
        var employer=new LinkedHashMap<String,String>();Object issuer=billing.get().get("issuer");
        if(!(issuer instanceof Map<?,?> company))throw new ApiException(HttpStatus.CONFLICT,"Configure the MAK billing profile before generating payslips.");
        for(String key:List.of("name","buildingNumber","street","additionalNumber","district","city","country","postalCode","phone"))employer.put(key,Objects.toString(company.get(key),""));
        if(employer.get("name").isBlank())throw new ApiException(HttpStatus.CONFLICT,"Configure the MAK business name before generating payslips.");
        String reference="MAK-PS-"+payroll.get("period").toString().replace("-","")+"-"+String.format(Locale.ROOT,"%06d",id)+"-A"+approvalId;
        return new Payslip(id,approvalId,version,reference,payroll.get("period").toString(),payroll.get("staff_name").toString(),payroll.get("staff_number").toString(),salary,instant(approved.get("approved_at")),approved.get("approver_name").toString(),Map.copyOf(employer),List.copyOf(payments),bank,cash,paid,outstanding,status,Instant.now());
    }
    private static long number(Object value){return ((Number)value).longValue();}
    private static Instant instant(Object value){return value instanceof java.sql.Timestamp time?time.toInstant():value instanceof OffsetDateTime time?time.toInstant():Instant.parse(value.toString());}
}
