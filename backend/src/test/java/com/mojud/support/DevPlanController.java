package com.mojud.support;

import com.mojud.shared.db.system.SystemDatabase;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /dev/plan?businessId=…&code=STARTER} puts one business on a plan with no payment, so
 * end-to-end tests can reach a plan's limits. Exists only in test sources.
 */
@Hidden
@RestController
class DevPlanController {

    private final SystemDatabase database;

    DevPlanController(SystemDatabase database) {
        this.database = database;
    }

    @PostMapping("/dev/plan")
    ResponseEntity<Void> setPlan(@RequestParam UUID businessId, @RequestParam String code) {
        database.jdbc().sql("update subscriptions set plan_code = ? where business_id = ?")
                .params(code, businessId)
                .update();
        return ResponseEntity.noContent().build();
    }
}
