package com.mojud.customers;

import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import com.mojud.tenancy.RequiresPermission;
import com.mojud.tenancy.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequiresPermission(Permission.CUSTOMERS)
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
    @RequiresPermission({Permission.ORDERS, Permission.CUSTOMERS})
    PagedResult<CustomerService.View> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String phone, @ParameterObject PageQuery paging) {
        if (phone != null) {
            return PagedResult.ofAll(customers.byPhone(phone).map(List::of).orElse(List.of()), paging);
        }
        return customers.list(q, paging);
    }

    /** Deletes the customer (managers and owners): refused while they have orders in progress. */
    @DeleteMapping("/api/v1/customers/{id}")
    @RequiresRole(BusinessRole.MANAGER)
    @RequiresPermission(Permission.CUSTOMERS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        customers.delete(id);
    }

    @GetMapping("/api/v1/customers/{id}")
    @RequiresPermission({Permission.ORDERS, Permission.CUSTOMERS})
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
