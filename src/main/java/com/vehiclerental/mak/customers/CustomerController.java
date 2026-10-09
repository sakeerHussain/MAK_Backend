package com.vehiclerental.mak.customers;

import java.net.URI;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerService customers;
    public CustomerController(CustomerService customers) { this.customers = customers; }

    @GetMapping
    public Page<CustomerResponse> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) String type, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return customers.list(search, type, status, page, size);
    }

    @GetMapping("/{id}") public CustomerResponse get(@PathVariable long id) { return customers.get(id); }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
        CustomerResponse created = customers.create(request);
        return ResponseEntity.created(URI.create("/api/customers/" + created.id())).body(created);
    }

    @PutMapping("/{id}") public CustomerResponse update(@PathVariable long id, @Valid @RequestBody CustomerRequest request) {
        return customers.update(id, request);
    }

    @PatchMapping("/{id}/status") public CustomerResponse status(@PathVariable long id, @Valid @RequestBody CustomerStatusRequest request) {
        return customers.status(id, request);
    }
}
