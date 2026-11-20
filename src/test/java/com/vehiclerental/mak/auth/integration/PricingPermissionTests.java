package com.vehiclerental.mak.auth.integration;

import com.vehiclerental.mak.auth.entity.Account;
import com.vehiclerental.mak.auth.repository.AccountRepository;
import com.vehiclerental.mak.auth.repository.AuthControlRepository;
import com.vehiclerental.mak.auth.security.AccountPrincipal;
import com.vehiclerental.mak.auth.security.RolePolicy;
import com.vehiclerental.mak.auth.service.AccountService;
import com.vehiclerental.mak.auth.service.RoleService;
import com.vehiclerental.mak.common.exception.ApiException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Financial visibility and payment collection are Finance capabilities, never implied by operational grants. */
@SpringBootTest @Transactional
class PricingPermissionTests {
    @Autowired RoleService roles; @Autowired AccountService service; @Autowired AccountRepository accounts;
    @Autowired AuthControlRepository controls; @Autowired WebApplicationContext context;
    Account admin; MockMvc mvc;

    @BeforeEach void setup() {
        controls.findById(1).orElseThrow().setBootstrapDone(true);
        admin = account("Admin", null);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    Account account(String role, Long roleId) {
        var a = new Account(); a.setName("Pricing audit"); a.setEmail("pricing-" + UUID.randomUUID() + "@example.invalid");
        a.setPasswordHash("not-a-login-password"); a.setRole(role); a.setCustomRoleId(roleId); a.setStatus("Active"); a.setCreatedAt(Instant.now());
        return accounts.saveAndFlush(a);
    }
    Account custom(String... grants) {
        var r = roles.save(null, new HashMap<>(Map.of("name", "Pricing " + UUID.randomUUID(), "permissions", List.of(grants))), admin.getId());
        return account("Viewer", ((Number) r.get("id")).longValue());
    }
    MockHttpSession session(Account a) {
        var s = new MockHttpSession();
        s.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                new AccountPrincipal(a.getId(), a.getSessionVersion()), null,
                service.permissions(a).stream().map(SimpleGrantedAuthority::new).toList())));
        return s;
    }
    int postJson(MockHttpSession s, String path, String body) throws Exception {
        return mvc.perform(post(path).session(s).with(csrf().asHeader()).contentType("application/json").content(body))
                .andReturn().getResponse().getStatus();
    }

    @Test void onlyAdminManagerAndFinanceHoldPricingByDefault() {
        for (String role : List.of("Admin", "Manager", "Finance")) assertTrue(roles.resolve(role, null).containsAll(RolePolicy.PRICING), role);
        for (String role : List.of("Operations", "Viewer")) {
            var grants = roles.resolve(role, null);
            assertTrue(RolePolicy.PRICING.stream().noneMatch(grants::contains), role);
            assertFalse(grants.contains("payments.refund"), role + " must not refund via bookings.write");
        }
    }

    @Test void pricingPermissionsAreInTheCustomRoleCatalogWithPrerequisites() {
        assertTrue(RolePolicy.catalog().containsAll(RolePolicy.PRICING));
        assertThrows(ApiException.class, () -> custom("pricing.write"), "pricing.write needs pricing.read");
        assertThrows(ApiException.class, () -> custom("payments.record"), "payments.record needs booking or invoice view");
        assertNotNull(custom("pricing.read", "pricing.write"));
        assertNotNull(custom("bookings.read", "payments.record"));
    }

    @Test void bookingPaymentsRequirePaymentsRecordNotBookingEdit() throws Exception {
        String payment = "{\"kind\":\"Payment\",\"amount\":1}";
        assertEquals(403, postJson(session(account("Operations", null)), "/api/bookings/999999/payments", payment));
        assertEquals(403, postJson(session(custom("bookings.read", "bookings.edit")), "/api/bookings/999999/payments", payment));
        int finance = postJson(session(account("Finance", null)), "/api/bookings/999999/payments", payment);
        assertNotEquals(403, finance); assertNotEquals(401, finance);
    }

    @Test void operatorsCannotRecordADepositWhenCreatingABooking() throws Exception {
        var operations = session(account("Operations", null));
        assertEquals(403, postJson(operations, "/api/bookings", "{\"receivedAmount\":50}"));
        assertEquals(403, postJson(operations, "/api/bookings", "{\"receivedAmount\":\"not-a-number\"}"));
        // Without a deposit the request reaches booking validation instead of being refused for payment rights.
        assertEquals(400, postJson(operations, "/api/bookings", "{\"receivedAmount\":0}"));
        assertEquals(400, postJson(operations, "/api/bookings", "{}"));
    }

    @Test void invoicePaymentsRequirePaymentsRecord() throws Exception {
        String payment = "{\"kind\":\"Payment\",\"amount\":1}";
        assertEquals(403, postJson(session(custom("invoices.read", "invoices.edit")), "/api/invoices/999999/payments", payment));
        assertEquals(404, postJson(session(custom("invoices.read", "payments.record")), "/api/invoices/999999/payments", payment));
    }
}
