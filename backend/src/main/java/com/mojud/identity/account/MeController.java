package com.mojud.identity.account;

import com.mojud.identity.AuthenticatedUser;
import com.mojud.shared.error.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final UserRepository users;

    MeController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    @Transactional(readOnly = true)
    MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return users.findById(user.id()).map(MeResponse::of).orElseThrow(ApiException::notFound);
    }
}
