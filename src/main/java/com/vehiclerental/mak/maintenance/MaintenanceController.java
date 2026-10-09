package com.vehiclerental.mak.maintenance;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.*;
import org.springframework.web.multipart.MultipartFile;
@RestController @RequestMapping("/api/maintenance")
public class MaintenanceController {
 private final MaintenanceService service;
 public MaintenanceController(MaintenanceService service){this.service=service;}
 @GetMapping public List<Map<String,Object>> list(){return service.list();}
 @GetMapping("/{id}")public Map<String,Object> get(@PathVariable long id){return service.get(id);}
 @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)public Map<String,Object> create(@RequestBody Map<String,Object> record){return service.save(null,record,new LinkedMultiValueMap<>());}
 @PutMapping(value="/{id}",consumes=MediaType.APPLICATION_JSON_VALUE)public Map<String,Object> update(@PathVariable long id,@RequestBody Map<String,Object> record){return service.save(id,record,new LinkedMultiValueMap<>());}
 @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)public Map<String,Object> createFiles(@RequestPart("record") Map<String,Object> record,@RequestParam MultiValueMap<String,MultipartFile> files){return service.save(null,record,files);}
 @PutMapping(value="/{id}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)public Map<String,Object> updateFiles(@PathVariable long id,@RequestPart("record") Map<String,Object> record,@RequestParam MultiValueMap<String,MultipartFile> files){return service.save(id,record,files);}
 @GetMapping("/{id}/expenses/{key}/receipt")public ResponseEntity<org.springframework.core.io.Resource> receipt(@PathVariable long id,@PathVariable String key){var file=service.receipt(id,key);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString()).contentType(MediaType.parseMediaType(file.type())).body(file.resource());}
}
