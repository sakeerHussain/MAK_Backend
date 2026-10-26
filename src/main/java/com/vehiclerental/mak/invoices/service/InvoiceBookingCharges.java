package com.vehiclerental.mak.invoices.service;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.rentals.entity.RentalRecord;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;

/** Keep legacy manual booking tax/extra assumptions out of issued invoices. */
final class InvoiceBookingCharges {
    private InvoiceBookingCharges() {}
    static boolean required(RentalRecord rental,Map<String,Object> payload){
        if(!"Individual".equals(rental.getKind()))return false;
        return !(payload.get("quoteSnapshot") instanceof Map<?,?> quote&&quote.get("lines") instanceof List<?> lines&&!lines.isEmpty());
    }
    static void prepare(Map<String,Object> draft,RentalRecord rental,Map<String,Object> payload){
        if(!required(rental,payload)){draft.remove("bookingChargeReview");return;}
        Map<?,?> supplied=draft.get("bookingChargeReview") instanceof Map<?,?> m?m:Map.of();
        var review=new LinkedHashMap<String,Object>();review.put("required",true);review.put("bookingTotal",rental.getTotalAmount());
        review.put("confirmed",Boolean.TRUE.equals(supplied.get("confirmed")));
        var extras=new ArrayList<String>();
        for(var field:List.of("extraKm","overtimeHours"))try{if(new BigDecimal(String.valueOf(payload.getOrDefault(field,0))).signum()>0)extras.add(payload.get(field)+("extraKm".equals(field)?" extra km":" overtime hours"));}catch(NumberFormatException ignored){}
        if(Boolean.TRUE.equals(payload.get("extraDriver")))extras.add("Additional driver");
        review.put("recordedExtras",String.join(" · ",extras));draft.put("bookingChargeReview",review);
    }
    static void validateIssue(Map<String,Object> draft,RentalRecord rental,Map<String,Object> payload,BigDecimal total){
        if(!required(rental,payload))return;
        prepare(draft,rental,payload);
        Map<?,?> review=(Map<?,?>)draft.get("bookingChargeReview");
        if(!Boolean.TRUE.equals(review.get("confirmed")))throw bad("Review and confirm the booking rental, extras and tax breakdown in Edit Draft before issuing.");
        if(total.compareTo(rental.getTotalAmount())!=0)throw bad("Rental, extras and tax must reconcile with the saved booking total before issuing.");
        for(Object item:(List<?>)draft.get("lines")){
            Map<?,?> line=(Map<?,?>)item;
            if(line.get("taxPercent")==null||line.get("taxPercent").toString().isBlank())throw bad("Enter an explicit tax rate for every rental and extra charge, including confirmed zero tax.");
            if(line.get("sourceRef")!=null&&!rental.getReference().equals(line.get("sourceRef")))throw bad("Invoice charge source does not match the booking.");
        }
    }
    private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,message);}
}
