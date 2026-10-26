package com.mojud.catalog.product;

import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The "running low" list; every member may see it. */
@RestController
class LowStockController {

    private final LowStock lowStock;

    LowStockController(LowStock lowStock) {
        this.lowStock = lowStock;
    }

    @GetMapping("/api/v1/stock/low")
    @RequiresRole(BusinessRole.STAFF)
    List<LowStock.Item> list() {
        return lowStock.list();
    }
}
