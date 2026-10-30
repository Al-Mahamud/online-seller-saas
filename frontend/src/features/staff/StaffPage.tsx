import { zodResolver } from "@hookform/resolvers/zod";
import { queryOptions, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { z } from "zod";
import { businessQuery, hasRole, type Role, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";
import { ConfirmDialog } from "@/components/form/ConfirmDialog";
import { FormError, PasswordField, SelectField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { BackToMore, PageHeader } from "@/components/layout/PageHeader";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { currentPassword, login } from "@/features/auth/schemas";
import { useFormat } from "@/lib/format";

type Member = components["schemas"]["StaffMember"];

const membersQuery = queryOptions({ queryKey: ["staff"], queryFn: () => call(api.GET("/api/v1/staff")) });
const invitesQuery = queryOptions({
  queryKey: ["staff-invites"],
  queryFn: () => call(api.GET("/api/v1/staff/invites")),
});

export function StaffPage() {
  const { t } = useTranslation();
  return (
    <div className="flex max-w-2xl flex-col gap-5">
      <div>
        <BackToMore />
        <PageHeader title={t("staff.title")} />
      </div>
      <InviteSection />
      <MembersSection />
    </div>
  );
}

function MembersSection() {
  const { t } = useTranslation();
  const { data: members } = useQuery(membersQuery);
  return (
    <Section title={t("staff.members")}>
      <ul className="flex flex-col">
        {members?.map((member) => (
          <MemberRow key={member.userId} member={member} />
        ))}
      </ul>
    </Section>
  );
}

const ASSIGNABLE: Role[] = ["MANAGER", "STAFF"];

function MemberRow({ member }: { member: Member }) {
  const { t } = useTranslation();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<"remove" | "transfer">();
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
  // The server's rules: nobody changes themselves, and the owner changes only by handing over.
  const editable = !member.you && member.role !== "OWNER";
  return (
    <li className="flex flex-col gap-2 border-t py-3 first:border-t-0 first:pt-0 sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0">
        <p className="font-semibold">
          {member.name}
          {member.you && <span className="font-normal text-muted-foreground"> {t("staff.you")}</span>}
        </p>
        <p className="text-sm break-all text-muted-foreground">{member.email ?? member.phone}</p>
      </div>
      {editable ? (
        <div className="flex flex-wrap items-center gap-2">
          <select
            aria-label={t("staff.roleOf", { name: member.name })}
            className="h-11 rounded-lg border border-input bg-card px-2"
            value={member.role}
            disabled={changeRole.isPending}
            onChange={(event) => changeRole.mutate(event.target.value as Role)}
          >
            {ASSIGNABLE.map((role) => (
              <option key={role} value={role}>
                {t(`business.role.${role}`)}
              </option>
            ))}
          </select>
          {hasRole(business, "OWNER") && (
            <Button variant="outline" onClick={() => setDialog("transfer")}>
              {t("staff.makeOwner")}
            </Button>
          )}
          <Button variant="outline" onClick={() => setDialog("remove")}>
            {t("staff.remove")}
          </Button>
        </div>
      ) : (
        <span className="w-fit rounded-full bg-accent px-2.5 text-sm font-semibold text-accent-foreground">
          {t(`business.role.${member.role}`)}
        </span>
      )}
      {dialog === "remove" && (
        <ConfirmDialog
          title={t("staff.removeTitle", { name: member.name })}
          description={t("staff.removeDescription")}
          confirm={t("staff.remove")}
          pending={remove.isPending}
          onConfirm={() => remove.mutate()}
          onClose={() => setDialog(undefined)}
        />
      )}
      {dialog === "transfer" && <TransferDialog member={member} onClose={() => setDialog(undefined)} />}
    </li>
  );
}

const transferSchema = z.object({ currentPassword });

function TransferDialog({ member, onClose }: { member: Member; onClose: () => void }) {
  const { t } = useTranslation();
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

const inviteSchema = z.object({ to: login, role: z.enum(["MANAGER", "STAFF"]) });

function InviteSection() {
  const { t } = useTranslation();
  const format = useFormat();
  const queryClient = useQueryClient();
  const { data: invites } = useQuery(invitesQuery);
  const form = useForm({
    resolver: zodResolver(inviteSchema),
    defaultValues: { to: "", role: "STAFF" as "MANAGER" | "STAFF" },
  });
  const refresh = () => queryClient.invalidateQueries(invitesQuery);
  const invite = useMutation({
    mutationFn: (body: z.output<typeof inviteSchema>) => call(api.POST("/api/v1/staff/invites", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: (sent) => {
      form.reset();
      toast.success(t("staff.inviteSent", { to: sent.to }));
      void refresh();
    },
    onError: (error) =>
      showServerError(form, error, {
        codes: { "staff.already_member": "to", "invite.channel_unavailable": "to" },
      }),
  });
  const revoke = useMutation({
    mutationFn: (inviteId: string) =>
      call(api.DELETE("/api/v1/staff/invites/{inviteId}", { params: { path: { inviteId } } })),
    onSettled: refresh,
  });
  return (
    <Section title={t("staff.invite")}>
      <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => invite.mutate(values))}>
        <FormError form={form} />
        <TextField form={form} name="to" label={t("auth.login")} hint={t("staff.inviteHint")} autoComplete="off" />
        <SelectField form={form} name="role" label={t("staff.role")}>
          {ASSIGNABLE.map((role) => (
            <option key={role} value={role}>
              {t(`business.role.${role}`)}
            </option>
          ))}
        </SelectField>
        <p className="text-sm text-muted-foreground">{t("staff.rolesExplained")}</p>
        <Button type="submit" className="self-start" disabled={invite.isPending}>
          {t("staff.sendInvite")}
        </Button>
      </form>
      {invites && invites.length > 0 && (
        <div className="flex flex-col gap-2">
          <h3 className="font-semibold">{t("staff.openInvites")}</h3>
          <ul className="flex flex-col">
            {invites.map((open) => (
              <li key={open.id} className="flex flex-wrap items-center justify-between gap-2 border-t py-2">
                <div className="min-w-0">
                  <p className="font-medium break-all">{open.to}</p>
                  <p className="text-sm text-muted-foreground">
                    {t(`business.role.${open.role}`)} · {t("staff.expires", { date: format.date(open.expiresAt) })}
                  </p>
                </div>
                <Button
                  variant="outline"
                  aria-label={t("staff.cancelInviteTo", { to: open.to })}
                  disabled={revoke.isPending}
                  onClick={() => revoke.mutate(open.id)}
                >
                  {t("page.cancel")}
                </Button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </Section>
  );
}
