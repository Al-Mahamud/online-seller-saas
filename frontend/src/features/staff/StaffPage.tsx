import { zodResolver } from "@hookform/resolvers/zod";
import { queryOptions, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Check, Crown, Lock, Pencil, Plus, UserPlus } from "lucide-react";
import { lazy, type ReactNode, Suspense, useEffect, useId, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod/mini";
import {
  type Business,
  businessQuery,
  can,
  hasRole,
  initials,
  type Permission,
  type Role,
  useBusiness,
} from "@/api/business";
import { api, call } from "@/api/client";
import { errorText } from "@/api/query";
import type { components } from "@/api/schema";
import { ConfirmDialog } from "@/components/form/ConfirmDialog";
import { FormError, PasswordField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { BackToMore } from "@/components/layout/PageHeader";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { currentPassword, name as nameRule, newUserId, phone as phoneRule } from "@/features/auth/schemas";
import { billingQuery, usePlanName } from "@/features/billing/api";
import { MENU_TRIGGER, type MenuAction } from "@/features/catalog/categoryMenuParts";
import { useLooseT } from "@/i18n/pages";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";

// The menu library loads on the first tap of a ⋯ button.
const RowMenu = lazy(() => import("@/features/catalog/CategoryMenu"));

type Member = components["schemas"]["StaffMember"];
type Added = components["schemas"]["TeamMembersAdded"];
type SentBy = NonNullable<Added["sentBy"]>;
type Rules = components["schemas"]["RolePermissionsRules"];

const membersQuery = queryOptions({ queryKey: ["staff"], queryFn: () => call(api.GET("/api/v1/staff")) });
const rulesQuery = queryOptions({
  queryKey: ["business-permissions"],
  queryFn: () => call(api.GET("/api/v1/business/permissions")),
});

const AREAS: Permission[] = ["ORDERS", "STOCK", "CUSTOMERS", "COSTS", "PRODUCTS", "SETTINGS", "TEAM"];
const DEFAULT_RULES: Rules = {
  staff: ["ORDERS", "STOCK", "CUSTOMERS"],
  manager: [...AREAS],
};
const ASSIGNABLE = ["STAFF", "MANAGER"] as const;
const RANK: Record<Role, number> = { STAFF: 1, MANAGER: 2, OWNER: 3 };
const CARD = "flex flex-col gap-3 rounded-2xl border bg-card p-3.5 md:p-4.5";
const SOLID =
  "flex h-12 cursor-pointer items-center justify-center gap-2 rounded-xl bg-primary px-4 text-base font-bold text-primary-foreground hover:bg-[#0a5c44] disabled:cursor-default disabled:bg-[#9AA19C]";
const OUTLINE =
  "flex h-11 cursor-pointer items-center justify-center rounded-xl border-[1.5px] border-input bg-card px-4 text-[15px] font-bold hover:bg-muted";

/** The owner manages anyone; others only roles up to their own (the server's rule too). */
const mayManage = (business: Business, role: Role) => business.role === "OWNER" || RANK[business.role] >= RANK[role];

/**
 * The team: who is in it and what each role may do, and adding someone by creating their account
 * (a User ID and a starting password, changed at the first sign-in).
 */
export function StaffPage() {
  const t = useLooseT();
  const format = useFormat();
  const business = useBusiness();
  const planName = usePlanName();
  const { data: members } = useQuery(membersQuery);
  const { data: billing } = useQuery({ ...billingQuery, enabled: can(business, "SETTINGS") });
  const seats = billing?.usage.staff_seats;
  const notYet = members?.filter((member) => member.awaitingFirstSignIn).length ?? 0;
  const people = (count: number) => t("team.people", { count: format.number(count) });
  return (
    <div className="flex flex-col gap-4">
      <header className="relative flex flex-col gap-3.5 bg-ledger pt-1 pr-4.5 pb-10.5 pl-6.5 text-white max-md:-mx-4 max-md:-mt-4 md:overflow-hidden md:rounded-2xl md:pt-4.5 md:pr-7 md:pb-5 md:pl-14">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:left-7" />
        <div className="[&_a]:text-marigold-text">
          <BackToMore />
          <h1 className="text-[28px] leading-tight font-bold md:text-[32px]">{t("staff.title")}</h1>
          <p className="text-[15px] text-ledger-text">{t("team.subtitle")}</p>
        </div>
        <div className="flex flex-wrap items-end gap-x-7 gap-y-3.5 border-t border-ledger-outline pt-3">
          <dl className="flex gap-6">
            <div>
              <dt className="text-sm font-semibold text-ledger-text">{t("team.figMembers")}</dt>
              <dd className="text-2xl font-bold text-marigold-text">{members ? people(members.length) : "–"}</dd>
            </div>
            <div>
              <dt className="text-sm font-semibold text-ledger-text">{t("team.figNew")}</dt>
              <dd className="text-2xl font-bold">{members ? people(notYet) : "–"}</dd>
            </div>
          </dl>
          {billing && seats && (
            <div className="flex min-w-0 flex-[1_1_240px] flex-col gap-1.5">
              <div className="flex justify-between gap-2 text-sm">
                <span className="font-semibold text-ledger-text">
                  {t("team.seats", { plan: planName(billing.plan) })}
                </span>
                <strong>
                  {seats.limit === null
                    ? t("team.seatsAny", { used: format.number(seats.used) })
                    : t("team.seatsOf", { used: format.number(seats.used), limit: format.number(seats.limit) })}
                </strong>
              </div>
              {seats.limit !== null && seats.limit > 0 && (
                <div aria-hidden="true" className="h-2 rounded bg-white/15">
                  <div
                    className="h-2 rounded bg-marigold"
                    style={{ width: `${Math.min(100, (seats.used / seats.limit) * 100)}%` }}
                  />
                </div>
              )}
              <span className="text-[13px] text-ledger-text">{t("team.seatsNote")}</span>
            </div>
          )}
        </div>
      </header>
      <div className="grid items-start gap-4.5 max-md:relative max-md:-mx-4 max-md:-mt-11.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3 max-md:pt-3.5 md:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <div className="flex min-w-0 flex-col gap-4">
          <Members members={members} />
          <Matrix />
        </div>
        <div className="flex min-w-0 flex-col gap-4 max-md:order-first">
          {seats?.limit === 0 ? (
            <FreePlan />
          ) : (
            <AddMember full={!!seats && seats.limit !== null && seats.used >= seats.limit} limit={seats?.limit ?? 0} />
          )}
        </div>
      </div>
    </div>
  );
}

function Members({ members }: { members?: Member[] }) {
  const t = useLooseT();
  return (
    <section aria-labelledby="members-title" className={CARD}>
      <h2 id="members-title" className="text-xl font-bold">
        {t("team.members")}
      </h2>
      <ul>
        {members?.map((member, index) => (
          <MemberRow key={member.userId} member={member} first={index === 0} />
        ))}
      </ul>
    </section>
  );
}

const AVATAR: Record<Role, string> = {
  OWNER: "bg-marigold-text text-ledger",
  MANAGER: "bg-[#D9E6F7] text-[#24427A]",
  STAFF: "bg-accent text-accent-foreground",
};

function MemberRow({ member, first }: { member: Member; first: boolean }) {
  const t = useLooseT();
  const format = useFormat();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<"remove" | "transfer" | "password">();
  const refresh = () => queryClient.invalidateQueries(membersQuery);
  const changeRole = useMutation({
    mutationFn: (role: Role) =>
      call(api.PATCH("/api/v1/staff/{userId}", { params: { path: { userId: member.userId } }, body: { role } })),
    onSettled: refresh,
  });
  const remove = useMutation({
    mutationFn: () => call(api.DELETE("/api/v1/staff/{userId}", { params: { path: { userId: member.userId } } })),
    onSuccess: () => {
      setDialog(undefined);
      toast.success(t("staff.removed", { name: member.name }));
    },
    onSettled: refresh,
  });
  const editable = !member.you && member.role !== "OWNER" && mayManage(business, member.role);
  const contact = member.email ?? format.phone(member.phone);
  const actions: MenuAction[] = [
    ...(member.canSendPassword ? [{ label: t("team.sendPassword"), onSelect: () => setDialog("password") }] : []),
    ...(hasRole(business, "OWNER") ? [{ label: t("team.makeOwner"), onSelect: () => setDialog("transfer") }] : []),
    { label: t("team.remove"), danger: true, onSelect: () => setDialog("remove") },
  ];
  return (
    <li className={cn("flex flex-wrap items-center gap-x-3 gap-y-2.5 py-3", !first && "border-t")}>
      <span
        aria-hidden="true"
        className={cn("flex size-11 flex-none items-center justify-center rounded-full font-bold", AVATAR[member.role])}
      >
        {initials(member.name)}
      </span>
      <div className="min-w-0 flex-[1_1_180px]">
        <p className="flex flex-wrap items-center gap-x-2 gap-y-1 font-bold">
          {member.name}
          {member.you && <span className="font-normal text-muted-foreground">{t("team.you")}</span>}
          {member.awaitingFirstSignIn && (
            <span className="rounded-full bg-[#FFF4DE] px-2.25 text-[13px] font-bold text-[#6B4A10]">
              {t("team.notYet")}
            </span>
          )}
        </p>
        <p className="text-sm break-words text-muted-foreground">
          {t("team.line", {
            userId: member.loginId,
            contact,
            joined: t("team.joined", { date: format.dayMonth(member.joinedAt) }),
          })}
        </p>
      </div>
      {member.role === "OWNER" && (
        <span className="flex items-center gap-1.5 rounded-full bg-[#FFF4DE] px-3 py-1 text-sm font-bold text-[#6B4A10]">
          <Crown aria-hidden="true" className="size-3.75" />
          {t("team.owner")}
        </span>
      )}
      {editable && (
        <div className="flex flex-none items-center gap-1.5">
          <div
            role="radiogroup"
            aria-label={t("team.roleOf", { name: member.name })}
            className="flex rounded-[10px] bg-muted p-0.75"
          >
            {ASSIGNABLE.map((role) => {
              const on = member.role === role;
              return (
                // biome-ignore lint/a11y/useSemanticElements: two buttons acting as a switch, saved on tap.
                <button
                  key={role}
                  type="button"
                  role="radio"
                  aria-checked={on}
                  disabled={changeRole.isPending || !mayManage(business, role)}
                  className={cn(
                    "h-9.5 cursor-pointer rounded-lg px-3 text-sm font-bold disabled:cursor-default disabled:opacity-60",
                    on ? "bg-card text-primary shadow-[0_1px_4px_rgba(7,26,20,0.15)]" : "text-[#3E4842]",
                  )}
                  onClick={() => !on && changeRole.mutate(role)}
                >
                  {t(`business.role.${role}`)}
                </button>
              );
            })}
          </div>
          <Menu label={t("team.more", { name: member.name })} actions={actions} />
        </div>
      )}
      {!editable && member.role !== "OWNER" && (
        <span className="rounded-full bg-accent px-2.5 text-sm font-semibold text-accent-foreground">
          {t(`business.role.${member.role}`)}
        </span>
      )}
      {dialog === "remove" && (
        <ConfirmDialog
          title={t("staff.removeTitle", { name: member.name })}
          description={t("staff.removeDescription")}
          problem={remove.error ? errorText(remove.error) : undefined}
          confirm={t("staff.remove")}
          pending={remove.isPending}
          onConfirm={() => remove.mutate()}
          onClose={() => setDialog(undefined)}
        />
      )}
      {dialog === "transfer" && <TransferDialog member={member} onClose={() => setDialog(undefined)} />}
      {dialog === "password" && <NewPasswordDialog member={member} onClose={() => setDialog(undefined)} />}
    </li>
  );
}

/** The ⋯ button; the menu itself loads on the first tap, and opens. */
function Menu({ label, actions }: { label: string; actions: MenuAction[] }) {
  const [wanted, setWanted] = useState(false);
  const button = (
    <button
      type="button"
      aria-label={label}
      aria-haspopup="menu"
      className={MENU_TRIGGER}
      onClick={() => setWanted(true)}
    >
      <span aria-hidden="true" className="text-lg leading-none font-bold">
        ⋯
      </span>
    </button>
  );
  if (!wanted) {
    return button;
  }
  return (
    <Suspense fallback={button}>
      <RowMenu label={label} actions={actions} />
    </Suspense>
  );
}

/** A login, shown once: to copy, or to send on WhatsApp. */
function Credentials({
  loginId,
  password,
  sentBy,
  phone,
}: {
  loginId: string;
  password: string;
  sentBy: SentBy | null | undefined;
  phone: string;
}) {
  const t = useLooseT();
  const format = useFormat();
  const [copied, setCopied] = useState(false);
  const text = t("team.shareText", { link: `${window.location.origin}/sign-in`, userId: loginId, password });
  return (
    <>
      <dl className="flex flex-col gap-2 rounded-[14px] bg-ledger p-3.5 text-white">
        <div>
          <dt className="text-[13px] font-semibold text-ledger-text">{t("team.loginId")}</dt>
          <dd className="text-[22px] font-bold tracking-wide break-all">{loginId}</dd>
        </div>
        <div>
          <dt className="text-[13px] font-semibold text-ledger-text">{t("team.startingPassword")}</dt>
          <dd className="font-mono text-[22px] font-bold tracking-widest text-marigold-text">{password}</dd>
        </div>
      </dl>
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          className={cn(OUTLINE, "flex-[1_1_auto] border-primary whitespace-nowrap text-primary")}
          onClick={() => void navigator.clipboard?.writeText(text).then(() => setCopied(true))}
        >
          {copied ? t("team.copied") : t("team.copy")}
        </button>
        <a
          href={`https://wa.me/?text=${encodeURIComponent(text)}`}
          target="_blank"
          rel="noopener noreferrer"
          className={cn(
            SOLID,
            "h-11 flex-[1_1_auto] text-[15px] whitespace-nowrap no-underline hover:text-primary-foreground",
          )}
        >
          {t("team.whatsapp")}
        </a>
      </div>
      <p role="status" className="flex items-start gap-1.5 text-sm font-semibold text-primary">
        {sentBy && <Check aria-hidden="true" className="mt-0.5 size-4 flex-none" strokeWidth={2.6} />}
        {sentBy === "SMS"
          ? t("team.sentSMS", { phone: format.phone(phone) })
          : sentBy === "EMAIL"
            ? t("team.sentEMAIL")
            : t("team.sentNone")}
      </p>
      <div className="rounded-xl bg-[#FFF4DE] px-3 py-2.5 text-sm leading-relaxed text-[#6B4A10]">
        <strong>{t("team.onceOnly")}</strong> {t("team.onceOnlyHint")}
      </div>
    </>
  );
}

const addSchema = z.object({
  name: nameRule,
  phone: phoneRule,
  email: z.string().check(z.trim()),
  userId: newUserId,
  role: z.enum(ASSIGNABLE),
});

function AddMember({ full, limit }: { full: boolean; limit: number }) {
  const t = useLooseT();
  const format = useFormat();
  const id = useId();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const [added, setAdded] = useState<{ result: Added; phone: string }>();
  const form = useForm({
    resolver: zodResolver(addSchema),
    defaultValues: { name: "", phone: "", email: "", userId: "", role: "STAFF" as "STAFF" | "MANAGER" },
  });
  const role = useWatch({ control: form.control, name: "role" });
  const typedId = useWatch({ control: form.control, name: "userId" }).trim().toLowerCase();
  const [availability, setAvailability] = useState<{ userId: string; available: boolean }>();
  useEffect(() => {
    if (!/^[a-z0-9_]{4,20}$/.test(typedId)) {
      return;
    }
    const timer = window.setTimeout(() => {
      void call(api.GET("/api/v1/auth/user-id/availability", { params: { query: { userId: typedId } } }))
        .then(({ available }) => setAvailability({ userId: typedId, available }))
        .catch(() => undefined);
    }, 400);
    return () => window.clearTimeout(timer);
  }, [typedId]);
  const known = availability?.userId === typedId ? availability : undefined;
  const add = useMutation({
    mutationFn: (values: z.output<typeof addSchema>) =>
      call(api.POST("/api/v1/staff", { body: { ...values, email: values.email || undefined } })),
    meta: { showsOwnErrors: true },
    onSuccess: (result, values) => {
      setAdded({ result, phone: values.phone });
      form.reset();
      void queryClient.invalidateQueries(membersQuery);
      void queryClient.invalidateQueries(billingQuery);
    },
    onError: (error) => showServerError(form, error),
  });

  if (added) {
    const { result } = added;
    return (
      <section aria-labelledby={`${id}-done`} className={cn(CARD, "border-[1.5px] border-primary")}>
        <div className="flex items-center gap-2.5">
          <span
            aria-hidden="true"
            className="flex size-10 flex-none items-center justify-center rounded-full bg-accent"
          >
            <Check className="size-5 text-primary" strokeWidth={2.6} />
          </span>
          <h2 id={`${id}-done`} className="text-[19px] font-bold">
            {t("team.added", { name: result.member.name })}
          </h2>
        </div>
        {result.startingPassword ? (
          <Credentials
            loginId={result.member.loginId}
            password={result.startingPassword}
            sentBy={result.sentBy}
            phone={added.phone}
          />
        ) : (
          <>
            <p className="text-[15px] text-[#3E4842]">{t("team.existing")}</p>
            <p role="status" className="text-sm font-semibold text-primary">
              {result.sentBy === "SMS"
                ? t("team.toldSMS")
                : result.sentBy === "EMAIL"
                  ? t("team.toldEMAIL")
                  : t("team.toldNone")}
            </p>
            <p className="text-[13px] text-muted-foreground">{t("team.existingNote")}</p>
          </>
        )}
        <button type="button" className={OUTLINE} onClick={() => setAdded(undefined)}>
          {t("team.addAnother")}
        </button>
      </section>
    );
  }
  return (
    <section aria-labelledby={`${id}-title`} className={cn(CARD, "border-[1.5px] border-mint-line")}>
      <div>
        <h2 id={`${id}-title`} className="text-xl font-bold">
          {t("team.addTitle")}
        </h2>
        <p className="text-sm text-muted-foreground">{t("team.addHint")}</p>
      </div>
      {full && (
        <div role="status" className="flex flex-col gap-1.5 rounded-xl bg-[#FFF4DE] px-3.5 py-3 text-[#6B4A10]">
          <p className="font-bold">{t("team.fullTitle", { count: format.number(limit) })}</p>
          <p className="text-sm">{t("team.fullHint")}</p>
          {can(business, "SETTINGS") && (
            <Link to="/billing" className="flex min-h-10 w-fit items-center text-sm font-bold">
              {t("team.seePro")}
            </Link>
          )}
        </div>
      )}
      <form noValidate className="flex flex-col gap-3" onSubmit={form.handleSubmit((values) => add.mutate(values))}>
        <fieldset disabled={full} className="flex flex-col gap-3">
          <FormError form={form} strong />
          <TextField form={form} name="name" label={t("team.name")} autoComplete="off" />
          <div className="grid gap-3 md:grid-cols-2">
            <TextField form={form} name="phone" label={t("team.phone")} type="tel" placeholder="01XXXXXXXXX" />
            <TextField form={form} name="email" label={t("team.email")} type="email" autoComplete="off" />
          </div>
          <TextField
            form={form}
            name="userId"
            label={t("team.userId")}
            autoCapitalize="none"
            spellCheck={false}
            autoComplete="off"
            className="font-bold tracking-wide"
            hint={
              <>
                {known && (
                  <span className={cn("block font-semibold", known.available ? "text-primary" : "text-destructive")}>
                    {t(known.available ? "team.userIdFree" : "team.userIdTaken", { userId: typedId })}
                  </span>
                )}
                {t("team.userIdRules")}
              </>
            }
          />
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1.5 text-[15px] font-semibold">{t("team.as")}</legend>
            {ASSIGNABLE.map((option) => {
              const on = role === option;
              const allowed = mayManage(business, option);
              return (
                <label
                  key={option}
                  className={cn(
                    "flex items-start gap-2.5 rounded-xl border-[1.5px] px-3 py-2.5 has-focus-visible:ring-3 has-focus-visible:ring-ring/50",
                    on ? "border-primary bg-[#F3F9F6]" : "border-input bg-card",
                    allowed ? "cursor-pointer" : "cursor-default opacity-60",
                  )}
                >
                  <input
                    type="radio"
                    value={option}
                    disabled={!allowed}
                    className="mt-0.5 size-5 flex-none accent-primary"
                    {...form.register("role")}
                  />
                  <span>
                    <span className="block font-bold">{t(`business.role.${option}`)}</span>
                    <span className="block text-[13px] text-[#3E4842]">{t(`team.${option}Hint`)}</span>
                  </span>
                </label>
              );
            })}
          </fieldset>
          <button type="submit" disabled={add.isPending || full} className={SOLID}>
            <Plus aria-hidden="true" className="size-4.5" strokeWidth={2.4} />
            {t("team.add")}
          </button>
        </fieldset>
      </form>
    </section>
  );
}

function FreePlan() {
  const t = useLooseT();
  return (
    <section
      aria-labelledby="free-title"
      className="flex flex-col gap-3 rounded-2xl border-[1.5px] border-[#E8D3A6] bg-[#FFF9EE] p-4.5"
    >
      <span
        aria-hidden="true"
        className="flex size-12 items-center justify-center rounded-[14px] bg-marigold text-ledger"
      >
        <UserPlus className="size-6" />
      </span>
      <h2 id="free-title" className="text-[19px] font-bold">
        {t("team.freeTitle")}
      </h2>
      <p className="text-[15px] text-[#3E4842]">{t("team.freeHint")}</p>
      <Link to="/billing" className={cn(SOLID, "h-11.5 w-fit text-[15px] no-underline hover:text-primary-foreground")}>
        {t("team.seePlans")}
      </Link>
    </section>
  );
}

function NewPasswordDialog({ member, onClose }: { member: Member; onClose: () => void }) {
  const t = useLooseT();
  const queryClient = useQueryClient();
  const send = useMutation({
    mutationFn: () =>
      call(api.POST("/api/v1/staff/{userId}/new-password", { params: { path: { userId: member.userId } } })),
    meta: { showsOwnErrors: true },
    onSuccess: () => void queryClient.invalidateQueries(membersQuery),
  });
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-md">
        {send.data ? (
          <>
            <DialogHeader>
              <DialogTitle>{t("team.passwordSent", { name: member.name })}</DialogTitle>
              <DialogDescription className="sr-only">{t("team.startingPassword")}</DialogDescription>
            </DialogHeader>
            <Credentials
              loginId={member.loginId}
              password={send.data.startingPassword}
              sentBy={send.data.sentBy}
              phone={member.phone}
            />
            <button type="button" className={OUTLINE} onClick={onClose}>
              {t("team.close")}
            </button>
          </>
        ) : (
          <>
            <DialogHeader>
              <DialogTitle>{t("team.passwordTitle", { name: member.name })}</DialogTitle>
              <DialogDescription asChild>
                <ul className="list-disc pl-4.5 text-sm leading-relaxed text-[#3E4842]">
                  {t("team.passwordPoints")
                    .split("|")
                    .map((point) => (
                      <li key={point}>{point}</li>
                    ))}
                </ul>
              </DialogDescription>
            </DialogHeader>
            {send.error && (
              <p role="alert" className="rounded-[10px] bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
                {errorText(send.error)}
              </p>
            )}
            <div className="flex justify-end gap-2">
              <button type="button" className={OUTLINE} onClick={onClose}>
                {t("team.cancel")}
              </button>
              <button
                type="button"
                disabled={send.isPending}
                className={cn(SOLID, "h-11 text-[15px]")}
                onClick={() => send.mutate()}
              >
                {t("team.sendPassword")}
              </button>
            </div>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

const transferSchema = z.object({ currentPassword });

function TransferDialog({ member, onClose }: { member: Member; onClose: () => void }) {
  const t = useLooseT();
  const queryClient = useQueryClient();
  const form = useForm({ resolver: zodResolver(transferSchema), defaultValues: { currentPassword: "" } });
  const transfer = useMutation({
    mutationFn: (body: { currentPassword: string }) =>
      call(api.POST("/api/v1/staff/{userId}/make-owner", { params: { path: { userId: member.userId } }, body })),
    meta: { showsOwnErrors: true },
    onSuccess: () => {
      toast.success(t("staff.transferred", { name: member.name }));
      void queryClient.invalidateQueries(businessQuery);
      void queryClient.invalidateQueries(membersQuery);
      onClose();
    },
    onError: (error) => showServerError(form, error, { codes: { "account.wrong_password": "currentPassword" } }),
  });
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("staff.transferTitle", { name: member.name })}</DialogTitle>
          <DialogDescription>{t("staff.transferDescription")}</DialogDescription>
        </DialogHeader>
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={form.handleSubmit((values) => transfer.mutate(values))}
        >
          <FormError form={form} />
          <PasswordField
            form={form}
            name="currentPassword"
            label={t("account.password.current")}
            autoComplete="current-password"
          />
          <Button type="submit" variant="destructive" disabled={transfer.isPending}>
            {t("staff.makeOwner")}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** What staff and managers may do; the owner ticks and unticks, and saves. */
function Matrix() {
  const t = useLooseT();
  const format = useFormat();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const { data: saved } = useQuery(rulesQuery);
  const [draft, setDraft] = useState<Rules>();
  const isOwner = hasRole(business, "OWNER");
  const rules = draft ?? saved ?? DEFAULT_RULES;
  const changes =
    draft && saved
      ? AREAS.filter(
          (area) =>
            draft.staff.includes(area) !== saved.staff.includes(area) ||
            draft.manager.includes(area) !== saved.manager.includes(area),
        ).length
      : 0;
  const save = useMutation({
    mutationFn: (body: Rules) => call(api.PUT("/api/v1/business/permissions", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: (updated) => {
      queryClient.setQueryData(rulesQuery.queryKey, updated);
      setDraft(undefined);
      toast.success(t("team.saved"));
    },
  });
  const toggle = (who: "staff" | "manager", area: Permission) =>
    setDraft((current) => {
      const base = current ?? saved ?? DEFAULT_RULES;
      const list = base[who];
      return { ...base, [who]: list.includes(area) ? list.filter((item) => item !== area) : [...list, area] };
    });
  const editing = !!draft;
  const mark = (on: boolean, label: string): ReactNode => (
    <span
      role="img"
      aria-label={label}
      className={cn(
        "inline-flex size-6 items-center justify-center rounded-full font-bold",
        on ? "bg-accent text-primary" : "bg-muted text-[#8B938D]",
      )}
    >
      {on ? "✓" : "–"}
    </span>
  );
  const cell = (who: "staff" | "manager", area: Permission) => {
    const on = rules[who].includes(area);
    const label = t("team.cell", { who: t(`team.${who}`), task: t(`team.area${area}`) });
    return editing ? (
      <input
        type="checkbox"
        aria-label={label}
        checked={on}
        className="size-5.5 cursor-pointer accent-primary"
        onChange={() => toggle(who, area)}
      />
    ) : (
      mark(on, `${label}: ${t(on ? "team.can" : "team.cannot")}`)
    );
  };
  return (
    <section aria-labelledby="matrix-title" className={cn(CARD, editing && "border-[1.5px] border-primary")}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 id="matrix-title" className="text-lg font-bold">
            {t("team.matrix")}
          </h2>
          <p className="text-[13px] text-muted-foreground">
            {editing ? t("team.matrixEditing") : t("team.matrixHint")}
          </p>
        </div>
        {isOwner && !editing && saved && (
          <button
            type="button"
            className="flex h-10 cursor-pointer items-center gap-1.5 rounded-[10px] border-[1.5px] border-primary bg-card px-3.5 text-sm font-bold text-primary hover:bg-accent"
            onClick={() => setDraft(saved)}
          >
            <Pencil aria-hidden="true" className="size-3.75" />
            {t("team.edit")}
          </button>
        )}
      </div>
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr>
            <th scope="col" className="px-1 py-1.5 text-left font-semibold text-muted-foreground">
              {t("team.task")}
            </th>
            <th scope="col" className="w-14 px-0.5 py-1.5 font-bold">
              {t("team.staff")}
            </th>
            <th scope="col" className="w-17 px-0.5 py-1.5 font-bold">
              {t("team.manager")}
            </th>
            <th scope="col" className="w-13 px-0.5 py-1.5 font-bold">
              {t("team.ownerCol")}
            </th>
          </tr>
        </thead>
        <tbody>
          {AREAS.map((area) => {
            const moved =
              editing &&
              saved &&
              (rules.staff.includes(area) !== saved.staff.includes(area) ||
                rules.manager.includes(area) !== saved.manager.includes(area));
            return (
              <tr key={area} className={cn("border-t", moved && "bg-[#FFF9EE]")}>
                <th scope="row" className="px-1 py-2 text-left font-normal">
                  {t(`team.area${area}`)}
                </th>
                <td className="text-center">{cell("staff", area)}</td>
                <td className="text-center">{cell("manager", area)}</td>
                <td className="text-center">{mark(true, `${t("team.ownerCol")}: ${t("team.can")}`)}</td>
              </tr>
            );
          })}
          <tr className="border-t">
            <th scope="row" className="px-1 py-2 text-left font-normal">
              {t("team.areaOwner")}
              {editing && (
                <span className="flex items-center gap-1 text-xs text-muted-foreground">
                  <Lock aria-hidden="true" className="size-3" />
                  {t("team.ownerOnlyLock")}
                </span>
              )}
            </th>
            <td className="text-center">{mark(false, `${t("team.staff")}: ${t("team.cannot")}`)}</td>
            <td className="text-center">{mark(false, `${t("team.manager")}: ${t("team.cannot")}`)}</td>
            <td className="text-center">{mark(true, `${t("team.ownerCol")}: ${t("team.can")}`)}</td>
          </tr>
        </tbody>
      </table>
      {editing && (
        <>
          <p className="rounded-xl bg-[#FFF4DE] px-3 py-2.5 text-[13px] leading-relaxed text-[#6B4A10]">
            {t("team.matrixNote")}
          </p>
          {save.error && (
            <p role="alert" className="rounded-[10px] bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
              {errorText(save.error)}
            </p>
          )}
          <div className="flex flex-wrap justify-between gap-2">
            <button
              type="button"
              className="min-h-10.5 cursor-pointer px-1 text-sm font-bold text-primary underline"
              onClick={() => setDraft(DEFAULT_RULES)}
            >
              {t("team.reset")}
            </button>
            <div className="flex gap-2">
              <button type="button" className={OUTLINE} onClick={() => setDraft(undefined)}>
                {t("team.cancel")}
              </button>
              <button
                type="button"
                disabled={save.isPending}
                className={cn(SOLID, "h-11 text-[15px]")}
                onClick={() => draft && save.mutate(draft)}
              >
                {changes ? t("team.saveCount", { count: format.number(changes) }) : t("team.save")}
              </button>
            </div>
          </div>
        </>
      )}
    </section>
  );
}
