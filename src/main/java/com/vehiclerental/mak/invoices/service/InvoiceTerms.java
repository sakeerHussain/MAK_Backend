package com.vehiclerental.mak.invoices.service;

import com.vehiclerental.mak.common.exception.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Calendar-day terms measured from the KSA issue date; date-only overrides stay date-only. */
public final class InvoiceTerms {
    private static final Pattern NET=Pattern.compile("^(?:net\\s*)?(\\d{1,3})(?:\\s*days?)?$");
    private InvoiceTerms() {}
    private static String text(Object value){return value==null?"":value.toString().trim();}
    public static Integer days(String value){
        String term=text(value).replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
        if(java.util.Set.of("due on receipt","on receipt","immediate").contains(term))return 0;
        var match=NET.matcher(term);if(!match.matches())return null;
        int days=Integer.parseInt(match.group(1));return days<=365?days:null;
    }
    public static String normalize(String value){Integer days=days(value);return days==null?text(value):days==0?"Due on receipt":"Net "+days;}
    public static void draft(Map<String,Object> data,String kind,String companyTerms,LocalDate today){
        boolean legacyDate=!data.containsKey("dueDateMode")&&text(data.get("paymentTerms")).isBlank()&&!text(data.get("dueDate")).isBlank();
        String term=text(data.get("paymentTerms"));
        if(term.isBlank())term=!text(companyTerms).isBlank()?companyTerms:"Corporate".equals(kind)?"Net 30":"Net 7";
        term=normalize(term);if(term.length()>100)throw bad("Payment terms accept at most 100 characters.");
        String mode=text(data.get("dueDateMode"));
        if(mode.isBlank())mode=legacyDate||days(term)==null?"Manual":"Terms";
        if(!mode.equals("Terms")&&!mode.equals("Manual"))throw bad("Choose Terms or Manual for the due-date calculation.");
        String reason=text(data.get("dueDateOverrideReason"));
        if(legacyDate)reason="Legacy due date supplied without payment terms.";
        if(reason.length()>500)throw bad("The due-date override reason accepts at most 500 characters.");
        data.put("paymentTerms",term);data.put("dueDateMode",mode);data.put("dueDateOverrideReason",mode.equals("Terms")?"":reason);
        data.remove("termsBaseDate");
        if(mode.equals("Terms"))data.put("dueDate",calculated(term,today).toString());
        else if(!text(data.get("dueDate")).isBlank())parse(data.get("dueDate"));
    }
    public static void issue(Map<String,Object> data,LocalDate today){
        // Old stored drafts with no terms retain their saved date; recognized terms are recalculated.
        if(!data.containsKey("dueDateMode"))draft(data,"Individual",null,today);
        if(text(data.get("dueDateMode")).equals("Terms")){
            data.put("dueDate",calculated(text(data.get("paymentTerms")),today).toString());data.put("termsBaseDate",today.toString());
        } else {
            String reason=text(data.get("dueDateOverrideReason"));
            if(reason.length()<3||reason.length()>500)throw bad("Enter a reason of 3 to 500 characters for the manual due date.");
            if(parse(data.get("dueDate")).isBefore(today))throw bad("Due date cannot be in the past.");
        }
    }
    private static LocalDate calculated(String term,LocalDate today){Integer days=days(term);if(days==null)throw bad("Custom payment terms require a manual due date and reason.");return today.plusDays(days);}
    private static LocalDate parse(Object value){try{return LocalDate.parse(text(value));}catch(DateTimeParseException e){throw bad("Enter a valid due date.");}}
    private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,message);}
}
