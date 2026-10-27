package com.vehiclerental.mak.payroll;

import com.vehiclerental.mak.common.dto.response.FileDownload;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.common.storage.StoredUploads;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import static com.vehiclerental.mak.payroll.PayrollData.*;
import static com.vehiclerental.mak.payroll.PayrollCalculator.*;

/** Approval locks entitlement. This ledger only records payments made elsewhere. */
@Service
public class PayrollSettlementService {
    private static final ZoneId KSA = ZoneId.of("Asia/Riyadh");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final StoredUploads uploads;
    public PayrollSettlementService(JdbcTemplate jdbc, ObjectMapper json, StoredUploads uploads) { this.jdbc=jdbc; this.json=json; this.uploads=uploads; }

    public void requireDraft(Map<String,Object> payroll) {
        if (payroll.get("approval_id") != null) throw conflict("Approved payroll is locked. Reverse any incorrect payment records, then reopen with a correction reason before editing salary figures.");
    }
    public boolean canDiscard(long id) { return jdbc.queryForObject("select count(*) from payroll_approvals where payroll_id=?",Long.class,id)==0; }

    @Transactional
    public void approve(long id, ApprovalRequest request, long actor) {
        var payroll=locked(id); version(request.version(),payroll); requireDraft(payroll);
        if (!request.reviewed()) throw bad("Confirm that you reviewed the salary, allowances, deductions and payment split.");
        String note=required(request.reviewNote(),1000,"Approval review note");
        var profile=profile(payroll); var adjustments=adjustments(payroll);
        var totals=calculate(profile,adjustments);
        // Follow the same source -> payee -> payroll lock order as preparation.
        // Eligibility is checked again at approval; no attendance policy is guessed.
        var payee=one("select * from payroll_payees where id=?",number(payroll,"payee_id"));
        String table="EMPLOYEE".equals(payee.get("staff_type"))?"employees":"drivers";
        var staff=jdbc.queryForList("select status,archived,joined_on from "+table+" where id=?",payee.get("staff_id"));
        if(staff.isEmpty()||Boolean.TRUE.equals(staff.getFirst().get("archived"))||!"Active".equals(staff.getFirst().get("status"))) throw bad("The linked staff member is not active. Review eligibility before approval.");
        if(staff.getFirst().get("joined_on") instanceof java.sql.Date joined && joined.toLocalDate().isAfter(YearMonth.parse(payroll.get("period").toString()).atEndOfMonth())) throw bad("The payroll month is before the staff member joined.");
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("salary",profile); snapshot.put("adjustments",adjustments); snapshot.put("notes",payroll.get("notes")); snapshot.put("totals",totals);
        long approval=insert("insert into payroll_approvals(payroll_id,net_cash,payload,review_note,approved_at,approved_by) values(?,?,?,?,?,?)",id,totals.netCash(),json.writeValueAsString(snapshot),note,now(),actor);
        jdbc.update("update payroll_drafts set approval_id=?,version=version+1,updated_at=?,updated_by=? where id=?",approval,now(),actor,id);
        audit(actor,"PAYROLL_APPROVED","Payroll "+id+"; approval "+approval+"; salary snapshot locked");
    }

    @Transactional
    public void reopen(long id, CorrectionRequest request, long actor) {
        var payroll=locked(id); version(request.version(),payroll); long approval=approved(payroll);
        String reason=required(request.reason(),1000,"Correction reason");
        if(jdbc.queryForObject("select count(*) from payroll_payments where payroll_id=? and reversed_at is null",Long.class,id)>0) throw conflict("This payroll has recorded payments. Correct or reverse those records first; salary figures cannot change while payments remain recorded.");
        jdbc.update("update payroll_approvals set reopened_at=?,reopened_by=?,reopen_reason=? where id=?",now(),actor,reason,approval);
        jdbc.update("update payroll_drafts set approval_id=null,version=version+1,updated_at=?,updated_by=? where id=?",now(),actor,id);
        audit(actor,"PAYROLL_REOPENED","Payroll "+id+"; approval "+approval+" retained; reason: "+shortReason(reason));
    }

    @Transactional
    public void record(long id, PaymentRequest request, MultipartFile receipt, long actor) {
        var payroll=locked(id);
        var data=cleanPayment(request);
        String requestData=json.writeValueAsString(data), receiptHash=receiptHash(receipt);
        var previous=jdbc.queryForList("select * from payroll_payments where payroll_id=? and request_key=?",id,data.requestKey());
        if(!previous.isEmpty()) {
            var saved=previous.getFirst();
            if(!requestData.equals(saved.get("request_data"))||!Objects.equals(receiptHash,saved.get("request_receipt_hash"))) throw conflict("This payment request was already used with different details. Reload payroll before starting a new payment record.");
            return; // A network retry must not record the same payment twice.
        }
        version(request.version(),payroll); long approval=approved(payroll);
        BigDecimal net=(BigDecimal)one("select * from payroll_approvals where id=?",approval).get("net_cash");
        BigDecimal outstanding=net.subtract(paid(id,null));
        if(data.amount().compareTo(outstanding)>0) throw bad("Payment exceeds the outstanding salary balance of SAR "+outstanding.toPlainString()+".");
        String activeReference=data.method()+":"+data.reference().toUpperCase(Locale.ROOT);
        if(jdbc.queryForObject("select count(*) from payroll_payments where payee_id=? and active_reference=?",Long.class,payroll.get("payee_id"),activeReference)>0) throw conflict("This staff member already has a recorded payment with that method and reference. Check the existing entry before recording it again.");
        long payment=insert("insert into payroll_payments(payroll_id,payee_id,approval_id,request_key,request_data,request_receipt_hash,method,amount,paid_on,reference,active_reference,notes,receiving_account,recorded_at,recorded_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,payroll.get("payee_id"),approval,data.requestKey(),requestData,receiptHash,data.method(),data.amount(),java.sql.Date.valueOf(data.paidOn()),data.reference(),activeReference,data.notes(),data.account()==null?null:json.writeValueAsString(data.account()),now(),actor);
        if(receipt!=null&&!receipt.isEmpty()) saveReceipt(id,payment,receipt);
        changed(id,actor);
        audit(actor,"PAYROLL_PAYMENT_RECORDED","Payroll "+id+"; payment "+payment+"; "+data.method()+"; external payment recorded");
    }

    @Transactional
    public void reverse(long id, long paymentId, CorrectionRequest request, long actor) {
        var payroll=locked(id); version(request.version(),payroll); approved(payroll);
        var payment=payment(id,paymentId);
        if(payment.get("reversed_at")!=null) throw conflict("This payment record is already reversed. Reload payroll.");
        String reason=required(request.reason(),1000,"Payment correction reason");
        jdbc.update("update payroll_payments set reversed_at=?,reversed_by=?,reversal_reason=?,active_reference=null where id=?",now(),actor,reason,paymentId);
        changed(id,actor);
        audit(actor,"PAYROLL_PAYMENT_REVERSED","Payroll "+id+"; payment "+paymentId+" retained; reason: "+shortReason(reason));
    }

    @Transactional
    public void attachReceipt(long id,long paymentId,long expectedVersion,MultipartFile receipt,long actor) {
        var payroll=locked(id);version(expectedVersion,payroll);approved(payroll);var payment=payment(id,paymentId);
        if(payment.get("reversed_at")!=null) throw conflict("A reversed payment record cannot receive new proof.");
        if(payment.get("receipt_key")!=null) throw conflict("This payment already has a receipt. Its original proof is retained.");
        saveReceipt(id,paymentId,receipt);changed(id,actor);
        audit(actor,"PAYROLL_RECEIPT_ADDED","Payroll "+id+"; payment "+paymentId+"; original proof attached");
    }

    @Transactional(readOnly=true)
    public FileDownload receipt(long id,long paymentId) {
        var payment=payment(id,paymentId);
        if(payment.get("receipt_key")==null) throw new ApiException(HttpStatus.NOT_FOUND,"No receipt is attached to this payment.");
        return new FileDownload(uploads.resource("payroll",id,payment.get("receipt_key").toString()),payment.get("receipt_name").toString(),payment.get("receipt_type").toString());
    }

    public Map<String,Object> describe(Map<String,Object> payroll,Totals draftTotals) {
        long id=number(payroll,"id"); boolean approved=payroll.get("approval_id")!=null;
        BigDecimal net=approved?(BigDecimal)one("select * from payroll_approvals where id=?",number(payroll,"approval_id")).get("net_cash"):draftTotals.netCash();
        BigDecimal bank=paid(id,"BANK"),cash=paid(id,"CASH"),total=bank.add(cash);
        String paymentStatus=!approved?"Not approved":net.signum()==0?"No payment due":total.signum()==0?"Unpaid":total.compareTo(net)==0?"Paid":"Partially paid";
        var result=new LinkedHashMap<String,Object>();
        result.put("approvalId",payroll.get("approval_id"));result.put("status",!approved?"Draft":"Paid".equals(paymentStatus)||"Partially paid".equals(paymentStatus)?paymentStatus:"Approved");
        result.put("paymentStatus",paymentStatus);result.put("approvedNetCash",approved?net:null);result.put("bankPaid",bank);result.put("cashPaid",cash);result.put("paid",total);result.put("outstanding",approved?net.subtract(total):null);
        result.put("canDiscard",!approved&&canDiscard(id));
        var history=new ArrayList<Map<String,Object>>();
        for(var record:jdbc.queryForList("select a.*,u.name as actor_name,r.name as reopen_actor_name from payroll_approvals a join auth_accounts u on u.id=a.approved_by left join auth_accounts r on r.id=a.reopened_by where a.payroll_id=? order by a.id",id)) {
            var item=new LinkedHashMap<String,Object>();item.put("id",number(record,"id"));item.put("netCash",record.get("net_cash"));item.put("reviewNote",record.get("review_note"));item.put("approvedAt",stamp(record.get("approved_at")));item.put("approvedBy",record.get("actor_name"));item.put("reopenedAt",stamp(record.get("reopened_at")));item.put("reopenedBy",record.get("reopen_actor_name"));item.put("reopenReason",record.get("reopen_reason"));
            item.put("snapshot",json.readValue(record.get("payload").toString(),new TypeReference<Map<String,Object>>(){}));history.add(item);
        }
        result.put("approvals",history);
        var payments=new ArrayList<Map<String,Object>>();
        for(var record:jdbc.queryForList("select p.*,u.name as actor_name,r.name as reverse_actor_name from payroll_payments p join auth_accounts u on u.id=p.recorded_by left join auth_accounts r on r.id=p.reversed_by where p.payroll_id=? order by p.id",id)) {
            var item=new LinkedHashMap<String,Object>();long paymentId=number(record,"id");
            item.put("id",paymentId);item.put("approvalId",number(record,"approval_id"));item.put("method",record.get("method"));item.put("amount",record.get("amount"));item.put("paidOn",record.get("paid_on").toString());item.put("reference",record.get("reference"));item.put("notes",record.get("notes"));item.put("recordedBy",record.get("actor_name"));item.put("recordedAt",stamp(record.get("recorded_at")));item.put("reversedAt",stamp(record.get("reversed_at")));item.put("reversedBy",record.get("reverse_actor_name"));item.put("reversalReason",record.get("reversal_reason"));
            item.put("account",record.get("receiving_account")==null?null:json.readValue(record.get("receiving_account").toString(),ReceivingAccount.class));
            item.put("receipt",record.get("receipt_key")==null?null:Map.of("name",record.get("receipt_name"),"type",record.get("receipt_type"),"size",record.get("receipt_size"),"url","/api/payroll/drafts/"+id+"/payments/"+paymentId+"/receipt"));payments.add(item);
        }
        result.put("payments",payments); return result;
    }

    private void saveReceipt(long id,long paymentId,MultipartFile receipt) {
        var stored=uploads.store("payroll",id,receipt);
        jdbc.update("update payroll_payments set receipt_key=?,receipt_name=?,receipt_type=?,receipt_size=? where id=?",stored.key(),stored.name(),stored.type(),stored.size(),paymentId);
    }
    private CleanPayment cleanPayment(PaymentRequest request) {
        String key=required(request.requestKey(),36,"Payment request key");try{key=UUID.fromString(key).toString();}catch(RuntimeException ex){throw bad("Use a valid payment request key.");}
        if(!request.confirmedPaid())throw bad("Confirm this payment has already been made outside MAK before recording it.");
        if(!Set.of("BANK","CASH").contains(Objects.toString(request.method(),"")))throw bad("Choose Bank or Cash. Noncash benefits are not salary payments.");
        BigDecimal amount=money(request.amount(),"Payment amount");if(amount.signum()==0)throw bad("Enter a payment amount greater than zero.");
        if(request.paidOn()==null||request.paidOn().isBefore(LocalDate.of(2000,1,1))||request.paidOn().isAfter(LocalDate.now(KSA)))throw bad("Enter the actual payment date, from 2000 through today in Saudi Arabia time.");
        ReceivingAccount account=null;
        if("BANK".equals(request.method())) {
            var input=request.account();if(input==null)throw bad("Enter the employee receiving account used for this bank payment.");
            String iban=text(input.iban(),40).replaceAll("\\s","").toUpperCase(Locale.ROOT),number=text(input.accountNumber(),80);
            if(!iban.isEmpty()&&!PayrollService.validIban(iban))throw bad("Enter a valid receiving IBAN.");
            if(iban.isEmpty()&&number.isEmpty())throw bad("Enter the receiving IBAN or account number used for this payment.");
            account=new ReceivingAccount(required(input.accountName(),150,"Employee account holder"),required(input.bankName(),150,"Receiving bank name"),iban,number);
        }
        return new CleanPayment(key,request.method(),amount,request.paidOn(),required(request.reference(),150,"Bank transfer reference or cash voucher number"),text(request.notes(),1000),account);
    }
    private BigDecimal paid(long id,String method) {
        if(method==null)return jdbc.queryForObject("select coalesce(sum(amount),0) from payroll_payments where payroll_id=? and reversed_at is null",BigDecimal.class,id);
        return jdbc.queryForObject("select coalesce(sum(amount),0) from payroll_payments where payroll_id=? and reversed_at is null and method=?",BigDecimal.class,id,method);
    }
    private Map<String,Object> locked(long id){return one("select * from payroll_drafts where id=? for update",id);}
    private Map<String,Object> one(String sql,long id){var rows=jdbc.queryForList(sql,id);if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"Payroll record not found. Refresh payroll.");return rows.getFirst();}
    private Map<String,Object> payment(long id,long paymentId){var rows=jdbc.queryForList("select * from payroll_payments where id=? and payroll_id=?",paymentId,id);if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"Payment record not found for this payroll.");return rows.getFirst();}
    private ProfileRequest profile(Map<String,Object> row){return json.readValue(row.get("snapshot").toString(),ProfileRequest.class);}
    private List<Adjustment> adjustments(Map<String,Object> row){return json.readValue(row.get("adjustments").toString(),new TypeReference<List<Adjustment>>(){});}
    private long approved(Map<String,Object> payroll){if(payroll.get("approval_id")==null)throw conflict("Approve payroll before recording or correcting payments.");return number(payroll,"approval_id");}
    private void changed(long id,long actor){jdbc.update("update payroll_drafts set version=version+1,updated_at=?,updated_by=? where id=?",now(),actor,id);}
    private void version(Long expected,Map<String,Object> row){if(expected==null||expected!=number(row,"version"))throw conflict("Payroll changed. Close and reload the record before continuing.");}
    private long insert(String sql,Object... params){var keys=new GeneratedKeyHolder();try{jdbc.update(connection->{var s=connection.prepareStatement(sql,new String[]{"id"});for(int i=0;i<params.length;i++)s.setObject(i+1,params[i]);return s;},keys);}catch(DataIntegrityViolationException ex){throw conflict("This payment or approval conflicts with a saved record. Reload payroll and check for duplicates.");}return Objects.requireNonNull(keys.getKey()).longValue();}
    private void audit(long actor,String event,String summary){jdbc.update("insert into auth_audit(actor_id,event,summary,created_at) values(?,?,?,?)",actor,event,summary,now());}
    private static long number(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    private static String text(String value,int max){String result=value==null?"":value.trim();if(result.length()>max)throw bad("A payroll field exceeds the permitted length.");return result;}
    private static String required(String value,int max,String label){String result=text(value,max);if(result.isBlank())throw bad(label+" is required.");return result;}
    private static String receiptHash(MultipartFile file){if(file==null||file.isEmpty())return null;if(file.getSize()>5L*1024*1024)throw bad("Choose a receipt up to 5 MB.");try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getBytes()));}catch(Exception ex){throw bad("The payment receipt could not be read.");}}
    private static String stamp(Object value){return value==null?null:value instanceof java.time.OffsetDateTime date?date.toInstant().toString():value instanceof Timestamp date?date.toInstant().toString():value.toString();}
    private static Timestamp now(){return Timestamp.from(Instant.now());}
    private static String shortReason(String reason){return reason.length()>140?reason.substring(0,140):reason;}
    private static ApiException conflict(String message){return new ApiException(HttpStatus.CONFLICT,message);}
    private record CleanPayment(String requestKey,String method,BigDecimal amount,LocalDate paidOn,String reference,String notes,ReceivingAccount account){}
}
