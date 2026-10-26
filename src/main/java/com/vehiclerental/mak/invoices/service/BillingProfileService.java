package com.vehiclerental.mak.invoices.service;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.invoices.repository.BillingProfileRepository;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

@Service
public class BillingProfileService {
 private static final List<String> SELLER=List.of("name","street","buildingNumber","additionalNumber","district","city","country","postalCode","phone","taxNumber","otherId");
 private static final List<String> BANK=List.of("accountName","bankName","branchName","accountNumber","iban");
 private final BillingProfileRepository profiles;private final JdbcTemplate jdbc;private final ObjectMapper json;private final InvoiceTime time;
 public BillingProfileService(BillingProfileRepository profiles,JdbcTemplate jdbc,ObjectMapper json,InvoiceTime time){this.profiles=profiles;this.jdbc=jdbc;this.json=json;this.time=time;}
 @Transactional(readOnly=true) public Map<String,Object> get(){var profile=profiles.findById(1L).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"MAK billing profile is unavailable."));var data=read(profile.getPayload());data.put("version",profile.getVersion());data.put("updatedAt",profile.getUpdatedAt().toString());data.put("updatedBy",profile.getUpdatedBy());return status(data);}
 @Transactional public Map<String,Object> save(Map<String,Object> request,long actor){
  var profile=profiles.lockProfile().orElseThrow(()->bad("MAK billing profile is unavailable."));
  if(version(request.get("version"))!=profile.getVersion())throw new ApiException(HttpStatus.CONFLICT,"The billing profile changed. Reload it before saving your changes.");
  var data=new LinkedHashMap<String,Object>();data.put("issuer",clean(request.get("issuer"),SELLER));data.put("account",clean(request.get("account"),BANK));data.put("paymentInstructions",limited(request.get("paymentInstructions"),2000));data.put("paymentContact",limited(request.get("paymentContact"),150));
  if(text(map(data.get("issuer")).get("name")).isBlank())throw bad("Enter the MAK seller name.");
  var account=map(data.get("account"));String iban=text(account.get("iban")).replaceAll("\\s","").toUpperCase(Locale.ROOT);
  if(!iban.isBlank()&&!validIban(iban))throw bad("Enter a valid IBAN, or leave it blank and provide an account number or payment instructions.");account.put("iban",iban);
  long next=profile.getVersion()+1;String payload=json.writeValueAsString(data);var now=time.now();
  profile.setVersion(next);profile.setPayload(payload);profile.setUpdatedAt(now);profile.setUpdatedBy(actor);profiles.saveAndFlush(profile);
  jdbc.update("insert into billing_profile_versions(version,payload,updated_at,updated_by) values(?,?,?,?)",next,payload,java.sql.Timestamp.from(now),actor);
  return get();
 }
 /** Fill only missing fields on new drafts; preserve the requested profile version and manual edits. */
 @Transactional(readOnly=true) public Map<String,Object> prefill(Map<String,Object> request){
  Map<String,Object> source;
  if(request.get("billingProfileVersion")==null)source=get();
  else {long requested=version(request.get("billingProfileVersion"));var rows=jdbc.queryForList("select payload from billing_profile_versions where version=?",requested);if(rows.isEmpty())throw new ApiException(HttpStatus.CONFLICT,"The selected billing profile version is unavailable. Reload the profile.");source=read(rows.getFirst().get("payload").toString());source.put("version",requested);}
  var result=new LinkedHashMap<String,Object>(request);
  for(String section:List.of("issuer","account")){
   var submitted=map(request.get(section));var saved=map(source.get(section));var merged=new LinkedHashMap<String,Object>(saved);
   for(var entry:submitted.entrySet())if(!text(entry.getValue()).isBlank())merged.put(entry.getKey(),entry.getValue());
   if(section.equals("issuer")&&"Rental Admin Preview".equals(text(submitted.get("name"))))merged.put("name",saved.get("name"));
   if(section.equals("issuer")&&text(merged.get("street")).isBlank()&&!text(merged.get("address")).isBlank())merged.put("street",merged.get("address"));
   result.put(section,merged);
  }
  if(text(request.get("paymentInstructions")).isBlank())result.put("paymentInstructions",source.get("paymentInstructions"));
  if(text(request.get("paymentContact")).isBlank())result.put("paymentContact",source.get("paymentContact"));
  result.put("billingProfileVersion",source.get("version"));return result;
 }
 public static List<String> missing(Map<String,Object> data){
  var seller=map(data.get("issuer"));var bank=map(data.get("account"));var missing=new ArrayList<String>();
  for(var pair:Map.of("name","Seller name","street","Seller street","city","Seller city","country","Seller country").entrySet())if(text(seller.get(pair.getKey())).isBlank())missing.add(pair.getValue());
  if(text(data.get("paymentInstructions")).isBlank()){
   if(text(bank.get("accountName")).isBlank())missing.add("Account name or payment instructions");
   if(text(bank.get("bankName")).isBlank())missing.add("Bank name or payment instructions");
   if(text(bank.get("iban")).isBlank()&&text(bank.get("accountNumber")).isBlank())missing.add("IBAN/account number or payment instructions");
  }
  return missing;
 }
 private Map<String,Object> status(Map<String,Object> data){var missing=missing(data);data.put("missingFields",missing);data.put("ready",missing.isEmpty());return data;}
 private Map<String,Object> read(String payload){return json.readValue(payload,new TypeReference<LinkedHashMap<String,Object>>(){});}
 private Map<String,Object> clean(Object value,List<String> fields){var input=map(value);var result=new LinkedHashMap<String,Object>();for(String field:fields)result.put(field,limited(input.get(field),field.equals("street")?500:150));return result;}
 private static String limited(Object value,int max){String text=text(value);if(text.length()>max)throw bad("A billing profile field exceeds its permitted length.");return text;}
 private static long version(Object value){try{return new java.math.BigDecimal(text(value)).longValueExact();}catch(Exception e){throw bad("A valid billing profile version is required.");}}
 @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){if(value==null)return new LinkedHashMap<>();if(!(value instanceof Map<?,?>))throw bad("Invalid billing profile details.");return (Map<String,Object>)value;}
 private static String text(Object value){return value==null?"":value.toString().trim();}
 private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,message);}
 private static boolean validIban(String iban){
  if(!iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}"))return false;
  String rotated=iban.substring(4)+iban.substring(0,4);int remainder=0;
  for(char ch:rotated.toCharArray()){String digits=ch>='A'&&ch<='Z'?Integer.toString(ch-'A'+10):Character.toString(ch);for(char digit:digits.toCharArray())remainder=(remainder*10+digit-'0')%97;}
  return remainder==1;
 }
}
