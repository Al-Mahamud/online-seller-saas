package com.mojud.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.db.system.SystemDatabase;
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

    @Autowired
    SystemDatabase systemDatabase;

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
    void aMemberLeavesAndTheirInvitesGoWithThem() {
        Owner owner = newBusiness("Leaving Shop");
        Member manager = join(owner, "MANAGER");
        String guest = TestAccounts.newEmail();
        manager.client.post("/api/v1/staff/invites", Map.of("to", guest, "role", "STAFF")).expectStatus(201);
        String token = tokenSentTo(guest);

        manager.client.post("/api/v1/staff/leave", Map.of()).expectStatus(204);

        manager.client.get("/api/v1/business").expectError(409, "business.not_selected");
        assertThat(owner.client.get("/api/v1/staff").<List<Object>>json("$")).hasSize(1);
        assertThat(owner.client.get("/api/v1/staff/invites").<List<Object>>json("$")).isEmpty();
        new ApiClient(port).post("/api/v1/public/invites/lookup", Map.of("token", token)).expectError(404, "invite.invalid");
        assertThat(owner.client.get("/api/v1/business/audit-log").<String>json("$.items[0].action")).isEqualTo("staff.left");
    }

    @Test
    void aWriteNamingABusinessTheMemberWasMovedFromDoesNothing() {
        Owner owner = newBusiness("Old Shop");
        Member member = join(owner, "MANAGER");
        TestBusinesses.create(member.client, "Own Shop");
        member.client.post("/api/v1/me/switch-business", Map.of("businessId", owner.businessId.toString())).expectStatus(200);
        ApiClient naming = member.client.withHeader("X-Business-Id", owner.businessId.toString());

        // While it is the selected one, naming it changes nothing.
        naming.patch("/api/v1/business", Map.of("name", "Old Shop 2")).expectStatus(200);

        owner.client.delete("/api/v1/staff/" + member.userId).expectStatus(204);

        // The session has moved to their own shop; the write meant for the old one is refused.
        naming.patch("/api/v1/business", Map.of("name", "Written By Mistake")).expectError(409, "business.changed");
        assertThat(member.client.get("/api/v1/business").<String>json("$.name")).isEqualTo("Own Shop");
    }

    @Test
    void theOwnerCannotLeave() {
        Owner owner = newBusiness("Kept Shop");

        owner.client.post("/api/v1/staff/leave", Map.of()).expectError(409, "staff.owner_protected");
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

    // Fixes from the 2b code review.

    @Test
    void aRemovedManagersInvitesStopWorking() {
        Owner owner = newBusiness("Locked Out Shop");
        Member manager = join(owner, "MANAGER");
        String secondAddress = TestAccounts.newEmail();
        manager.client.post("/api/v1/staff/invites", Map.of("to", secondAddress, "role", "MANAGER")).expectStatus(201);
        String token = tokenSentTo(secondAddress);

        owner.client.delete("/api/v1/staff/" + manager.userId).expectStatus(204);

        new ApiClient(port).post("/api/v1/public/invites/lookup", Map.of("token", token)).expectError(404, "invite.invalid");
        assertThat(owner.client.get("/api/v1/staff/invites").<List<Object>>json("$")).isEmpty();
    }

    @Test
    void aManagerDemotedToStaffLosesTheirInvites() {
        Owner owner = newBusiness("Demoting Shop");
        Member manager = join(owner, "MANAGER");
        String invited = TestAccounts.newEmail();
        manager.client.post("/api/v1/staff/invites", Map.of("to", invited, "role", "STAFF")).expectStatus(201);
        String token = tokenSentTo(invited);

        owner.client.patch("/api/v1/staff/" + manager.userId, Map.of("role", "STAFF")).expectStatus(204);

        new ApiClient(port).post("/api/v1/public/invites/lookup", Map.of("token", token)).expectError(404, "invite.invalid");
    }

    @Test
    void anInviteDiesWithItsSendersRightToInviteEvenIfNotWithdrawn() {
        Owner owner = newBusiness("Belt And Braces");
        Member manager = join(owner, "MANAGER");
        ApiClient invitee = new ApiClient(port);
        Account account = TestAccounts.signUp(invitee, messages);
        manager.client.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        // Change the sender's role behind the service's back: accepting must still check it.
        systemDatabase.jdbc().sql("update memberships set role = 'STAFF' where business_id = ? and user_id = ?")
                .params(owner.businessId, manager.userId).update();

        invitee.post("/api/v1/invites/accept", Map.of("token", tokenSentTo(account.email())))
                .expectError(404, "invite.invalid");
    }

    @Test
    void ownershipCannotBeHandedToSomeoneAtTheOwnerLimit() {
        Owner owner = newBusiness("Too Many Shop");
        Member manager = join(owner, "MANAGER");
        for (int i = 1; i <= 5; i++) {
            TestBusinesses.create(manager.client, "Manager's Shop " + i);
        }

        owner.client.post("/api/v1/staff/" + manager.userId + "/make-owner",
                Map.of("currentPassword", owner.account.password())).expectError(409, "staff.owner_limit");
    }

    @Test
    void aDoubleHandoverMakesOnlyOneNewOwner() throws Exception {
        Owner owner = newBusiness("Double Click Shop");
        Member first = join(owner, "MANAGER");
        Member second = join(owner, "MANAGER");

        List<ApiResponse> answers = inParallel(
                () -> owner.client.post("/api/v1/staff/" + first.userId + "/make-owner",
                        Map.of("currentPassword", owner.account.password())),
                () -> owner.client.post("/api/v1/staff/" + second.userId + "/make-owner",
                        Map.of("currentPassword", owner.account.password())));

        assertThat(answers).extracting(ApiResponse::status).containsExactlyInAnyOrder(204, 403);
        assertThat(systemDatabase.jdbc().sql("select count(*) from memberships where business_id = ? and role = 'OWNER'")
                .param(owner.businessId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void parallelInvitesToOneAddressLeaveOneOpenInvite() throws Exception {
        Owner owner = newBusiness("Busy Inviter");
        String email = TestAccounts.newEmail();

        List<ApiResponse> answers = inParallel(
                () -> owner.client.post("/api/v1/staff/invites", Map.of("to", email, "role", "STAFF")),
                () -> owner.client.post("/api/v1/staff/invites", Map.of("to", email, "role", "STAFF")),
                () -> owner.client.post("/api/v1/staff/invites", Map.of("to", email, "role", "MANAGER")));

        assertThat(answers).extracting(ApiResponse::status).containsOnly(201);
        assertThat(owner.client.get("/api/v1/staff/invites").<List<Object>>json("$")).hasSize(1);
    }

    @Test
    void acceptingTwoInvitesAtOnceJoinsOnce() throws Exception {
        Owner owner = newBusiness("Two Links Shop");
        ApiClient person = new ApiClient(port);
        Account account = TestAccounts.signUpWithPhone(person, messages);
        String email = TestAccounts.newEmail();
        person.post("/api/v1/me/contact/code", Map.of("to", email)).expectStatus(202);
        person.post("/api/v1/me/contact", Map.of("to", email, "code", messages.lastCode(email).orElseThrow(),
                "currentPassword", account.password())).expectStatus(200);
        owner.client.post("/api/v1/staff/invites", Map.of("to", account.phone(), "role", "STAFF")).expectStatus(201);
        owner.client.post("/api/v1/staff/invites", Map.of("to", email, "role", "STAFF")).expectStatus(201);
        String byPhone = tokenSentTo(account.e164());
        String byEmail = tokenSentTo(email);

        List<ApiResponse> answers = inParallel(
                () -> person.post("/api/v1/invites/accept", Map.of("token", byPhone)),
                () -> person.post("/api/v1/invites/accept", Map.of("token", byEmail)));

        assertThat(answers).extracting(ApiResponse::status).containsExactlyInAnyOrder(200, 409);
        assertThat(owner.client.get("/api/v1/staff").<List<Object>>json("$")).hasSize(2);
    }

    @Test
    void aMemberWhoRejoinsShowsTheirNewJoinDate() {
        Owner owner = newBusiness("Rejoin Shop");
        Member staff = join(owner, "STAFF");
        String firstJoined = joinedAt(owner, staff);
        owner.client.delete("/api/v1/staff/" + staff.userId).expectStatus(204);
        clock.advance(Duration.ofDays(30));

        owner.client.post("/api/v1/staff/invites", Map.of("to", staff.account.email(), "role", "STAFF")).expectStatus(201);
        staff.client.post("/api/v1/invites/accept", Map.of("token", tokenSentTo(staff.account.email()))).expectStatus(200);

        assertThat(java.time.Instant.parse(joinedAt(owner, staff)))
                .isAfter(java.time.Instant.parse(firstJoined).plus(Duration.ofDays(29)));
    }

    private String joinedAt(Owner owner, Member member) {
        List<String> dates = owner.client.get("/api/v1/staff").json("$[?(@.userId == '" + member.userId + "')].joinedAt");
        return dates.get(0);
    }

    @SafeVarargs
    private List<ApiResponse> inParallel(java.util.concurrent.Callable<ApiResponse>... calls) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(calls.length);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<ApiResponse>> futures = new java.util.ArrayList<>();
            for (java.util.concurrent.Callable<ApiResponse> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<ApiResponse> answers = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<ApiResponse> future : futures) {
                answers.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
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
        client.post("/api/v1/auth/signup", Map.of("userId", TestAccounts.newUserId(), "name", "New Staff", "phone", TestAccounts.newPhone(), "email", email,
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
