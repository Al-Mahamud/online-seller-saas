# Sign-in and sign-up redesign

Oct 31, 2026 · **Status: built, as described under "As built". Not yet tried on a real server or with sellers.** Add later changes to this document's change log.

Design: [Mojud sign-in and sign-up redesign](https://claude.ai/artifact/2gN8GtJ17CvMjXyWvZi93H) (screens for laptop and phone, plus every state of the User ID field).

## Decisions

Decided by Al Mahamud:

| Decision | Detail |
|---|---|
| New look | The current sign-in and sign-up pages are to be replaced by the design above. |
| User ID at sign-up | People choose their own. Each one is unique. A check button tells them whether it is free. |
| Sign-in | **User ID and password only.** No signing in with a phone number or email, and none with a code. |
| No NID | An NID field was tried in the design and removed. Don't collect it. |

Proposed by Claude and drawn or assumed in the design. Change any of these by saying so:

| Proposal | Detail |
|---|---|
| User ID rules | 4–20 characters: lowercase English letters, digits and `_`. Capitals become lowercase as they are typed, so `Rahim` and `rahim` can't both exist. |
| Forgot user ID | A new link on sign-in. Without it, someone who forgets their ID is locked out for good. |
| Forgot password | Starts from the User ID; the code goes to the phone or email on that account. |
| Phone and email at sign-up | Stay, and are still proved with a code. Recovery and invites depend on them. |
| Two-step sign-in | Stays, for accounts that turned it on. It comes after the password. |
| Continue without checking | "এগিয়ে যান" runs the same check and stops if the ID is taken. Pressing the check button first is not required. |

## Look

Keeps green `#0B6B4F`, ink `#17201B` and Hind Siliguri in its three weights, so there is no new font to download. Adds:

| What | Value |
|---|---|
| Dark panel and phone header | `#07392B` |
| Ledger margin line | `#E8A93A`, 2 px, full height |
| Ledger numbers | `#F0B94F` |
| Text on the dark panel | `#BFD6CB` |
| Ledger row lines | `#1F5848` |
| Outline of the language button on dark | `#2F6B59` |
| Available / wrong | `#0B6B4F` with a tick / `#B42318` with a cross, so colour is never the only signal |
| Fields and main buttons | 52 px tall, radius 10 px, border 1.5 px `#CBD0CB` |
| Phone form sheet | White, top corners 24 px |

## Screens

**Laptop.** Two columns that stack below 880 px. Left, the dark ledger panel: logo, headline and four numbered rows (stock, orders, delivery, COD). Right, the form and the language button.

**Phone.** Dark header with logo, language button, title and subtitle. The form sits on a white sheet below it.

**Sign in.** User ID with "forgot user ID" beside its label, password with "forgot password" beside its label and the show/hide eye, one "লগ ইন" button, then the line about the free trial and a link to sign up.

**Sign up, step 1.** Step bar, name, phone, User ID with the check button, email, password, the choice of where the code goes, "এগিয়ে যান", and a link to sign in. As now, the SMS/email choice appears only when the server offers both.

**Sign up, step 2.** Step bar, six boxes for the code, "অ্যাকাউন্ট খুলুন", the resend timer and "তথ্য বদলান".

**User ID field states.** Before checking, checking, available, already taken, not allowed. Typing again clears the result.

**Forgot user ID.** Phone or email, then the six-box code, then the ID shown large with "লগ ইন করুন" and "পাসওয়ার্ডও ভুলে গেছেন?". The code screen says the same thing whether or not an account exists.

**Forgot password.** User ID, with a link to "forgot user ID" for someone who has lost both. Then the code and the new password on one screen. It ends back on sign-in with a green notice.

**Two-step sign-in.** One text field, not six boxes, because a recovery code is longer and has a dash. A new "লগ ইনে ফিরে যান" link lets someone start again.

**Wrong user ID or password.** A red notice above the fields, with a cross. The User ID stays filled and the password clears. Neither field is marked, so the page doesn't reveal which was wrong.

**Drawn for phone only:** the code and result steps of forgot user ID, and the password-changed notice. On a laptop they use the same form in the right column.

## New text

Everything else on these screens is already in `frontend/src/i18n/bn.json`. The Bangla needs Al Mahamud's read-through; the English is a first draft for `en.json`.

| Where | বাংলা | English |
|---|---|---|
| Panel headline | ব্যবসার হিসাব এবার খাতা ছেড়ে ফোনে। | Your accounts, off the ledger and onto your phone. |
| Row 1 | কী আছে, কী কমে আসছে | What you have, what's running low |
| Row 2 | কোনটা প্যাক করতে হবে | What needs packing |
| Row 3 title | ডেলিভারি | Delivery |
| Row 3 | কোন পার্সেল কুরিয়ারে আছে | Which parcels are with couriers |
| Row 4 | কত টাকা পাওনা, কত পথে | How much is owed, how much is on the way |
| Step bar | ধাপ ১ / ২, ধাপ ২ / ২ | Step 1 of 2, Step 2 of 2 |
| Field | ইউজার আইডি | User ID |
| Link | ইউজার আইডি ভুলে গেছেন? | Forgot your user ID? |
| Button | যাচাই করুন | Check |
| Button, while checking | যাচাই হচ্ছে… | Checking… |
| Rules | ৪–২০ অক্ষর: ইংরেজি ছোট হাতের অক্ষর, সংখ্যা আর _ | 4–20 characters: lowercase English letters, numbers and _ |
| Available | এই ইউজার আইডিটি নেওয়া যাবে। | This user ID is available. |
| Taken | এই ইউজার আইডিটি আগেই নেওয়া হয়েছে। অন্যটি দিন। | This user ID is already taken. Try another. |
| Not allowed | ৪–২০ অক্ষর দিন: শুধু ইংরেজি ছোট হাতের অক্ষর, সংখ্যা আর _ | Use 4–20 characters: only lowercase English letters, numbers and _ |
| Forgot user ID, description | অ্যাকাউন্টের ফোন নম্বর বা ইমেইল দিন। আমরা একটি কোড পাঠাব। | Enter your account's phone number or email and we'll send you a code. |
| Result title | আপনার ইউজার আইডি | Your user ID |
| Result description | এটি দিয়ে লগ ইন করুন। ভবিষ্যতের জন্য লিখে রাখুন। | Sign in with this. Write it down for next time. |
| Result link | পাসওয়ার্ডও ভুলে গেছেন? | Forgot your password too? |
| Forgot password, description | আপনার ইউজার আইডি দিন। অ্যাকাউন্টের ফোন বা ইমেইলে আমরা একটি কোড পাঠাব। | Enter your user ID and we'll send a code to the account's phone or email. |
| New password, title | নতুন পাসওয়ার্ড | New password |
| New password, description | এই ইউজার আইডি থাকলে অ্যাকাউন্টের ফোন বা ইমেইলে একটি কোড পাঠানো হয়েছে। কোডটি ৫ মিনিট কাজ করবে। | If this user ID exists, we've sent a code to the account's phone or email. It works for 5 minutes. |
| Wrong details | ইউজার আইডি বা পাসওয়ার্ড সঠিক নয়। | The user ID or password is wrong. |

## As built

- **Order of the sign-up fields:** name, phone, User ID, email, password on both sizes, so the Tab key and the eye follow the same order. The phone mockup had the User ID second.
- **While checking:** the button says "যাচাই হচ্ছে…"; the line under the field keeps the rules until the answer comes. The mockup repeated the word there too.
- **Laptop's other screens:** forgot user ID's code and result, and the password-changed notice, use the same form as on phones.
- **Two-step sign-in's back link** starts the sign-in again.
- **Existing accounts:** the migration gives each one a generated ID such as `user_0123456789abcde`. They find it with "forgot user ID", or on the account page once signed in.

### Backend

- **Migration (V33 or next):** `users.user_id`, not null, unique, with a database check for the rules. Rows already in a database need a generated value first; there are no real sellers yet, so this only touches test and local data.
- **`POST /api/v1/auth/signup`:** takes `userId` and refuses a taken one (`account.user_id_taken`). The database constraint decides when two people choose the same ID at the same moment; the check button can't.
- **New, availability check:** answers whether a User ID is free, with a per-IP rate limit, since it reveals which IDs exist. Reserved words such as `admin`, `support` and `mojud` count as taken.
- **`POST /api/v1/auth/login`:** takes the User ID and password only. Remove the `code` route, `logInWithCode` and `OtpPurpose.LOGIN`. The login identifier parsing changes with it.
- **New, forgot user ID:** a code to a verified phone or email, then the ID is shown or sent. It must answer the same way whether or not an account exists, as codes do now.
- **`POST /api/v1/auth/password/reset`:** starts from the User ID. The code goes to that account's verified phone or email.
- **Messages:** `error.auth.invalid_credentials` in `messages.properties` and `messages_bn.properties` mentions phone, email and code; reword it to User ID and password. It stays one message for every wrong detail.
- **Unchanged:** invites still match on phone or email, and admin search still finds people by phone or email. Add `userId` to `/me`, the data-rights export and admin search.
- **Rate limits:** review `login-per-ip` and the per-account limit in `identity.rate-limits`, which are keyed on the login.
- **API description:** update the snapshot (`-DupdateSnapshots=true`) and run `npm run api:types`.

### Frontend

- **`features/auth/AuthLayout.tsx`:** the new frame. `SimpleFrame` is also used by other pages without tabs, so the new look goes in `AuthLayout` and `SimpleFrame` stays as it is.
- **`SignInPage.tsx`:** the new form. Remove `CodeForm`, `withCode` and `offersCodes`. `TwoStepForm` stays, restyled.
- **`SignUpPage.tsx`:** the User ID field and check button, the step bar, and the SMS/email cards.
- **`ForgotPasswordPage.tsx`:** starts from the User ID, in the new frame.
- **New forgot-user-ID page and route.**
- **Code boxes:** six boxes must still accept a pasted code and the phone's auto-fill, and read as one field to screen readers.
- **`i18n/bn.json` and `en.json`:** the new text above; remove keys nothing uses any more, then run `npm run texts:review`.
- **`InvitePage.tsx`:** sign-up and sign-in links keep working; invited people now choose a User ID too.

### Tests

- **Backend:** `AuthTests`, `AccountTests`, `TwoFactorTests`, `KeyRotationTests`, `BusinessTests`, `EmailFlowTests`, `TestAccounts` and `TestAdmins` all sign in by phone or email today. New cases: a taken ID, two sign-ups racing for one ID, each rule, reserved words, the check's rate limit, phone and email refused at sign-in, and forgot user ID not revealing accounts.
- **End-to-end:** `e2e/support/accounts.ts` signs up every test account, so it changes first; `auth.spec.ts` and `account.spec.ts` change most. The accessibility scan and both screen sizes apply to every new screen.
- **Also:** `first-visit.perf.ts` and the 250 KB size budget.
- **Docs:** update `frontend-plan.md`'s F1 note and mark this document as built.

## Open questions

Each has a default that the build will use unless changed.

1. **Can someone change their User ID later?** Default: no, not in the first release.
2. **Should the app's other places show the User ID?** Default: only on the account page.

## Change log

- **Oct 31, 2026:** first design: dark ledger look on laptop and phone.
- **Oct 31, 2026:** NID field added to sign-up, then removed.
- **Oct 31, 2026:** User ID with a check button added to sign-up, with a screen of its states.
- **Oct 31, 2026:** sign-in allowed the User ID alongside phone and email, then changed to User ID and password only. Code sign-in removed; "forgot user ID" link added.
- **Oct 31, 2026:** drew forgot user ID, forgot password, two-step sign-in and the wrong-details notice, and English sign-in and sign-up on laptop and phone. The English panel headline was shortened to fit.
- **Oct 31, 2026:** built in the backend and the web app.
