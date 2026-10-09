package com.vehiclerental.mak.customers;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.vehiclerental.mak.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringBootTest
class CustomerQuoteTests {
    @Autowired CustomerService customers;
    @Autowired QuoteService quotes;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;

    private CustomerRequest customer(String type, String name) {
        return new CustomerRequest(type, name, "+966500000101", "customer@example.com", "Al Khobar", "Active",
                "CR-TEST", "123456789012345", "Accounts Person", "Main Street", "Al Khobar", "Saudi Arabia",
                "Net 30", new BigDecimal("50"), 15, 15);
    }
    private CustomerResponse createCustomer() { return customers.create(customer("Corporate", "Test " + UUID.randomUUID())); }
    private QuoteRequest request(String status, List<QuoteRequest.Route> routes) {
        LocalDate today = LocalDate.now();
        return new QuoteRequest("Q-" + UUID.randomUUID(), status, today, today.plusDays(20), today, today.plusMonths(2),
                new BigDecimal("15"), "Net 30", "Agreed scope", routes,
                List.of(new QuoteRequest.Monthly("monthly-1", "Coaster", "20000", "1000", "30")),
                List.of(new QuoteRequest.ServiceRate("service-1", "Local trip", "Trip", "80")),
                new QuoteRequest.Extras("2.5", "", "70", "10", "500", "750"));
    }
    private List<QuoteRequest.Route> routes() {
        return List.of(new QuoteRequest.Route("route-1", "Al Khobar", "", "Dammam", "Sedan", "800"),
                new QuoteRequest.Route("route-2", "Jubail", "Dammam, Al Khobar", "Riyadh", "SUV", "950"));
    }
    private QuoteApprovalRequest approval() {
        return new QuoteApprovalRequest("Customer Manager", LocalDate.now(), "Email", "Email reference TEST-1", null);
    }

    @Test void customerPersistenceFilteringAndDeactivation() {
        CustomerResponse created = createCustomer();
        assertEquals(created.name(), customers.get(created.id()).name());
        assertEquals("customer@example.com", created.billingEmail());
        assertEquals(1, customers.list(created.name(), "Corporate", "Active", 0, 20).getTotalElements());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> customers.create(customer("Corporate", created.name().toUpperCase()))).status());
        assertEquals("Inactive", customers.status(created.id(), new CustomerStatusRequest("Inactive")).status());
        assertThrows(ApiException.class, () -> quotes.create(created.id(), request("Draft", routes())));
    }

    @Test void quoteRoundTripApprovalLockAndRevision() {
        CustomerResponse customer = createCustomer();
        QuoteResponse first = quotes.create(customer.id(), request("Shared", routes()));
        assertEquals(1, first.version());
        assertEquals("corporate:" + customer.id(), first.customerKey());
        assertEquals("Dammam, Al Khobar", quotes.get(first.id()).routes().get(1).via());
        QuoteResponse approved = quotes.approve(first.id(), approval());
        assertEquals("Approved", approved.status());
        assertEquals("Customer Manager", quotes.get(first.id()).approval().by());
        assertThrows(ApiException.class, () -> quotes.update(first.id(), request("Draft", routes())));
        QuoteResponse revised = quotes.create(customer.id(), request("Shared", routes()));
        assertEquals(2, revised.version());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> quotes.approve(revised.id(), approval())).status());
        quotes.transition(first.id(), "Superseded");
        assertEquals("Approved", quotes.approve(revised.id(), approval()).status());
        assertEquals("Superseded", quotes.get(first.id()).status());
        assertEquals("800", quotes.get(first.id()).routes().getFirst().amount());
    }

    @Test void invalidRatesAndApprovalAreRejected() {
        CustomerResponse customer = createCustomer();
        var duplicate = List.of(routes().getFirst(), new QuoteRequest.Route("other", " AL KHOBAR ", "", "dammam", "sedan", "900"));
        assertThrows(ApiException.class, () -> quotes.create(customer.id(), request("Shared", duplicate)));
        QuoteResponse draft = quotes.create(customer.id(), request("Draft", List.of()));
        assertThrows(ApiException.class, () -> quotes.approve(draft.id(), approval()));
        QuoteResponse shared = quotes.create(customer.id(), request("Shared", routes()));
        assertThrows(ApiException.class, () -> quotes.approve(shared.id(), new QuoteApprovalRequest("Tester", LocalDate.now().plusDays(1), "Email", "Ref", null)));
        assertThrows(ApiException.class, () -> quotes.create(customer.id(), request("Approved", routes())));
    }

    @Test void evidencePersistsAndLocksWithApproval() {
        QuoteResponse quote = quotes.create(createCustomer().id(), request("Shared", routes()));
        byte[] bytes = "%PDF-1.7 test approval".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        quotes.uploadEvidence(quote.id(), new MockMultipartFile("file", "approval.pdf", "application/pdf", bytes));
        assertArrayEquals(bytes, quotes.evidence(quote.id()).getFileData());
        assertNotNull(quotes.get(quote.id()).evidenceUrl());
        quotes.approve(quote.id(), approval());
        assertEquals("approval.pdf", quotes.get(quote.id()).approval().fileName());
        assertThrows(ApiException.class, () -> quotes.uploadEvidence(quote.id(), new MockMultipartFile("file", "other.pdf", "application/pdf", bytes)));
    }

    @Test void httpCreatesCustomerAndQuoteAndReturnsValidationErrors() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var result = mvc.perform(post("/api/customers").contentType("application/json")
                .content(json.writeValueAsString(customer("Individual", "HTTP " + UUID.randomUUID()))))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/customers/" + id + "/quotes").contentType("application/json")
                .content(json.writeValueAsString(request("Shared", routes()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerKey").value("individual:" + id))
                .andExpect(jsonPath("$.routes[0].amount").value("800"));
        mvc.perform(post("/api/customers").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/customers").param("size", "101")).andExpect(status().isBadRequest());
    }
}
