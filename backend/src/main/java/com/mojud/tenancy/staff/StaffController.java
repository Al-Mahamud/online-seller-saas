package com.mojud.tenancy.staff;

import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Members and invites of the selected business; owners and managers only (backend-plan.md §7). */
@RestController
@RequestMapping("/api/v1/staff")
@RequiresRole(BusinessRole.MANAGER)
class StaffController {

    private final StaffService staff;
    private final InviteService invites;

    StaffController(StaffService staff, InviteService invites) {
        this.staff = staff;
        this.invites = invites;
    }

    @GetMapping
    List<StaffService.StaffMember> list() {
        return staff.list();
    }

    record RoleChange(@NotNull BusinessRole role) {
    }

    /** MANAGER or STAFF; the owner changes only by transfer. */
    @PatchMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeRole(@PathVariable UUID userId, @Valid @RequestBody RoleChange body) {
        staff.changeRole(userId, body.role());
    }

    /** Counts from the member's next request. */
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID userId) {
        staff.remove(userId);
    }

    record Transfer(@NotBlank String currentPassword) {
    }

    /** The member becomes OWNER and the caller MANAGER. */
    @PostMapping("/{userId}/make-owner")
    @RequiresRole(BusinessRole.OWNER)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void transferOwnership(@PathVariable UUID userId, @Valid @RequestBody Transfer body, HttpServletRequest request) {
        staff.transferOwnership(userId, body.currentPassword(), request.getRemoteAddr());
    }

    @GetMapping("/invites")
    List<InviteResponse> openInvites() {
        return invites.open();
    }

    /** {@code to} is a phone number or an email address; {@code role} is MANAGER or STAFF. */
    record InviteRequest(@NotBlank String to, @NotNull BusinessRole role) {
    }

    @PostMapping("/invites")
    @ResponseStatus(HttpStatus.CREATED)
    InviteResponse invite(@Valid @RequestBody InviteRequest body) {
        return invites.invite(body.to(), body.role());
    }

    @DeleteMapping("/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID inviteId) {
        invites.revoke(inviteId);
    }
}
