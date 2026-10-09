package com.vehiclerental.mak.notifications.booking;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import com.vehiclerental.mak.auth.Permissions;
import com.vehiclerental.mak.common.ApiException;
import com.vehiclerental.mak.rentals.RentalRecord;
import com.vehiclerental.mak.notifications.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class BookingNotificationService {
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 private final EmailService emails;
 private final int pickupMinutes,confirmationMinutes,escalationMinutes;
 private final boolean emailEscalation;
 private static final ZoneId ZONE=ZoneId.of("Asia/Riyadh");
 public BookingNotificationService(JdbcTemplate jdbc,ObjectMapper json,EmailService emails,
  @Value("${app.notifications.pickup-minutes:60}") int pickupMinutes,
  @Value("${app.notifications.confirmation-minutes:120}") int confirmationMinutes,
  @Value("${app.notifications.escalation-minutes:15}") int escalationMinutes,
  @Value("${app.notifications.email-escalation:false}") boolean emailEscalation,@Value("${app.mail.enabled:false}") boolean mailEnabled){
  this.jdbc=jdbc;this.json=json;this.emails=emails;this.pickupMinutes=Math.max(1,pickupMinutes);this.confirmationMinutes=Math.max(1,confirmationMinutes);this.escalationMinutes=Math.max(1,escalationMinutes);this.emailEscalation=emailEscalation && mailEnabled;
 }
 private void lock(){jdbc.queryForList("select id from booking_notification_control where id=1 for update");}
 private Instant instant(Object value){if(value instanceof OffsetDateTime time)return time.toInstant();if(value instanceof Instant time)return time;return ((Timestamp)value).toInstant();}
 private Timestamp stamp(){return Timestamp.from(Instant.now());}
 private void changed(long account){jdbc.update("insert into booking_notification_events(account_id,created_at) values(?,?)",account,stamp());}
 private String module(String kind){return "Corporate".equals(kind)?"corporate-trips":"bookings";}
 private LocalDateTime local(Object value){return ((Timestamp)value).toLocalDateTime();}
 private Map<String,Object> snapshot(Map<String,Object> rental){
  var payload=json.readTree(rental.get("payload").toString());var result=new LinkedHashMap<String,Object>();
  result.put("reference",rental.get("reference"));result.put("customer",payload.path("Corporate".equals(rental.get("kind"))?"companyName":"customerName").asText("Customer"));
  result.put("pickupAt",local(rental.get("pickup_at")).toString());result.put("returnAt",local(rental.get("return_at")).toString());
  result.put("pickupLocation",payload.path("pickupLocation").asText());result.put("vehicleAssigned",rental.get("vehicle_id")!=null);
  boolean self="Self-drive".equals(payload.path("Corporate".equals(rental.get("kind"))?"serviceMode":"driverMode").asText());
  result.put("driver",self?"Self-drive":rental.get("driver_id")==null?"Unassigned":"Assigned");return result;
 }
 private boolean missing(Map<String,Object> r){var s=snapshot(r);return !Boolean.TRUE.equals(s.get("vehicleAssigned")) || "Unassigned".equals(s.get("driver"));}
 private long add(Map<String,Object> r,String key,String type,String priority,String title,boolean actionable,boolean supervisors){
  var existing=jdbc.queryForList("select id from booking_alerts where alert_key=?",key);
  long id;
  if(existing.isEmpty()){
   jdbc.update("insert into booking_alerts(rental_id,kind,alert_key,type,priority,title,snapshot,actionable,created_at) values(?,?,?,?,?,?,?,?,?)",r.get("id"),r.get("kind"),key,type,priority,title,json.writeValueAsString(snapshot(r)),actionable,stamp());
   id=jdbc.queryForObject("select id from booking_alerts where alert_key=?",Long.class,key);
  }else id=((Number)existing.get(0).get("id")).longValue();
  recipients(id,r.get("kind").toString(),supervisors);return id;
 }
 private void recipients(long alert,String kind,boolean supervisors){
  for(var account:jdbc.queryForList("select id,role from auth_accounts where status='Active'")){
   String role=account.get("role").toString();if(!Permissions.forRole(role).contains(module(kind)+".write") || (supervisors && !Set.of("Admin","Manager").contains(role)))continue;
   long id=((Number)account.get("id")).longValue();
   if(jdbc.queryForObject("select count(*) from booking_alert_recipients where alert_id=? and account_id=?",Long.class,alert,id)==0){jdbc.update("insert into booking_alert_recipients(alert_id,account_id) values(?,?)",alert,id);changed(id);}
  }
 }
 private Map<String,Object> rental(long id){return jdbc.queryForMap("select * from rental_records where id=?",id);}
 // Runs within the rental transaction. Rolled-back changes cannot leak to SSE.
 @Transactional
 public void bookingChanged(RentalRecord record,String type){
  lock();var r=rental(record.getId());String title=switch(type){case "Created"->"New booking";case "Cancelled"->"Booking cancelled";case "Rescheduled"->"Booking rescheduled";case "Assigned"->"Assignment updated";default->"Booking status updated";};
  String priority=Set.of("Cancelled","Rescheduled").contains(type)?"Action required":"Information";
  add(r,"event:"+UUID.randomUUID(),type,priority,title,false,false);
  evaluate(r,LocalDateTime.now(ZONE));
 }
 @Transactional
 public void scan(){
  lock();var now=LocalDateTime.now(ZONE);
  for(var r:jdbc.queryForList("select * from rental_records where status in ('Pending','Confirmed','Active')"))evaluate(r,now);
  for(var a:jdbc.queryForList("select * from booking_alerts where actionable=true and resolved_at is null")){
   var r=rental(((Number)a.get("rental_id")).longValue());
   if(!valid(a,r,now)){resolve(((Number)a.get("id")).longValue());continue;}
   recipients(((Number)a.get("id")).longValue(),a.get("kind").toString(),"Escalation".equals(a.get("type")));
   if(emailEscalation && "Escalation".equals(a.get("type")))queueEscalation(((Number)a.get("id")).longValue(),r,a.get("title").toString());
   if("Critical".equals(a.get("priority")) && !"Escalation".equals(a.get("type")) && !Boolean.TRUE.equals(a.get("escalated"))
    && instant(a.get("created_at")).isBefore(Instant.now().minusSeconds(escalationMinutes*60L))
    && jdbc.queryForObject("select count(*) from booking_alert_recipients where alert_id=? and acknowledged_at is not null",Long.class,a.get("id"))==0){
    long escalation=add(r,"escalation:"+a.get("id"),"Escalation","Critical","Supervisor follow-up: "+a.get("title"),true,true);
    jdbc.update("update booking_alerts set escalated=true where id=?",a.get("id"));
    if(emailEscalation)queueEscalation(escalation,r,a.get("title").toString());
   }
  }
 }
 private void evaluate(Map<String,Object> r,LocalDateTime now){
  String status=r.get("status").toString();var pickup=local(r.get("pickup_at"));var end=local(r.get("return_at"));String base=r.get("id")+":"+pickup;
  if("Pending".equals(status) && !pickup.isAfter(now.plusMinutes(confirmationMinutes)))add(r,base+":confirmation","Confirmation","Action required","Booking needs confirmation",true,false);
  if("Confirmed".equals(status)){
   if("Advance".equals(r.get("booking_type")) && missing(r) && !pickup.toLocalDate().isAfter(now.toLocalDate().plusDays(1))){
    String stage=pickup.isAfter(now.plusMinutes(pickupMinutes))?pickup.toLocalDate().equals(now.toLocalDate())?"today":"tomorrow":"urgent";
    add(r,base+":assignment:"+stage,"Assignment",stage.equals("urgent")?"Critical":"Action required",stage.equals("tomorrow")?"Plan tomorrow's assignment (assign on trip day)":"Vehicle / driver assignment needed",true,false);
   }
   if(!pickup.isAfter(now))add(r,base+":handover","Missed handover","Critical","Pickup overdue — handover not recorded",true,false);
   else if(!pickup.isAfter(now.plusMinutes(pickupMinutes)))add(r,base+":pickup","Pickup","Action required","Pickup approaching",true,false);
  }
  if("Active".equals(status) && !end.isAfter(now))add(r,r.get("id")+":"+end+":return","Overdue return","Critical","Vehicle return overdue",true,false);
  for(var a:jdbc.queryForList("select * from booking_alerts where rental_id=? and actionable=true and resolved_at is null",r.get("id"))){
   if(!valid(a,r,now))resolve(((Number)a.get("id")).longValue());
   else {String latest=json.writeValueAsString(snapshot(r));if(!latest.equals(a.get("snapshot"))){jdbc.update("update booking_alerts set snapshot=? where id=?",latest,a.get("id"));signalRecipients(((Number)a.get("id")).longValue());}}
  }
 }
 private boolean valid(Map<String,Object> a,Map<String,Object> r,LocalDateTime now){
  String type=a.get("type").toString(),status=r.get("status").toString();
  if(type.equals("Escalation")){
   long source=Long.parseLong(a.get("alert_key").toString().substring(11));var original=jdbc.queryForMap("select * from booking_alerts where id=?",source);
   return original.get("resolved_at")==null && jdbc.queryForObject("select count(*) from booking_alert_recipients where alert_id=? and acknowledged_at is not null",Long.class,source)==0 && valid(original,r,now);
  }
  var old=json.readTree(a.get("snapshot").toString());
  if(!old.path(type.equals("Overdue return")?"returnAt":"pickupAt").asText().equals(local(r.get(type.equals("Overdue return")?"return_at":"pickup_at")).toString()))return false;
  return switch(type){
   case "Confirmation" -> status.equals("Pending");
   case "Assignment" -> status.equals("Confirmed") && "Advance".equals(r.get("booking_type")) && missing(r)
     && (a.get("alert_key").toString().endsWith(":urgent") || (a.get("alert_key").toString().endsWith(":today") ? local(r.get("pickup_at")).isAfter(now.plusMinutes(pickupMinutes)) : local(r.get("pickup_at")).toLocalDate().isAfter(now.toLocalDate())));
   case "Pickup" -> status.equals("Confirmed") && local(r.get("pickup_at")).isAfter(now);
   case "Missed handover" -> status.equals("Confirmed") && !local(r.get("pickup_at")).isAfter(now);
   case "Overdue return" -> status.equals("Active") && !local(r.get("return_at")).isAfter(now);
   default -> false;
  };
 }
 private void resolve(long id){jdbc.update("update booking_alerts set resolved_at=? where id=? and resolved_at is null",stamp(),id);signalRecipients(id);}
 private void signalRecipients(long id){for(var row:jdbc.queryForList("select account_id from booking_alert_recipients where alert_id=?",id))changed(((Number)row.get("account_id")).longValue());}
 private void queueEscalation(long id,Map<String,Object> r,String title){
  for(var row:jdbc.queryForList("select n.account_id,a.email from booking_alert_recipients n join auth_accounts a on a.id=n.account_id where n.alert_id=? and n.email_queued=false",id)){
   emails.bookingEscalation(id,row.get("email").toString(),r.get("reference").toString(),title);
   jdbc.update("update booking_alert_recipients set email_queued=true where alert_id=? and account_id=?",id,row.get("account_id"));
  }
 }
 public long cursor(long account){return jdbc.queryForObject("select coalesce(max(id),0) from booking_notification_events where account_id=?",Long.class,account);}
 private List<String> kinds(Set<String> permissions){var kinds=new ArrayList<String>();if(permissions.contains("bookings.write"))kinds.add("Individual");if(permissions.contains("corporate-trips.write"))kinds.add("Corporate");return kinds;}
 @Transactional(readOnly=true)
 public Map<String,Object> list(long account,Set<String> permissions,String filter,String kind,String priority,String type,String from,String to,int page){
  var kinds=kinds(permissions);var args=new ArrayList<Object>();args.add(account);String where=" n.account_id=?";
  if(kinds.isEmpty())where+=" and 1=0";else {where+=" and a.kind in ("+String.join(",",Collections.nCopies(kinds.size(),"?"))+")";args.addAll(kinds);}
  if("Unread".equals(filter))where+=" and n.read_at is null";
  if("Unresolved".equals(filter))where+=" and a.actionable=true and a.resolved_at is null";
  if("Unacknowledged".equals(filter))where+=" and a.actionable=true and a.resolved_at is null and n.acknowledged_at is null";
  if(kind!=null&&!kind.isBlank()){where+=" and a.kind=?";args.add(kind);}
  if(priority!=null&&!priority.isBlank()){where+=" and a.priority=?";args.add(priority);}
  if(type!=null&&!type.isBlank()){where+=" and a.type=?";args.add(type);}
  try{if(from!=null&&!from.isBlank()){where+=" and a.created_at>=?";args.add(Timestamp.from(LocalDate.parse(from).atStartOfDay(ZONE).toInstant()));}if(to!=null&&!to.isBlank()){where+=" and a.created_at<?";args.add(Timestamp.from(LocalDate.parse(to).plusDays(1).atStartOfDay(ZONE).toInstant()));}}catch(RuntimeException e){throw new ApiException(HttpStatus.BAD_REQUEST,"Enter valid notification dates.");}
  String join=" from booking_alerts a join booking_alert_recipients n on n.alert_id=a.id where "+where;
  long total=jdbc.queryForObject("select count(*)"+join,Long.class,args.toArray());
  args.add(25);args.add(Math.max(0,page)*25);
  var rows=jdbc.queryForList("select a.*,n.read_at,n.acknowledged_at"+join+" order by case when a.resolved_at is null and a.actionable=true then 0 else 1 end,case a.priority when 'Critical' then 0 when 'Action required' then 1 else 2 end,a.id desc limit ? offset ?",args.toArray());
  return Map.of("items",rows.stream().map(this::response).toList(),"total",total,"page",Math.max(0,page),"cursor",cursor(account));
 }
 private Map<String,Object> response(Map<String,Object> r){var out=new LinkedHashMap<String,Object>();out.put("id",r.get("id"));out.put("bookingId",r.get("rental_id"));out.put("kind",r.get("kind"));out.put("type",r.get("type"));out.put("priority",r.get("priority"));out.put("title",r.get("title"));out.put("details",json.readTree(r.get("snapshot").toString()));out.put("actionable",r.get("actionable"));out.put("acknowledgedBy",jdbc.queryForList("select a.name from booking_alert_recipients n join auth_accounts a on a.id=n.account_id where n.alert_id=? and n.acknowledged_at is not null",String.class,r.get("id")));for(String key:List.of("created_at","resolved_at","read_at","acknowledged_at")){String camel=switch(key){case "created_at"->"createdAt";case "resolved_at"->"resolvedAt";case "read_at"->"readAt";default->"acknowledgedAt";};out.put(camel,r.get(key)==null?null:instant(r.get(key)).toString());}return out;}
 @Transactional
 public void mark(long account,Set<String> permissions,long id,boolean acknowledge){
  lock();var found=jdbc.queryForList("select a.* from booking_alerts a join booking_alert_recipients n on n.alert_id=a.id where a.id=? and n.account_id=?",id,account);
  if(found.isEmpty()||!kinds(permissions).contains(found.get(0).get("kind")))throw new ApiException(HttpStatus.NOT_FOUND,"Notification not found.");
  if(acknowledge)jdbc.update("update booking_alert_recipients set read_at=coalesce(read_at,?),acknowledged_at=coalesce(acknowledged_at,?) where alert_id=? and account_id=?",stamp(),stamp(),id,account);
  else jdbc.update("update booking_alert_recipients set read_at=coalesce(read_at,?) where alert_id=? and account_id=?",stamp(),id,account);
  changed(account);
 }
 @Transactional public void readAll(long account,Set<String> permissions){lock();for(String kind:kinds(permissions))jdbc.update("update booking_alert_recipients set read_at=coalesce(read_at,?) where account_id=? and alert_id in(select id from booking_alerts where kind=?)",stamp(),account,kind);changed(account);}
 @Transactional(readOnly=true) public Map<String,Object> summary(long account,Set<String> permissions){
  var kinds=kinds(permissions);long unread=0,critical=0,unresolved=0;
  for(String kind:kinds){var counts=jdbc.queryForMap("select count(case when n.read_at is null then 1 end) unread,count(case when a.actionable=true and a.resolved_at is null then 1 end) unresolved,count(case when a.priority='Critical' and a.actionable=true and a.resolved_at is null and n.acknowledged_at is null then 1 end) critical from booking_alerts a join booking_alert_recipients n on n.alert_id=a.id where n.account_id=? and a.kind=?",account,kind);unread+=((Number)counts.get("unread")).longValue();unresolved+=((Number)counts.get("unresolved")).longValue();critical+=((Number)counts.get("critical")).longValue();}
  return Map.of("unread",unread,"critical",critical,"unresolved",unresolved,"cursor",cursor(account),"emailEscalation",emailEscalation,"pickupMinutes",pickupMinutes,"escalationMinutes",escalationMinutes);
 }
}
