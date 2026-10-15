package com.vehiclerental.mak.inspections.controller;

import com.vehiclerental.mak.inspections.dto.request.InspectionRequest;
import com.vehiclerental.mak.inspections.service.InspectionService;

import java.util.*;
import java.nio.charset.StandardCharsets;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/inspections")
public class InspectionController {
    private final InspectionService service;
    public InspectionController(InspectionService service){this.service=service;}
    @GetMapping public List<Map<String,Object>> list(){return service.list();}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable long id){return service.get(id);}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@Valid @RequestBody InspectionRequest request){return service.save(null,request,new LinkedMultiValueMap<>());}
    @PutMapping(value="/{id}",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> update(@PathVariable long id,@Valid @RequestBody InspectionRequest request){return service.save(id,request,new LinkedMultiValueMap<>());}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createFiles(@Valid @RequestPart("record") InspectionRequest request,@RequestParam MultiValueMap<String,MultipartFile> files){return service.save(null,request,files);}
    @PutMapping(value="/{id}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> updateFiles(@PathVariable long id,@Valid @RequestPart("record") InspectionRequest request,@RequestParam MultiValueMap<String,MultipartFile> files){return service.save(id,request,files);}
    @GetMapping("/{id}/photos/{key}")
    public ResponseEntity<org.springframework.core.io.Resource> photo(@PathVariable long id,@PathVariable String key){
        var file=service.photo(id,key);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(file.name(),StandardCharsets.UTF_8).build().toString()).contentType(MediaType.parseMediaType(file.type())).body(file.resource());
    }
}
