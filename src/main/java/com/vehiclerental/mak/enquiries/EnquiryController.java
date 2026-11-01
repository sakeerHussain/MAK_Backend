package com.vehiclerental.mak.enquiries;
import org.springframework.web.bind.annotation.*;import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;import com.vehiclerental.mak.auth.security.AccountPrincipal;import jakarta.validation.Valid;
@RestController @RequestMapping("/api")
public class EnquiryController {
 private final EnquiryService service;public EnquiryController(EnquiryService service){this.service=service;}
 @PostMapping("/public/rental-enquiries") public ResponseEntity<EnquiryService.Receipt> rental(@Valid @RequestBody EnquiryRequest r){return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.submit("Rental",r));}
 @PostMapping("/public/contact-messages") public ResponseEntity<EnquiryService.Receipt> contact(@Valid @RequestBody EnquiryRequest r){return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.submit("Contact",r));}
 @GetMapping("/enquiries") public ResponseEntity<EnquiryService.Inbox> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String kind,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String search){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(page,size,kind,status,search));}
 @GetMapping("/enquiries/{id}") public ResponseEntity<EnquiryService.Item> get(@PathVariable long id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));}
 @GetMapping("/enquiries/{id}/history") public ResponseEntity<?> history(@PathVariable long id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(id));}
 @PatchMapping("/enquiries/{id}") public EnquiryService.Item update(@PathVariable long id,@Valid @RequestBody EnquiryService.Change change,@AuthenticationPrincipal AccountPrincipal principal){return service.update(id,change,principal.id());}
}
