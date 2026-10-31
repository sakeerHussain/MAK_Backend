package com.vehiclerental.mak.invoices.service;

import com.vehiclerental.mak.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.math.*;
import java.util.*;

/** Five-field data QR only. This does not perform ZATCA clearance or reporting. */
public final class InvoicePhaseOneQr {
 private InvoicePhaseOneQr() {}
 public static String encode(Map<String,Object> invoice,Instant issuedAt) {
  Map<?,?> seller=invoice.get("issuer") instanceof Map<?,?> m?m:Map.of();
  String name=Objects.toString(seller.get("name"),"").trim(),vat=Objects.toString(seller.get("taxNumber"),"").trim();
  if(name.isEmpty() || !vat.matches("[0-9]{15}"))throw bad("Enter the seller name and a 15-digit VAT number before generating the invoice QR.");
  BigDecimal net=BigDecimal.ZERO,tax=BigDecimal.ZERO;
  if(invoice.get("lines") instanceof List<?> lines) for(Object value:lines) {
   Map<?,?> line=(Map<?,?>)value;
   BigDecimal gross=n(line.get("quantity")).multiply(n(line.get("unitPrice"))).setScale(2,RoundingMode.HALF_UP);
   BigDecimal taxable=gross.subtract(gross.multiply(n(line.get("discountPercent"))).divide(BigDecimal.valueOf(100),2,RoundingMode.HALF_UP));
   net=net.add(taxable);tax=tax.add(taxable.multiply(n(line.get("taxPercent"))).divide(BigDecimal.valueOf(100),2,RoundingMode.HALF_UP));
  }
  String[] fields={name,vat,issuedAt.toString(),money(net.add(tax)),money(tax)};
  var out=new ByteArrayOutputStream();
  for(int i=0;i<fields.length;i++) {byte[] bytes=fields[i].getBytes(StandardCharsets.UTF_8);if(bytes.length>255)throw bad("Seller name exceeds the five-field QR limit of 255 UTF-8 bytes. Review the registered invoice name.");out.write(i+1);out.write(bytes.length);out.writeBytes(bytes);}
  return Base64.getEncoder().encodeToString(out.toByteArray());
 }
 public static Instant issueInstant(String value) {
  try{return Instant.parse(value);}catch(java.time.format.DateTimeParseException ignored){
   try{return OffsetDateTime.parse(value).toInstant();}catch(java.time.format.DateTimeParseException legacy){return LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Riyadh")).toInstant();}
  }
 }
 private static BigDecimal n(Object v){return v==null?BigDecimal.ZERO:new BigDecimal(v.toString());}
 private static String money(BigDecimal v){return v.setScale(2,RoundingMode.HALF_UP).toPlainString();}
 private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,message);}
}
