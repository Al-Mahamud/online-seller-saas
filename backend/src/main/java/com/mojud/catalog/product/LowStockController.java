package com.mojud.catalog.product;

import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import com.mojud.tenancy.RequiresPermission;
import com.mojud.tenancy.Permission;
import org.springdoc.core.annotations.ParameterObject;
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
    @RequiresPermission(Permission.STOCK)
    @RequiresRole(BusinessRole.STAFF)
    PagedResult<LowStock.Item> list(@ParameterObject PageQuery paging) {
        return PagedResult.ofAll(lowStock.list(), paging);
    }
}
