package com.mojud.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestAccounts.Account;
import com.mojud.support.TestBusinesses;
import com.mojud.support.TestClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Staff invites, roles, removal and ownership transfer (implementation-plan.md 2b). */
@IntegrationTest
class StaffTests {

    private static final Pattern INVITE_LINK = Pattern.compile("/invites#([A-Za-z0-9_-]+)");

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    TestClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // Invites.

    @Test
    void someoneNewSignsUpWithTheInvitedEmailAndJoins() {
        Owner owner = newBusiness("Kurti House");
        String email = TestAccounts.newEmail();

        owner.client.post("/api/v1/staff/invites", Map.of("to", email, "role", "STAFF")).expectStatus(201);
        String token = tokenSentTo(email);

        ApiResponse preview = new ApiClient(port).post("/api/v1/public/invites/lookup", Map.of("token", token))
                .expectStatus(200);
        assertThat(preview.<String>json("$.businessName")).isEqualTo("Kurti House");
        assertThat(preview.<String>json("$.role")).isEqualTo("STAFF");
        assertThat(preview.<String>json("$.invitedBy")).isEqualTo("Test Seller");
        assertThat(preview.<String>json("$.sentTo")).startsWith(email.substring(0, 1)).contains("•••@example.com");

        ApiClient invitee = new ApiClient(port).withHeader("X-Forwarded-For", "203.0.113.100");
        signUpWithEmail(invitee, email);
        ApiResponse joined = invitee.post("/api/v1/invites/accept", Map.of("token", token)).expectStatus(200);

        assertThat(joined.<String>json("$.role")).isEqualTo("STAFF");
        assertThat(invitee.get("/api/v1/business").expectStatus(200).<String>json("$.name")).isEqualTo("Kurti House");
        List<String> roles = owner.client.get("/api/v1/staff").expectStatus(200).json("$[*].role");
        assertThat(roles).containsExactly("OWNER", "STAFF");
    }

    @Test
    void anExistingUserJoinsAndKeepsTheirOwnBusiness() {
        Owner owner = newBusiness("Shared Shop");
        ApiClient member = new ApiClient(port);
        Account account = TestAccounts.signUp(member, messages);
        TestBusinesses.create(member, "Member's Own Shop");

        owner.client.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "MANAGER")).expectStatus(201);
        member.post("/api/v1/invites/accept", Map.of("token", tokenSentTo(account.email()))).expectStatus(200);

        assertThat(member.get("/api/v1/business").<String>json("$.role")).isEqualTo("MANAGER");
        assertThat(member.get("/api/v1/businesses").<List<String>>json("$[*].name"))
                .containsExactlyInAnyOrder("Shared Shop", "Member's Own Shop");
    }

    @Test
    void aForwardedLinkIsUselessToAnotherAccount() {
        Owner owner = newBusiness("Careful Shop");
        String invitedEmail = TestAccounts.newEmail();
        owner.client.post("/api/v1/staff/invites", Map.of("to", invitedEmail, "role", "STAFF")).expectStatus(201);
        String token = tokenSentTo(invitedEmail);

        ApiClient stranger = new ApiClient(port);
        TestAccounts.signUp(stranger, messages);

        ApiResponse refused = stranger.post("/api/v1/invites/accept", Map.of("token", token))
                .expectError(403, "invite.wrong_account");
        assertThat(refused.<String>json("$.detail")).contains("•••@example.com");
        assertThat(owner.client.get("/api/v1/staff").<List<Object>>json("$")).hasSize(1);
    }

    @Test
    void anInviteWorksOnce() {
        Owner owner = newBusiness("Once Shop");
        ApiClient member = new ApiClient(port);
        Account account = TestAccounts.signUp(member, messages);
        owner.client.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        String token = tokenSentTo(account.email());
        member.post("/api/v1/invites/accept", Map.of("token", token)).expectStatus(200);

        member.post("/api/v1/invites/accept", Map.of("token", token)).expectError(404, "invite.invalid");
        new ApiClient(port).post("/api/v1/public/invites/lookup", Map.of("token", token)).expectError(404, "invite.invalid");
    }

    @Test
    void revokedExpiredReplacedAndMadeUpInvitesAreInvalid() {
        Owner owner = newBusiness("Strict Invites");
        ApiClient member = new ApiClient(port);
        Account account = TestAccounts.signUp(member, messages);

        String inviteId = owner.client.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF"))
                .expectStatus(201).json("$.id");
        String revoked = tokenSentTo(account.email());
        owner.client.delete("/api/v1/staff/invites/" + inviteId).expectStatus(204);
        member.post("/api/v1/invites/accept", Map.of("token", revoked)).expectError(404, "invite.invalid");

        owner.client.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        String replaced = tokenSentTo(account.email());
        owner.client.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "MANAGER")).expectStatus(201);
        String current = tokenSentTo(account.email());
        member.post("/api/v1/invites/accept", Map.of("token", replaced)).expectError(404, "invite.invalid");
        assertThat(owner.client.get("/api/v1/staff/invites").<List<String>>json("$[*].role")).containsExactly("MANAGER");

        clock.advance(Duration.ofDays(7).plusMinutes(1));
        member.post("/api/v1/invites/accept", Map.of("token", current)).expectError(404, "invite.invalid");
        member.post("/api/v1/invites/accept", Map.of("token", "made-up-token")).expectError(404, "invite.invalid");
    }

    @Test
    void invitesArriveInTheInvitersLanguageWithALinkToTheWebApp() {
        Owner owner = newBusiness("Bangla Shop");
        owner.client.patch("/api/v1/me", Map.of("locale", "bn")).expectStatus(200);
        String email = TestAccounts.newEmail();

        owner.client.post("/api/v1/staff/invites", Map.of("to", email, "role", "STAFF")).expectStatus(201);

        CapturedMessages.Message message = messages.to(email).get(0);
        assertThat(message.subject()).contains("Bangla Shop").contains("আমন্ত্রণ");
        assertThat(message.body()).contains("স্টাফ").contains("http://localhost:5173/invites#");
    }

    @Test
    void phoneInvitesGoBySms() {
        Owner owner = newBusiness("Phone Shop");
        ApiClient member = new ApiClient(port);
        Account account = TestAccounts.signUpWithPhone(member, messages);

        owner.client.post("/api/v1/staff/invites", Map.of("to", account.phone(), "role", "STAFF")).expectStatus(201);

        member.post("/api/v1/invites/accept", Map.of("token", tokenSentTo(account.e164()))).expectStatus(200);
    }

    @Test
    void existingMembersAndOwnersCannotBeInvited() {
        Owner owner = newBusiness("Full Shop");

        owner.client.post("/api/v1/staff/invites", Map.of("to", owner.account.email(), "role", "STAFF"))
                .expectError(409, "staff.already_member");
        owner.client.post("/api/v1/staff/invites", Map.of("to", TestAccounts.newEmail(), "role", "OWNER"))
                .expectError(400, "validation.failed");
        owner.client.post("/api/v1/staff/invites", Map.of("to", "not an address", "role", "STAFF"))
                .expectError(400, "validation.failed");
    }

    // Roles.

    @Test
    void staffCannotManageStaffButManagersCan() {
        Owner owner = newBusiness("Team Shop");
        Member staff = join(owner, "STAFF");
        Member manager = join(owner, "MANAGER");

        staff.client.get("/api/v1/staff").expectError(403, "access.denied");
        staff.client.post("/api/v1/staff/invites", Map.of("to", TestAccounts.newEmail(), "role", "STAFF"))
                .expectError(403, "access.denied");

        manager.client.post("/api/v1/staff/invites", Map.of("to", TestAccounts.newEmail(), "role", "MANAGER"))
                .expectStatus(201);
        manager.client.patch("/api/v1/staff/" + staff.userId, Map.of("role", "MANAGER")).expectStatus(204);

        staff.client.get("/api/v1/staff").expectStatus(200);
    }

    @Test
    void theOwnerIsProtectedAndNobodyChangesThemselves() {
        Owner owner = newBusiness("Protected Shop");
        Member manager = join(owner, "MANAGER");

        manager.client.patch("/api/v1/staff/" + owner.userId, Map.of("role", "STAFF"))
                .expectError(409, "staff.owner_protected");
        manager.client.delete("/api/v1/staff/" + owner.userId).expectError(409, "staff.owner_protected");
        manager.client.patch("/api/v1/staff/" + manager.userId, Map.of("role", "STAFF"))
                .expectError(409, "staff.self_change");
        owner.client.delete("/api/v1/staff/" + owner.userId).expectError(409, "staff.self_change");
        owner.client.patch("/api/v1/staff/" + manager.userId, Map.of("role", "OWNER"))
                .expectError(400, "validation.failed");
    }

    @Test
    void aRemovedMemberLosesAccessAtOnceAndCanBeInvitedBack() {
        Owner owner = newBusiness("Revolving Shop");
        Member staff = join(owner, "STAFF");

        owner.client.delete("/api/v1/staff/" + staff.userId).expectStatus(204);

        staff.client.get("/api/v1/business").expectError(409, "business.not_selected");
        assertThat(owner.client.get("/api/v1/staff").<List<Object>>json("$")).hasSize(1);

        owner.client.post("/api/v1/staff/invites", Map.of("to", staff.account.email(), "role", "MANAGER"))
                .expectStatus(201);
        staff.client.post("/api/v1/invites/accept", Map.of("token", tokenSentTo(staff.account.email())))
                .expectStatus(200);
        assertThat(staff.client.get("/api/v1/business").<String>json("$.role")).isEqualTo("MANAGER");
    }

    @Test
    void membersOfAnotherBusinessAreNotFound() {
        Owner mine = newBusiness("Mine");
        Owner theirs = newBusiness("Theirs");
        Member theirStaff = join(theirs, "STAFF");
        String theirInvite = theirs.client.post("/api/v1/staff/invites", Map.of("to", TestAccounts.newEmail(), "role", "STAFF"))
                .expectStatus(201).json("$.id");

        mine.client.patch("/api/v1/staff/" + theirStaff.userId, Map.of("role", "MANAGER"))
                .expectError(404, "resource.not_found");
        mine.client.delete("/api/v1/staff/" + theirStaff.userId).expectError(404, "resource.not_found");
        mine.client.delete("/api/v1/staff/invites/" + theirInvite).expectError(404, "resource.not_found");
    }

    // Ownership.

    @Test
    void theOwnerCanHandTheBusinessToAMember() {
        Owner owner = newBusiness("Handover Shop");
        Member manager = join(owner, "MANAGER");

        owner.client.post("/api/v1/staff/" + manager.userId + "/make-owner", Map.of("currentPassword", "wrong password"))
                .expectError(400, "account.wrong_password");
        owner.client.post("/api/v1/staff/" + manager.userId + "/make-owner",
                Map.of("currentPassword", owner.account.password())).expectStatus(204);

        assertThat(manager.client.get("/api/v1/business").<String>json("$.role")).isEqualTo("OWNER");
        assertThat(owner.client.get("/api/v1/business").<String>json("$.role")).isEqualTo("MANAGER");
        manager.client.delete("/api/v1/staff/" + owner.userId).expectStatus(204);
    }

    @Test
    void onlyTheOwnerHandsOverAndOnlyToAMember() {
        Owner owner = newBusiness("Guarded Shop");
        Member manager = join(owner, "MANAGER");
        Member staff = join(owner, "STAFF");

        manager.client.post("/api/v1/staff/" + staff.userId + "/make-owner",
                Map.of("currentPassword", manager.account.password())).expectError(403, "access.denied");
        owner.client.post("/api/v1/staff/" + UUID.randomUUID() + "/make-owner",
                Map.of("currentPassword", owner.account.password())).expectError(404, "resource.not_found");
    }

    // Helpers.

    private record Owner(ApiClient client, Account account, UUID userId, UUID businessId) {
    }

    private record Member(ApiClient client, Account account, UUID userId) {
    }

    private Owner newBusiness(String name) {
        ApiClient client = new ApiClient(port);
        Account account = TestAccounts.signUp(client, messages);
        UUID businessId = TestBusinesses.create(client, name);
        return new Owner(client, account, TestBusinesses.userId(client), businessId);
    }

    /** A new user who joins the owner's business through an invite. */
    private Member join(Owner owner, String role) {
        ApiClient client = new ApiClient(port);
        Account account = TestAccounts.signUp(client, messages);
        owner.client.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", role)).expectStatus(201);
        client.post("/api/v1/invites/accept", Map.of("token", tokenSentTo(account.email()))).expectStatus(200);
        return new Member(client, account, TestBusinesses.userId(client));
    }

    private void signUpWithEmail(ApiClient client, String email) {
        client.post("/api/v1/auth/otp", Map.of("to", email, "purpose", "SIGNUP")).expectStatus(202);
        client.post("/api/v1/auth/signup", Map.of("name", "New Staff", "phone", TestAccounts.newPhone(), "email", email,
                "password", TestAccounts.PASSWORD, "codeSentTo", "EMAIL",
                "code", messages.lastCode(email).orElseThrow())).expectStatus(201);
    }

    private String tokenSentTo(String destination) {
        List<CapturedMessages.Message> received = messages.to(destination);
        for (int i = received.size() - 1; i >= 0; i--) {
            Matcher link = INVITE_LINK.matcher(received.get(i).body());
            if (link.find()) {
                return link.group(1);
            }
        }
        throw new AssertionError("No invite sent to " + destination);
    }
}
