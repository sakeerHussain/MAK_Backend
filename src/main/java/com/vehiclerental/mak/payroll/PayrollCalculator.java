package com.vehiclerental.mak.payroll;

import com.vehiclerental.mak.common.exception.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.http.HttpStatus;
import static com.vehiclerental.mak.payroll.PayrollData.*;

public final class PayrollCalculator {
    private PayrollCalculator() {}
    public static final BigDecimal ZERO = new BigDecimal("0.00");
    public record Totals(BigDecimal cashEarnings, BigDecimal benefits, BigDecimal additions,
        BigDecimal deductions, BigDecimal netCash, BigDecimal fixedBankAmount, List<String> warnings) {}

    public static Totals calculate(ProfileRequest profile, List<Adjustment> adjustments) {
        BigDecimal cash = money(profile.basicSalary(), "Basic salary"), benefits = ZERO;
        BigDecimal fixed = money(profile.fixedBankAmount(), "Fixed bank allocation");
        var warnings = new ArrayList<String>();
        for (var component : profile.components()) {
            BigDecimal amount = money(component.amount(), "Component amount");
            if ("BENEFIT".equals(component.settlement())) benefits = benefits.add(amount);
            else cash = cash.add(amount);
        }
        BigDecimal additions = ZERO, deductions = ZERO;
        for (var item : adjustments) {
            BigDecimal amount = money(item.amount(), "Adjustment amount");
            if ("DEDUCTION".equals(item.kind())) deductions = deductions.add(amount);
            else if ("BENEFIT".equals(item.settlement())) benefits = benefits.add(amount);
            else additions = additions.add(amount);
        }
        BigDecimal gross = cash.add(additions), net = gross.subtract(deductions);
        if (net.signum() < 0) throw bad("Deductions cannot exceed cash earnings. Review the amounts before saving.");
        if (fixed.compareTo(net) > 0) warnings.add("Net cash entitlement is below the fixed bank allocation. Review the payment split.");
        BigDecimal bankAllowances = profile.components().stream().filter(c -> "BANK".equals(c.settlement())).map(Component::amount).reduce(ZERO, BigDecimal::add);
        BigDecimal cashAllowances = profile.components().stream().filter(c -> "CASH".equals(c.settlement())).map(Component::amount).reduce(ZERO, BigDecimal::add);
        if (bankAllowances.compareTo(fixed) > 0 || cashAllowances.compareTo(cash.subtract(fixed)) > 0)
            warnings.add("Allowance settlement preferences and the fixed bank allocation need reconciliation before payment.");
        if (!adjustments.isEmpty()) warnings.add("Review the payment split after monthly adjustments.");
        return new Totals(gross, benefits, additions, deductions, net, fixed, List.copyOf(warnings));
    }
    public static BigDecimal money(BigDecimal value, String label) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("9999999999.99")) > 0)
            throw bad(label + " must be a non-negative SAR amount, with at most two decimal places.");
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw bad(label + " must have at most two decimal places."); }
    }
    public static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
}
