package com.vehiclerental.mak.payroll;

import com.vehiclerental.mak.common.exception.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import static com.vehiclerental.mak.payroll.PayrollData.*;
import static com.vehiclerental.mak.payroll.PayrollCalculator.*;

@Service
public class PayrollService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PayrollSettlementService settlements;
    public PayrollService(JdbcTemplate jdbc, ObjectMapper json, PayrollSettlementService settlements) { this.jdbc = jdbc; this.json = json; this.settlements = settlements; }

    @Transactional(readOnly = true)
    public Map<String,Object> get(long id) { return draftView(row("select * from payroll_drafts where id=?", id)); }

    @Transactional(readOnly = true)
    public Map<String,Object> list(String period) {
        month(period);
        var result = new LinkedHashMap<String,Object>();
        result.put("period", period);
        result.put("staff", roster());
        result.put("profiles", jdbc.queryForList("select * from payroll_profiles order by payee_id, effective_month desc").stream().map(this::profileView).toList());
        result.put("drafts", jdbc.queryForList("select * from payroll_drafts where period=? order by staff_name, id", period).stream().map(this::draftView).toList());
        return result;
    }

    @Transactional
    public Map<String,Object> saveProfile(Long id, ProfileRequest request, long actor) {
        var data = validateProfile(request);
        var staff = source(data.staffType(), data.staffId(), true);
        long payee = payee(staff);
        if (id == null) {
            if (jdbc.queryForObject("select count(*) from payroll_profiles where payee_id=? and effective_month=?", Long.class, payee, data.effectiveMonth()) > 0)
                throw conflict("A salary profile already exists for this staff member and effective month. Edit that version instead.");
            id = insert("insert into payroll_profiles(payee_id,effective_month,payload,version,updated_at,updated_by) values(?,?,?,0,?,?)", payee, data.effectiveMonth(), json.writeValueAsString(data), now(), actor);
        } else {
            var existing = row("select * from payroll_profiles where id=? for update", id);
            checkVersion(data.version(), existing);
            if (number(existing, "payee_id") != payee || !existing.get("effective_month").equals(data.effectiveMonth()))
                throw bad("The staff member and effective month of a saved profile cannot change. Create a new effective version.");
            jdbc.update("update payroll_profiles set payload=?,version=version+1,updated_at=?,updated_by=? where id=?", json.writeValueAsString(data), now(), actor, id);
        }
        audit(actor, "PAYROLL_PROFILE_SAVED", "Salary profile " + id + "; existing monthly snapshots unchanged");
        return profileView(row("select * from payroll_profiles where id=?", id));
    }

    @Transactional
    public List<Map<String,Object>> prepare(DraftRequest request, long actor) {
        var period = month(request.period());
        if (request.payeeIds() == null || request.payeeIds().isEmpty() || request.payeeIds().size() > 200 || request.payeeIds().stream().anyMatch(Objects::isNull) || new HashSet<>(request.payeeIds()).size() != request.payeeIds().size())
            throw bad("Select between 1 and 200 different staff members with saved salary profiles.");
        // Consistent lock order prevents two overlapping batch runs from deadlocking.
        var ids = request.payeeIds().stream().sorted().toList();
        var created = new ArrayList<Map<String,Object>>();
        for (Long payeeId : ids) {
            if (payeeId == null) throw bad("Choose a valid staff member.");
            var payee = row("select * from payroll_payees where id=?", payeeId);
            var staff = source(payee.get("staff_type").toString(), number(payee, "staff_id"), true);
            payee = row("select * from payroll_payees where id=? for update", payeeId);
            if (!"Active".equals(staff.get("status"))) throw bad(staff.get("name") + " is inactive. Review the staff profile before preparing payroll.");
            if (staff.get("joined_on") instanceof java.sql.Date joined && joined.toLocalDate().isAfter(period.atEndOfMonth()))
                throw bad("The selected period is before " + staff.get("name") + " joined.");
            String key = staff.get("staff_number").toString().trim().toUpperCase(Locale.ROOT);
            if (!key.equals(payee.get("staff_key"))) {
                if (jdbc.queryForObject("select count(*) from payroll_payees where staff_key=? and id<>?", Long.class, key, payeeId) > 0)
                    throw conflict("This staff number is already linked to another payroll payee. Resolve the duplicate staff records first.");
                jdbc.update("update payroll_payees set staff_key=? where id=?", key, payeeId);
            }
            if (jdbc.queryForObject("select count(*) from payroll_drafts where period=? and payee_id=?", Long.class, request.period(), payeeId) > 0)
                throw conflict("Payroll already exists for " + staff.get("name") + " in " + request.period() + ". No duplicate was created.");
            var profiles = jdbc.queryForList("select * from payroll_profiles where payee_id=? and effective_month<=? order by effective_month desc", payeeId, request.period());
            if (profiles.isEmpty()) throw bad("No salary profile is effective for " + staff.get("name") + " in this month.");
            var profile = profiles.getFirst();
            long id = insert("insert into payroll_drafts(payee_id,period,profile_id,profile_version,staff_name,staff_number,snapshot,adjustments,notes,version,created_at,updated_at,updated_by) values(?,?,?,?,?,?,?,?,?,0,?,?,?)",
                payeeId, request.period(), number(profile,"id"), number(profile,"version"), staff.get("name"), staff.get("staff_number"), profile.get("payload"), "[]", "", now(), now(), actor);
            created.add(draftView(row("select * from payroll_drafts where id=?", id)));
        }
        audit(actor, "PAYROLL_DRAFTS_PREPARED", created.size() + " monthly drafts; period " + request.period());
        return created;
    }

    @Transactional
    public Map<String,Object> update(long id, DraftUpdate request, long actor) {
        var draft = row("select * from payroll_drafts where id=? for update", id);
        checkVersion(request.version(), draft);
        settlements.requireDraft(draft);
        var adjustments = validateAdjustments(request.adjustments());
        calculate(readProfile(draft.get("snapshot")), adjustments);
        jdbc.update("update payroll_drafts set adjustments=?,notes=?,version=version+1,updated_at=?,updated_by=? where id=?", json.writeValueAsString(adjustments), text(request.notes(), 2000), now(), actor, id);
        audit(actor, "PAYROLL_DRAFT_UPDATED", "Monthly draft " + id + "; " + adjustments.size() + " manual adjustments");
        return draftView(row("select * from payroll_drafts where id=?", id));
    }

    @Transactional
    public void discard(long id, long version, long actor) {
        var draft = row("select * from payroll_drafts where id=? for update", id);
        checkVersion(version, draft);
        settlements.requireDraft(draft);
        if (!settlements.canDiscard(id)) throw conflict("This payroll has approval history and must be retained. Correct the draft and approve it again instead of discarding it.");
        jdbc.update("delete from payroll_drafts where id=?", id);
        audit(actor, "PAYROLL_DRAFT_DISCARDED", "Monthly draft " + id + "; period " + draft.get("period") + "; no payment reversed");
    }

    /** Staff deletion cannot erase the identity associated with a saved salary. */
    public void guardStaffArchive(String type, long id) {
        source(type, id, true);
        if (jdbc.queryForObject("select count(*) from payroll_payees where staff_type=? and staff_id=?", Long.class, type, id) > 0)
            throw conflict("Staff has a saved payroll salary profile. Deactivate instead to preserve payroll history.");
    }

    private List<Map<String,Object>> roster() {
        var staff = jdbc.queryForList("select 'EMPLOYEE' as staff_type,id as staff_id,employee_id as staff_number,name,status,department,joined_on,monthly_salary as reference_rate,employment_type from employees where archived=false union all select 'DRIVER',id,employee_id,name,status,category,joined_on,monthly_rate,employment_type from drivers where archived=false order by name");
        var payees = jdbc.queryForList("select * from payroll_payees");
        for (var person : staff) {
            var payee = payees.stream().filter(p -> p.get("staff_type").equals(person.get("staff_type")) && number(p,"staff_id") == number(person,"staff_id")).findFirst();
            person.put("payeeId", payee.map(p -> p.get("id")).orElse(null));
            String key = person.get("staff_number").toString().trim();
            person.put("duplicateStaffNumber", staff.stream().filter(s -> s.get("staff_number").toString().trim().equalsIgnoreCase(key)).count() > 1);
        }
        return staff.stream().map(person -> {
            var data = new LinkedHashMap<String,Object>();
            for (var field : Map.of("staffType","staff_type","staffId","staff_id","staffNumber","staff_number","name","name","status","status","department","department","joinedOn","joined_on","referenceRate","reference_rate","employmentType","employment_type","payeeId","payeeId").entrySet()) data.put(field.getKey(), person.get(field.getValue()));
            data.put("duplicateStaffNumber", person.get("duplicateStaffNumber"));
            return (Map<String,Object>) data;
        }).toList();
    }

    private Map<String,Object> source(String type, Long id, boolean lock) {
        String table = switch (type == null ? "" : type) { case "EMPLOYEE" -> "employees"; case "DRIVER" -> "drivers"; default -> throw bad("Choose Employee or Driver."); };
        if (id == null || id < 1) throw bad("Choose a valid staff member.");
        var rows = jdbc.queryForList("select id,name,employee_id as staff_number,status,joined_on from " + table + " where id=? and archived=false" + (lock ? " for update" : ""), id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "The linked staff record is unavailable.");
        var row = rows.getFirst(); row.put("staff_type", type); return row;
    }

    private long payee(Map<String,Object> staff) {
        String type = staff.get("staff_type").toString(), key = staff.get("staff_number").toString().trim().toUpperCase(Locale.ROOT);
        long staffId = number(staff, "id");
        var existing = jdbc.queryForList("select * from payroll_payees where staff_type=? and staff_id=? for update", type, staffId);
        var duplicate = jdbc.queryForList("select * from payroll_payees where staff_key=?", key);
        if (!duplicate.isEmpty() && (!duplicate.getFirst().get("staff_type").equals(type) || number(duplicate.getFirst(),"staff_id") != staffId))
            throw conflict("This staff number already has a payroll profile under another Employee or Driver record. Use that existing payee.");
        if (!existing.isEmpty()) {
            long id = number(existing.getFirst(), "id");
            jdbc.update("update payroll_payees set staff_key=? where id=?", key, id); return id;
        }
        return insert("insert into payroll_payees(staff_type,staff_id,staff_key) values(?,?,?)", type, staffId, key);
    }

    private ProfileRequest validateProfile(ProfileRequest data) {
        if (data == null) throw bad("Enter the salary profile.");
        month(data.effectiveMonth());
        BigDecimal basic = money(data.basicSalary(), "Basic salary"), fixed = money(data.fixedBankAmount(), "Fixed bank allocation");
        if (data.components() == null || data.components().size() > 40) throw bad("Use at most 40 recurring components.");
        var components = new ArrayList<Component>(); var names = new HashSet<String>();
        for (var component : data.components()) {
            if (component == null) throw bad("Enter a valid salary component.");
            String name = required(component.name(), 100, "Component name");
            if (!names.add(name.toLowerCase(Locale.ROOT))) throw bad("Use a different name for each recurring component.");
            components.add(new Component(name, money(component.amount(), "Component amount"), settlement(component.settlement())));
        }
        String iban = text(data.iban(), 40).replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!iban.isEmpty() && !validIban(iban)) throw bad("Enter a valid receiving IBAN or leave it blank until confirmed.");
        var cleaned = new ProfileRequest(data.version(), data.staffType(), data.staffId(), data.effectiveMonth(), basic, fixed,
            text(data.accountName(), 150), text(data.bankName(), 150), iban, text(data.accountNumber(), 80), List.copyOf(components), text(data.notes(), 2000));
        if (fixed.compareTo(calculate(cleaned, List.of()).netCash()) > 0) throw bad("The fixed bank allocation cannot exceed recurring cash earnings. Company-provided benefits are excluded.");
        return cleaned;
    }

    private List<Adjustment> validateAdjustments(List<Adjustment> input) {
        if (input == null || input.size() > 60) throw bad("Use at most 60 monthly adjustments.");
        var result = new ArrayList<Adjustment>();
        for (var item : input) {
            if (item == null || !Set.of("ADDITION", "DEDUCTION").contains(Objects.toString(item.kind(), ""))) throw bad("Choose Addition or Deduction.");
            if ("DEDUCTION".equals(item.kind()) && !"CASH".equals(item.settlement())) throw bad("Deductions reduce cash entitlement; they are not a bank payment or company-provided benefit.");
            var amount = money(item.amount(), "Adjustment amount");
            if (amount.signum() == 0) throw bad("Monthly adjustments must have an amount greater than zero.");
            result.add(new Adjustment(required(item.name(), 100, "Adjustment name"), item.kind(), amount, settlement(item.settlement()), required(item.reason(), 500, "Adjustment reason")));
        }
        return List.copyOf(result);
    }

    private Map<String,Object> profileView(Map<String,Object> row) {
        var result = new LinkedHashMap<String,Object>(); var data = readProfile(row.get("payload"));
        result.put("id", number(row,"id")); result.put("payeeId", number(row,"payee_id")); result.put("version", number(row,"version"));
        result.put("details", data); result.put("totals", calculate(data, List.of())); result.put("updatedAt", row.get("updated_at").toString());
        var warnings = new ArrayList<>(calculate(data, List.of()).warnings());
        if (data.fixedBankAmount().signum() > 0 && (data.accountName().isBlank() || data.bankName().isBlank() || data.iban().isBlank() && data.accountNumber().isBlank()))
            warnings.add("Receiving bank details are incomplete. Configure them before payment processing.");
        result.put("warnings", warnings); return result;
    }

    private Map<String,Object> draftView(Map<String,Object> row) {
        var profile = readProfile(row.get("snapshot"));
        var adjustments = json.readValue(row.get("adjustments").toString(), new TypeReference<List<Adjustment>>() {});
        var totals = calculate(profile, adjustments);
        var result = new LinkedHashMap<String,Object>();
        for (String key : List.of("id","version")) result.put(key, number(row,key));
        result.put("payeeId", number(row,"payee_id")); result.put("period", row.get("period")); result.put("name", row.get("staff_name")); result.put("staffNumber", row.get("staff_number"));
        result.put("profileId", number(row,"profile_id")); result.put("profileVersion", number(row,"profile_version"));
        result.put("snapshot", profile); result.put("adjustments", adjustments); result.put("notes", row.get("notes")); result.put("totals", totals);
        result.putAll(settlements.describe(row, totals)); result.put("updatedAt", row.get("updated_at").toString());
        var warnings = new ArrayList<>(totals.warnings());
        if (profile.fixedBankAmount().signum() > 0 && (profile.accountName().isBlank() || profile.bankName().isBlank() || profile.iban().isBlank() && profile.accountNumber().isBlank())) warnings.add("Receiving bank details are incomplete.");
        var current = jdbc.queryForList("select version from payroll_profiles where id=?", row.get("profile_id"));
        long newer = jdbc.queryForObject("select count(*) from payroll_profiles where payee_id=? and effective_month<=? and effective_month>?", Long.class, row.get("payee_id"), row.get("period"), profile.effectiveMonth());
        if (newer > 0 || !current.isEmpty() && number(current.getFirst(),"version") != number(row,"profile_version")) warnings.add(row.get("approval_id") == null ? "The salary profile has changed. This draft retains its original salary snapshot; review whether the new structure should apply." : "The salary profile has changed. This approved payroll retains its locked salary snapshot.");
        var payee = row("select * from payroll_payees where id=?", number(row,"payee_id"));
        String table = "EMPLOYEE".equals(payee.get("staff_type")) ? "employees" : "drivers";
        var staff = jdbc.queryForList("select joined_on,status,archived from " + table + " where id=?", payee.get("staff_id"));
        if (staff.isEmpty() || Boolean.TRUE.equals(staff.getFirst().get("archived")) || !"Active".equals(staff.getFirst().get("status"))) warnings.add("The linked staff record is no longer active. Review eligibility before approval.");
        if (!staff.isEmpty() && staff.getFirst().get("joined_on") instanceof java.sql.Date joined && YearMonth.from(joined.toLocalDate()).toString().equals(row.get("period"))) warnings.add("Staff joined during this payroll month. No automatic proration is applied; enter a reasoned manual adjustment if required.");
        result.put("warnings", warnings); return result;
    }

    private ProfileRequest readProfile(Object value) { return json.readValue(value.toString(), ProfileRequest.class); }
    private Map<String,Object> row(String sql, long id) { var rows = jdbc.queryForList(sql, id); if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Payroll record not found. Refresh the register."); return rows.getFirst(); }
    private long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        try { jdbc.update(connection -> { var statement = connection.prepareStatement(sql, new String[]{"id"}); for (int i=0;i<params.length;i++) statement.setObject(i+1, params[i]); return statement; }, keys); }
        catch (DataIntegrityViolationException ex) { throw conflict("Another request created this payroll record. Refresh the register before continuing."); }
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    private void checkVersion(Long version, Map<String,Object> row) { if (version == null || version != number(row,"version")) throw conflict("This payroll record changed. Close and reload it before saving."); }
    private static long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    private static YearMonth month(String value) { try { if (value == null || !value.matches("20\\d{2}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException(); return YearMonth.parse(value); } catch (RuntimeException ex) { throw bad("Choose a payroll month between 2000 and 2099."); } }
    private static String settlement(String value) { if (!Set.of("BANK","CASH","BENEFIT").contains(Objects.toString(value,""))) throw bad("Choose Bank, Cash or Company-provided benefit."); return value; }
    private static String text(String value, int max) { String result = value == null ? "" : value.trim(); if (result.length() > max) throw bad("A payroll field exceeds the permitted length."); return result; }
    private static String required(String value, int max, String label) { String result = text(value,max); if (result.isBlank()) throw bad(label + " is required."); return result; }
    private static Timestamp now() { return Timestamp.from(Instant.now()); }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
    private void audit(long actor, String event, String summary) { jdbc.update("insert into auth_audit(actor_id,event,summary,created_at) values(?,?,?,?)", actor, event, summary, now()); }
    static boolean validIban(String iban) {
        if (!iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")) return false;
        if (iban.startsWith("SA") && iban.length()!=24) return false;
        String rotated=iban.substring(4)+iban.substring(0,4); int remainder=0;
        for(char ch:rotated.toCharArray()) for(char digit:(ch>='A'&&ch<='Z'?Integer.toString(ch-'A'+10):Character.toString(ch)).toCharArray()) remainder=(remainder*10+digit-'0')%97;
        return remainder==1;
    }
}
