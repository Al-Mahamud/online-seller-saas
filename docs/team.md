# Team: adding members and who can do what

`/settings/staff` (needs the Team area). Design: https://claude.ai/artifact/NsY2W9ZexQqrKYfpVanu2F
(local copy in `mojud-design-artifacts/23-team`).

## Adding a member (no invite links)

`POST /api/v1/staff {name, phone, email?, userId, role}`:

- **New person:** Mojud creates their account (`identity.TeamAccounts`) with the User ID the business
  typed and a random starting password like `k7Qm-29xa`. It works once, for 7 days, and is shown
  once in the answer (copy or WhatsApp) and sent by SMS, else by email, if a sender is set up
  (`sentBy`). Nothing on the account is verified: the person proves their phone or email later in
  their account settings, like anyone else, and "forgot password" works only after that.
  `users.created_for_business` remembers which business made it (V38).
- **Already on Mojud** (a verified phone or email matches): that account joins the business and is
  told; no new password, and its User ID isn't shown to the business.
- **First sign-in:** with the starting password, the session may only read who they are and the
  business's name, sign out, and `POST /api/v1/me/first-password` (`identity.auth.PasswordGate`,
  `auth.password_change_required` for anything else). The page is `/set-password`. An expired
  starting password answers `auth.starting_password_expired`.
- **New password:** `POST /api/v1/staff/{userId}/new-password`, only for an account this business
  made that works in no other business (`staff.password_not_yours`); signs them out everywhere.
- Seats count as before; Free shows "only you".

The old invite endpoints and `/invites` page still work for links already sent, but the app no longer
makes invites.

## Who can do what (`Permission`)

Areas: ORDERS, STOCK, CUSTOMERS, COSTS, PRODUCTS, SETTINGS, TEAM. The owner may do everything; what
staff and managers may do is stored per business (`business_settings.staff_permissions`,
`manager_permissions`, V38), defaulting to the rules from before. Read on every request
(`TenantFilter` → `CurrentBusiness.can`); endpoints carry `@RequiresPermission` on top of
`@RequiresRole(STAFF)`. `GET /api/v1/business/permissions` (anyone), `PUT` (owner only). The
business answer lists the member's own areas; the app hides menus, pages and buttons by them.

Fixed, not editable: exports and settling COD stay manager-only; deleting a customer needs manager
and Customers; the audit log, ownership, deleting the business, the search colour and paying for a
plan stay owner-only. Finding a customer while taking an order needs Orders or Customers.

**Never above your own role:** whoever may manage the team adds, changes, removes or resets only
roles up to their own (`staff.role_above_yours`), so staff given Team can manage staff only.
