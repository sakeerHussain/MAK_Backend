package com.vehiclerental.mak.enquiries;
import com.vehiclerental.mak.common.exception.ApiException;
import com.vehiclerental.mak.fleet.repository.VehicleRepository;
import org.springframework.stereotype.Service;import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;import org.springframework.data.jpa.domain.Specification;
import java.time.*;import java.util.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
@Service
public class EnquiryService {
 private final EnquiryRepository records;private final EnquiryEventRepository events;private final VehicleRepository vehicles;private final TransactionTemplate tx;
 public EnquiryService(EnquiryRepository records,EnquiryEventRepository events,VehicleRepository vehicles,PlatformTransactionManager manager){this.records=records;this.events=events;this.vehicles=vehicles;tx=new TransactionTemplate(manager);}
 public record Receipt(String reference){}
 public record Item(long id,String reference,String kind,String name,String email,String phone,String company,String subject,String message,String category,String preference,String location,LocalDate pickupDate,LocalDate returnDate,Long vehicleId,String vehicleName,String status,String internalNote,long version,Instant createdAt,Instant updatedAt,Long updatedBy){}
 public record Inbox(List<Item> content,int totalPages,long totalElements,int number){}
 public record Change(@jakarta.validation.constraints.NotBlank String status,@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max=4000) String internalNote,@jakarta.validation.constraints.NotNull Long version){}
 public Receipt submit(String kind,EnquiryRequest request){
  String fingerprint=fingerprint(kind,request);
  try{return tx.execute(ignored->{
   var prior=records.findBySubmissionId(request.submissionId());if(prior.isPresent())return receipt(prior.get(),fingerprint);
   if(!text(request.website()).isEmpty())throw bad("Unable to accept this submission.");
   if(kind.equals("Rental")){
    if(text(request.phone()).isEmpty())throw bad("Enter a phone number for your rental enquiry.");
    if(text(request.preference()).isEmpty()||text(request.category()).isEmpty())throw bad("Choose a vehicle category and rental preference.");
    var today=LocalDate.now(ZoneId.of("Asia/Riyadh"));
    if(request.pickupDate()!=null&&request.pickupDate().isBefore(today))throw bad("Pickup date cannot be in the past (KSA time).");
    if(request.returnDate()!=null&&(request.pickupDate()==null||request.returnDate().isBefore(request.pickupDate())))throw bad("Return date must be on or after pickup date.");
   }else if(request.vehicleId()!=null||request.pickupDate()!=null||request.returnDate()!=null)throw bad("Use the rental enquiry form for vehicle and date requests.");
   var e=new WebsiteEnquiry();e.setSubmissionId(request.submissionId());e.setFingerprint(fingerprint);e.setKind(kind);e.setName(text(request.name()));e.setEmail(text(request.email()));e.setPhone(text(request.phone()));e.setCompany(text(request.company()));
   e.setSubject(kind.equals("Rental")?"Rental enquiry":text(request.subject()));if(e.getSubject().isEmpty())throw bad("Choose a subject.");
   e.setMessage(text(request.message()));e.setCategory(text(request.category()));e.setPreference(text(request.preference()));e.setLocation(text(request.location()));e.setPickupDate(request.pickupDate());e.setReturnDate(request.returnDate());
   if(request.vehicleId()!=null){var v=vehicles.findById(request.vehicleId()).filter(x->x.isShowOnPublicSite()&&!"Inactive".equals(x.getStatus())).orElseThrow(()->bad("The selected vehicle is no longer published. Refresh the collection or send a general rental enquiry."));e.setVehicleId(v.getId());e.setVehicleName(v.getMake()+" "+v.getModel());}
   e.setStatus("New");e.setCreatedAt(Instant.now());e.setUpdatedAt(e.getCreatedAt());records.saveAndFlush(e);return new Receipt(reference(e.getId()));
  });}catch(DataIntegrityViolationException conflict){var prior=records.findBySubmissionId(request.submissionId());if(prior.isPresent())return receipt(prior.get(),fingerprint);throw conflict;}
 }
 private Receipt receipt(WebsiteEnquiry e,String fingerprint){if(!e.getFingerprint().equals(fingerprint))throw new ApiException(HttpStatus.CONFLICT,"This submission reference was already used. Reload the form before sending a different message.");return new Receipt(reference(e.getId()));}
 @Transactional(readOnly=true) public Inbox list(int page,int size,String kind,String status,String search){
  if(page<0||size<1||size>100||search.length()>150)throw bad("Invalid inbox page or search.");
  if(!kind.isEmpty()&&!Set.of("Rental","Contact").contains(kind))throw bad("Invalid enquiry type.");validateStatus(status,true);
  Specification<WebsiteEnquiry> filter=(root,query,cb)->{var clauses=new ArrayList<jakarta.persistence.criteria.Predicate>();if(!kind.isEmpty())clauses.add(cb.equal(root.get("kind"),kind));if(!status.isEmpty())clauses.add(cb.equal(root.get("status"),status));if(!search.isBlank()){String value="%"+search.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";clauses.add(cb.or(cb.like(cb.lower(root.get("name")),value,'!'),cb.like(cb.lower(root.get("email")),value,'!'),cb.like(cb.lower(root.get("company")),value,'!'),cb.like(cb.lower(root.get("subject")),value,'!')));}return cb.and(clauses.toArray(jakarta.persistence.criteria.Predicate[]::new));};
  var result=records.findAll(filter,PageRequest.of(page,size,Sort.by(Sort.Order.desc("createdAt"),Sort.Order.desc("id"))));return new Inbox(result.map(this::item).getContent(),result.getTotalPages(),result.getTotalElements(),result.getNumber());
 }
 @Transactional(readOnly=true) public Item get(long id){return item(find(id));}
 @Transactional(readOnly=true) public List<EnquiryEvent> history(long id){find(id);return events.findByEnquiryIdOrderByIdDesc(id);}
 @Transactional public Item update(long id,Change change,long actor){
  var e=find(id);if(change.version()!=e.getVersion())throw new ApiException(HttpStatus.CONFLICT,"This enquiry changed. Reload it before saving.");validateStatus(change.status(),false);
  String note=text(change.internalNote());if(e.getStatus().equals(change.status())&&e.getInternalNote().equals(note))return item(e);
  var event=new EnquiryEvent();event.setEnquiryId(id);event.setActorId(actor);event.setPreviousStatus(e.getStatus());event.setNewStatus(change.status());event.setNoteChanged(!e.getInternalNote().equals(note));event.setCreatedAt(Instant.now());events.save(event);
  e.setStatus(change.status());e.setInternalNote(note);e.setUpdatedAt(event.getCreatedAt());e.setUpdatedBy(actor);records.saveAndFlush(e);return item(e);
 }
 private WebsiteEnquiry find(long id){return records.findById(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Enquiry not found."));}
 private void validateStatus(String status,boolean empty){if(!(empty&&status.isEmpty())&&!Set.of("New","In progress","Closed","Spam").contains(status))throw bad("Choose a valid enquiry status.");}
 private Item item(WebsiteEnquiry e){return new Item(e.getId(),reference(e.getId()),e.getKind(),e.getName(),e.getEmail(),e.getPhone(),e.getCompany(),e.getSubject(),e.getMessage(),e.getCategory(),e.getPreference(),e.getLocation(),e.getPickupDate(),e.getReturnDate(),e.getVehicleId(),e.getVehicleName(),e.getStatus(),e.getInternalNote(),e.getVersion(),e.getCreatedAt(),e.getUpdatedAt(),e.getUpdatedBy());}
 private static String reference(long id){return "MAK-ENQ-"+String.format(Locale.ROOT,"%06d",id);}
 private static String text(String value){return value==null?"":value.trim();}
 private static String fingerprint(String kind,EnquiryRequest r){try{String value=String.join(Character.toString(0),kind,text(r.name()),text(r.email()),text(r.phone()),text(r.company()),text(r.subject()),text(r.message()),text(r.category()),text(r.preference()),text(r.location()),Objects.toString(r.pickupDate(),""),Objects.toString(r.returnDate(),""),Objects.toString(r.vehicleId(),""),text(r.website()));return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,message);}
}
