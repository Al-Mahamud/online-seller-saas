import { zodResolver } from "@hookform/resolvers/zod";
import { queryOptions, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod/mini";
import { api, call } from "@/api/client";
import { ApiError } from "@/api/errors";
import { type Me, meQuery } from "@/api/session";
import { CodeField, FormError, PasswordField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { appOrRecoveryCode, code, currentPassword } from "@/features/auth/schemas";
import { useLooseT } from "@/i18n/pages";
import { useFormat } from "@/lib/format";

const statusQuery = queryOptions({
  queryKey: ["two-factor"],
  queryFn: () => call(api.GET("/api/v1/me/two-factor")),
});

type Open = "enable" | "new-codes" | "disable";

export function TwoFactorSection() {
  const t = useLooseT();
  const format = useFormat();
  const { data: status } = useQuery(statusQuery);
  const [open, setOpen] = useState<Open>();
  const close = () => setOpen(undefined);

  return (
    <Section title={t("twoFactor.title")}>
      {status?.enabled ? (
        <>
          <div>
            <p>{t("twoFactor.on", { date: status.enabledAt ? format.date(status.enabledAt) : "" })}</p>
            <p className="text-sm text-muted-foreground">
              {t("twoFactor.recoveryLeft", { left: format.number(status.recoveryCodesLeft) })}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" onClick={() => setOpen("new-codes")}>
              {t("twoFactor.newCodes")}
            </Button>
            <Button variant="outline" onClick={() => setOpen("disable")}>
              {t("twoFactor.turnOff")}
            </Button>
          </div>
        </>
      ) : (
        <>
          <p className="text-muted-foreground">{t("twoFactor.off")}</p>
          <Button className="self-start" disabled={!status} onClick={() => setOpen("enable")}>
            {t("twoFactor.turnOn")}
          </Button>
        </>
      )}
      {open === "enable" && <EnableDialog onClose={close} />}
      {open === "new-codes" && <NewCodesDialog onClose={close} />}
      {open === "disable" && <DisableDialog onClose={close} />}
    </Section>
  );
}

/** The account and the status changed: both are read again, or set directly where the answer allows. */
function useTwoFactorChanged() {
  const queryClient = useQueryClient();
  return (enabled: boolean) => {
    void queryClient.invalidateQueries(statusQuery);
    const me = queryClient.getQueryData(meQuery.queryKey);
    if (me) {
      const updated: Me = { ...me, twoFactorEnabled: enabled };
      queryClient.setQueryData(meQuery.queryKey, updated);
    }
  };
}

const passwordSchema = z.object({ password: currentPassword });
const codeSchema = z.object({ code });

/** The password, then the QR code and the app's first code, then the recovery codes, shown this once. */
function EnableDialog({ onClose }: { onClose: () => void }) {
  const t = useLooseT();
  const changed = useTwoFactorChanged();
  const [setup, setSetup] = useState<{ secret: string; otpauthUri: string }>();
  const [codes, setCodes] = useState<string[]>();
  const passwordForm = useForm({ resolver: zodResolver(passwordSchema), defaultValues: { password: "" } });
  const codeForm = useForm({ resolver: zodResolver(codeSchema), defaultValues: { code: "" } });

  const start = useMutation({
    mutationFn: (body: { password: string }) => call(api.POST("/api/v1/me/two-factor/setup", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: setSetup,
    onError: (error) => showServerError(passwordForm, error, { codes: { "account.wrong_password": "password" } }),
  });
  const enable = useMutation({
    mutationFn: (body: { code: string }) => call(api.POST("/api/v1/me/two-factor/enable", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: (result) => {
      setCodes(result.recoveryCodes);
      changed(true);
    },
    // The server's words mention recovery codes, which don't exist until this is done.
    onError: (error) =>
      error instanceof ApiError && error.code === "two_factor.invalid_code"
        ? codeForm.setError("code", { type: "server", message: t("twoFactor.setupWrongCode") })
        : showServerError(codeForm, error),
  });

  if (codes) {
    return <RecoveryCodes codes={codes} onDone={onClose} />;
  }
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-dvh overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("twoFactor.title")}</DialogTitle>
          <DialogDescription>{setup ? t("twoFactor.scanStep") : t("twoFactor.passwordStep")}</DialogDescription>
        </DialogHeader>
        {setup ? (
          <form
            noValidate
            className="flex flex-col gap-4"
            onSubmit={codeForm.handleSubmit((values) => enable.mutate(values))}
          >
            <FormError form={codeForm} />
            <QrCode uri={setup.otpauthUri} />
            <div>
              <p className="text-sm text-muted-foreground">{t("twoFactor.secret")}</p>
              <code data-testid="two-factor-secret" lang="en" className="font-mono text-sm font-semibold break-all">
                {setup.secret}
              </code>
            </div>
            <CodeField form={codeForm} name="code" label={t("twoFactor.appCode")} />
            <Button type="submit" disabled={enable.isPending}>
              {t("twoFactor.enable")}
            </Button>
          </form>
        ) : (
          <form
            noValidate
            className="flex flex-col gap-4"
            onSubmit={passwordForm.handleSubmit((values) => start.mutate(values))}
          >
            <FormError form={passwordForm} />
            <PasswordField
              form={passwordForm}
              name="password"
              label={t("twoFactor.password")}
              autoComplete="current-password"
            />
            <Button type="submit" disabled={start.isPending}>
              {t("twoFactor.continue")}
            </Button>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}

/** Drawn here in the browser, so the secret never goes to anyone else to draw. */
function QrCode({ uri }: { uri: string }) {
  const t = useLooseT();
  const [image, setImage] = useState<string>();
  useEffect(() => {
    let current = true;
    void import("qrcode")
      .then((qrcode) => qrcode.toDataURL(uri, { margin: 1, width: 220 }))
      .then((url) => {
        if (current) {
          setImage(url);
        }
      });
    return () => {
      current = false;
    };
  }, [uri]);
  return (
    <div className="flex h-55 items-center justify-center">
      {image && <img src={image} alt={t("twoFactor.qrAlt")} width={220} height={220} />}
    </div>
  );
}

/** Shown once: the dialog closes only by saying they are saved. */
function RecoveryCodes({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  const t = useLooseT();
  const text = `${codes.join("\n")}\n`;

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      toast.success(t("twoFactor.copied"));
    } catch {
      toast.error(t("twoFactor.copyFailed"));
    }
  }

  function download() {
    const url = URL.createObjectURL(new Blob([text], { type: "text/plain" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "mojud-recovery-codes.txt";
    link.click();
    URL.revokeObjectURL(url);
  }

  return (
    <Dialog open>
      <DialogContent
        showCloseButton={false}
        className="max-h-dvh overflow-y-auto"
        onEscapeKeyDown={(event) => event.preventDefault()}
        onInteractOutside={(event) => event.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>{t("twoFactor.codesTitle")}</DialogTitle>
          <DialogDescription>{t("twoFactor.codesWarning")}</DialogDescription>
        </DialogHeader>
        <ul data-testid="recovery-codes" lang="en" className="grid grid-cols-2 gap-x-4 gap-y-1 font-mono text-sm">
          {codes.map((recoveryCode) => (
            <li key={recoveryCode}>{recoveryCode}</li>
          ))}
        </ul>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={() => void copy()}>
            {t("twoFactor.copy")}
          </Button>
          <Button variant="outline" onClick={download}>
            {t("twoFactor.download")}
          </Button>
        </div>
        <Button onClick={onDone}>{t("twoFactor.saved")}</Button>
      </DialogContent>
    </Dialog>
  );
}

function NewCodesDialog({ onClose }: { onClose: () => void }) {
  const t = useLooseT();
  const changed = useTwoFactorChanged();
  const [codes, setCodes] = useState<string[]>();
  const form = useForm({ resolver: zodResolver(codeSchema), defaultValues: { code: "" } });
  const create = useMutation({
    mutationFn: (body: { code: string }) => call(api.POST("/api/v1/me/two-factor/recovery-codes", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: (result) => {
      setCodes(result.recoveryCodes);
      changed(true);
    },
    onError: (error) => showServerError(form, error, { codes: { "two_factor.invalid_code": "code" } }),
  });

  if (codes) {
    return <RecoveryCodes codes={codes} onDone={onClose} />;
  }
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("twoFactor.newCodes")}</DialogTitle>
          <DialogDescription>{t("twoFactor.newCodesStep")}</DialogDescription>
        </DialogHeader>
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={form.handleSubmit((values) => create.mutate(values))}
        >
          <FormError form={form} />
          <CodeField form={form} name="code" label={t("twoFactor.appCode")} />
          <Button type="submit" disabled={create.isPending}>
            {t("twoFactor.createCodes")}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}

const disableSchema = z.object({ password: currentPassword, code: appOrRecoveryCode });

function DisableDialog({ onClose }: { onClose: () => void }) {
  const t = useLooseT();
  const changed = useTwoFactorChanged();
  const form = useForm({ resolver: zodResolver(disableSchema), defaultValues: { password: "", code: "" } });
  const disable = useMutation({
    mutationFn: (body: z.output<typeof disableSchema>) => call(api.POST("/api/v1/me/two-factor/disable", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: () => {
      changed(false);
      toast.success(t("twoFactor.offDone"));
      onClose();
    },
    onError: (error) =>
      showServerError(form, error, {
        codes: { "account.wrong_password": "password", "two_factor.invalid_code": "code" },
      }),
  });
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("twoFactor.turnOff")}</DialogTitle>
          <DialogDescription>{t("twoFactor.offStep")}</DialogDescription>
        </DialogHeader>
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={form.handleSubmit((values) => disable.mutate(values))}
        >
          <FormError form={form} />
          <PasswordField form={form} name="password" label={t("twoFactor.password")} autoComplete="current-password" />
          <TextField form={form} name="code" label={t("twoFactor.appOrRecovery")} autoComplete="one-time-code" />
          <Button type="submit" disabled={disable.isPending}>
            {t("twoFactor.turnOff")}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
