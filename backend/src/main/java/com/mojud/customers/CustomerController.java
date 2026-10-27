package com.mojud.customers;

import com.mojud.shared.paging.CursorPage;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Customers: every member works with them (orders need them). */
@RestController
@RequiresRole(BusinessRole.STAFF)
class CustomerController {

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    /**
     * {@code phone}: the one customer with this number (written any way), for the order form; or
     * {@code q}: search by name or part of a phone number; or neither: newest first, paged.
     */
    @GetMapping("/api/v1/customers")
    CursorPage<CustomerService.View> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String phone, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        if (phone != null) {
            return new CursorPage<>(customers.byPhone(phone).map(List::of).orElse(List.of()), null);
        }
        return customers.list(q, cursor, limit);
    }

    @GetMapping("/api/v1/customers/{id}")
    CustomerService.View get(@PathVariable UUID id) {
        return customers.get(id);
    }

    record CreateRequest(@NotNull @Size(max = 200) String name, @NotNull @Size(max = 40) String phone,
            @Size(max = 40) String altPhone, @Size(max = 1000) String address, Integer districtId, Integer areaId,
            @Size(max = 2000) String note) {
    }

    @PostMapping("/api/v1/customers")
    @ResponseStatus(HttpStatus.CREATED)
    CustomerService.View create(@Valid @RequestBody CreateRequest body) {
        return customers.create(new CustomerService.Details(body.name(), body.phone(), body.altPhone(), body.address(),
                body.districtId(), body.areaId(), body.note()));
    }

    record UpdateRequest(@Size(max = 200) String name, @Size(max = 40) String phone, @Size(max = 40) String altPhone,
            @Size(max = 1000) String address, Integer districtId, Integer areaId, Boolean clearLocation,
            @Size(max = 2000) String note, Long version) {
    }

    @PatchMapping("/api/v1/customers/{id}")
    CustomerService.View update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest body) {
        return customers.update(id, new CustomerService.Changes(body.name(), body.phone(), body.altPhone(), body.address(),
                body.districtId(), body.areaId(), Boolean.TRUE.equals(body.clearLocation()), body.note(), body.version()));
    }
}
