package com.mojud.tenancy.staff;

import com.mojud.identity.AuthenticatedUser;
import com.mojud.tenancy.web.SelectedBusiness;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The invitee's side. The token travels in the request body, not the URL, so it stays out of
 * access logs. The web app's invite page reads it from the link's fragment ({@code /invites#token}),
 * which browsers never send to servers.
 */
@RestController
class InviteController {

    private final InviteService invites;

    InviteController(InviteService invites) {
        this.invites = invites;
    }

    record TokenRequest(@NotBlank @Size(max = 100) String token) {
    }

    /** Open to anyone holding the link: which business, which role, and who invited. */
    @PostMapping("/api/v1/public/invites/lookup")
    InviteService.Preview lookup(@Valid @RequestBody TokenRequest body) {
        return invites.preview(body.token());
    }

    /** The joined business becomes the selected one. */
    @PostMapping("/api/v1/invites/accept")
    InviteService.Joined accept(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody TokenRequest body,
            HttpServletRequest request) {
        InviteService.Joined joined = invites.accept(user.id(), body.token());
        SelectedBusiness.set(request, joined.businessId());
        return joined;
    }
}
