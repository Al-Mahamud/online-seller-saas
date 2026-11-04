import { zodResolver } from "@hookform/resolvers/zod";
import { queryOptions, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Camera, Minus, Plus } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { type UseFormReturn, useForm, useWatch } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { z } from "zod/mini";
import { type Business, businessQuery, can, hasRole, initials, settingsQuery, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";
import { uploadImage } from "@/api/uploads";
import { FormError, TextAreaField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { BackToMore } from "@/components/layout/PageHeader";
import { Button } from "@/components/ui/button";
import { billingQuery, usePlanName } from "@/features/billing/api";
import { lowStockQuery } from "@/features/catalog/api";
import { LowStockLink } from "@/features/catalog/LowStockWindow";
import { ordersQuery } from "@/features/orders/api";
import { banglaDigits, latinDigits } from "@/lib/digits";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { DeletionSection } from "./DeletionSection";
import { SearchColourChoice } from "./SearchColourSection";
import { businessFields } from "./schemas";

type Settings = components["schemas"]["SettingsResponse"];

const membersQuery = queryOptions({ queryKey: ["staff"], queryFn: () => call(api.GET("/api/v1/staff")) });

const FOOTER_MAX = 500;
/** The first order's number in a new business. */
const FIRST_ORDER = 1001;
const CARD = "flex scroll-mt-4 flex-col gap-3.5 rounded-2xl border bg-card p-3.5 md:p-5";
const STEP =
  "flex size-11 flex-none cursor-pointer items-center justify-center rounded-[10px] border-[1.5px] border-input bg-card hover:bg-muted";
const SECTIONS = ["info", "stock", "slip", "colour", "danger"] as const;
type SectionId = (typeof SECTIONS)[number];
const sectionId = (section: SectionId) => `business-${section}`;

const schema = z.object({
  ...businessFields,
  lowStockDefault: z.pipe(
    z.pipe(
      z.pipe(
        z.pipe(z.string().check(z.trim()), z.transform(latinDigits)),
        z.string().check(z.regex(/^\d{1,6}$/, "field.numberRange")),
      ),
      z.transform(Number),
    ),
    z.number().check(z.maximum(100_000, "field.numberRange")),
  ),
  orderNumberPrefix: z.pipe(
    z.pipe(
      z.string().check(z.trim()),
      z.transform((text: string) => text.toUpperCase()),
    ),
    z.string().check(z.regex(/^[A-Z0-9-]{0,10}$/, "field.prefixInvalid")),
  ),
  invoiceFooter: z.string().check(z.trim(), z.maxLength(FOOTER_MAX, "field.footerLong")),
  searchTheme: z.enum(["LEDGER", "MINT"]),
});
type Values = z.input<typeof schema>;
type Form = UseFormReturn<Values, unknown, z.output<typeof schema>>;

const PROFILE = ["name", "phone", "address"] as const;
const SETTINGS = ["lowStockDefault", "orderNumberPrefix", "invoiceFooter"] as const;

const valuesOf = (business: Business, settings: Settings): Values => ({
  name: business.name,
  phone: business.phone ?? "",
  address: business.address ?? "",
  lowStockDefault: String(settings.lowStockDefault),
  orderNumberPrefix: settings.orderNumberPrefix,
  invoiceFooter: settings.invoiceFooter,
  searchTheme: settings.searchTheme,
});

/**
 * The business: its details and logo, when stock goes down, the order number and slip, the search
 * colour, and deleting it. Managers and owners; one save for everything changed.
 */
export function BusinessPage() {
  const { data: settings } = useQuery(settingsQuery);
  const business = useBusiness();
  // The form starts once the settings are in, so nothing typed is lost to them arriving.
  return settings ? <Page business={business} settings={settings} /> : null;
}

function Page({ business, settings }: { business: Business; settings: Settings }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const format = useFormat();
  const isOwner = hasRole(business, "OWNER");
  const form: Form = useForm({ resolver: zodResolver(schema), defaultValues: valuesOf(business, settings) });
  const changed = Object.keys(form.formState.dirtyFields).length;
  const logo = useLogo();
  const searchTheme = useWatch({ control: form.control, name: "searchTheme" });

  const save = useMutation({
    mutationFn: async (values: z.output<typeof schema>) => {
      const dirty = form.formState.dirtyFields;
      let savedBusiness = business;
      let savedSettings = settings;
      if (PROFILE.some((key) => dirty[key])) {
        savedBusiness = await call(
          api.PATCH("/api/v1/business", { body: { name: values.name, phone: values.phone, address: values.address } }),
        );
        queryClient.setQueryData(businessQuery.queryKey, savedBusiness);
      }
      if (SETTINGS.some((key) => dirty[key])) {
        const { lowStockDefault, orderNumberPrefix, invoiceFooter } = values;
        savedSettings = await call(
          api.PATCH("/api/v1/business/settings", {
            body: { lowStockDefault, orderNumberPrefix, invoiceFooter },
          }),
        );
        queryClient.setQueryData(settingsQuery.queryKey, savedSettings);
      }
      if (isOwner && dirty.searchTheme) {
        savedSettings = await call(
          api.PUT("/api/v1/business/search-theme", { body: { searchTheme: values.searchTheme } }),
        );
        queryClient.setQueryData(settingsQuery.queryKey, savedSettings);
      }
      return valuesOf(savedBusiness, savedSettings);
    },
    meta: { showsOwnErrors: true },
    onSuccess: (saved) => {
      form.reset(saved);
      toast.success(t("business.page.saved"));
    },
    onError: (error) => showServerError(form, error),
  });

  return (
    <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => save.mutate(values))}>
      <Header form={form} business={business} onLogo={logo.pick} />
      <div className="flex gap-6 max-md:relative max-md:-mx-4 max-md:-mt-7.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3 max-md:pt-3.5">
        <SectionNav isOwner={isOwner} />
        <div className="flex min-w-0 flex-1 flex-col gap-4.5">
          <SectionChips isOwner={isOwner} />
          <FormError form={form} strong />
          <InfoCard form={form} business={business} logo={logo} />
          <StockCard form={form} />
          <SlipCard form={form} business={business} />
          <section id={sectionId("colour")} aria-labelledby={`${sectionId("colour")}-title`} className={CARD}>
            <CardTitle
              id={`${sectionId("colour")}-title`}
              title={t("search.theme.title")}
              hint={t("business.page.colourHint")}
            />
            <SearchColourChoice
              value={searchTheme}
              canChange={isOwner}
              onChange={(theme) => form.setValue("searchTheme", theme, { shouldDirty: true })}
            />
          </section>
          <DeletionSection id={sectionId("danger")} />
          {changed > 0 && (
            <section
              aria-label={t("business.page.unsavedRegion")}
              className="sticky bottom-[calc(96px+env(safe-area-inset-bottom))] z-10 flex flex-wrap items-center justify-between gap-2.5 rounded-2xl bg-ledger py-3 pr-3.5 pl-4.5 text-white shadow-[0_16px_36px_rgba(7,26,20,0.35)] md:bottom-6"
            >
              <p role="status" className="flex items-center gap-2 text-[15px] font-bold">
                <span aria-hidden="true" className="size-2 rounded-full bg-marigold" />
                {t("business.page.unsaved", { count: format.number(changed) })}
              </p>
              <div className="flex gap-2 max-md:w-full">
                <button
                  type="button"
                  className="h-11 flex-1 cursor-pointer rounded-xl border-[1.5px] border-[#5E8B7C] px-4 whitespace-nowrap text-[15px] font-bold hover:bg-white/10"
                  onClick={() => form.reset()}
                >
                  {t("business.page.discard")}
                </button>
                <button
                  type="submit"
                  disabled={save.isPending}
                  className="h-11 flex-1 cursor-pointer rounded-xl bg-marigold px-4.5 whitespace-nowrap text-[15px] font-bold text-ledger hover:bg-marigold-text disabled:opacity-70"
                >
                  {t("business.page.save")}
                </button>
              </div>
            </section>
          )}
        </div>
      </div>
      {logo.input}
    </form>
  );
}

/** One hidden file input for the logo, opened from the header and from the details card. */
function useLogo() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const update = (updated: Business) => queryClient.setQueryData(businessQuery.queryKey, updated);
  const upload = useMutation({
    mutationFn: async (file: File) => {
      const uploaded = await uploadImage(file);
      return call(api.PUT("/api/v1/business/logo", { body: { fileId: uploaded.id } }));
    },
    onSuccess: update,
  });
  const remove = useMutation({
    mutationFn: () => call(api.DELETE("/api/v1/business/logo")),
    onSuccess: update,
  });
  return {
    busy: upload.isPending || remove.isPending,
    pick: () => input.current?.click(),
    remove: () => remove.mutate(),
    input: (
      <input
        ref={input}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        aria-label={t("business.profile.upload")}
        className="sr-only"
        tabIndex={-1}
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) {
            upload.mutate(file);
          }
          event.target.value = "";
        }}
      />
    ),
  };
}
type Logo = ReturnType<typeof useLogo>;

/** The logo, or the name's first letters. Described once on the page; elsewhere it is decoration. */
function LogoTile({
  business,
  name,
  className,
  described = false,
}: {
  business: Business;
  name: string;
  className: string;
  described?: boolean;
}) {
  const { t } = useTranslation();
  return business.logo ? (
    <img
      src={business.logo.small}
      alt={described ? t("business.profile.logoAlt") : ""}
      className={cn("object-cover", className)}
    />
  ) : (
    <span
      aria-hidden="true"
      className={cn("flex items-center justify-center bg-marigold-text font-bold text-ledger", className)}
    >
      {initials(name || business.name)}
    </span>
  );
}

/** "+8801712…" or what is being typed, as the slip prints it. */
function useContactLine(form: Form) {
  const { t } = useTranslation();
  const { i18n } = useTranslation();
  const [phone, address] = useWatch({ control: form.control, name: ["phone", "address"] });
  const shownPhone = i18n.resolvedLanguage === "en" ? phone.trim() : banglaDigits(phone.trim());
  return [shownPhone, address.trim()].filter(Boolean).join(" · ") || t("business.page.contactNone");
}

/** The next order's number: one after the newest, or the first. */
function useNextNumber(form: Form) {
  const format = useFormat();
  const prefix = useWatch({ control: form.control, name: "orderNumberPrefix" }).trim().toUpperCase();
  const { data } = useQuery({ ...ordersQuery({ size: 1 }), enabled: can(useBusiness(), "ORDERS") });
  const newest = data?.items[0]?.number;
  const next = newest ? newest + 1 : FIRST_ORDER;
  return format.orderNumber(`${prefix || "#"}${next}`);
}

function Header({ form, business, onLogo }: { form: Form; business: Business; onLogo: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const planName = usePlanName();
  const name = useWatch({ control: form.control, name: "name" });
  const low = useWatch({ control: form.control, name: "lowStockDefault" });
  const contact = useContactLine(form);
  const nextNumber = useNextNumber(form);
  const { data: billing } = useQuery(billingQuery);
  const { data: members } = useQuery({ ...membersQuery, enabled: can(business, "TEAM") });
  const lowNumber = Number(latinDigits(low.trim()));
  const figures = [
    { label: t("business.page.commitAt"), value: t("business.page.commitSHIP") },
    {
      label: t("business.page.lowLimit"),
      value: Number.isFinite(lowNumber) ? t("business.page.count", { count: format.number(lowNumber) }) : "–",
      className: "text-marigold-text",
    },
    { label: t("business.page.nextNumber"), value: nextNumber },
    ...(members
      ? [
          {
            label: t("business.page.members"),
            value: t("business.page.memberCount", { count: format.number(members.length) }),
          },
        ]
      : []),
  ];
  return (
    <header className="relative flex flex-col gap-3.5 bg-ledger pt-1 pr-4.5 pb-10.5 pl-6.5 text-white max-md:-mx-4 max-md:-mt-4 md:overflow-hidden md:rounded-2xl md:pt-4.5 md:pr-8 md:pb-5 md:pl-14">
      <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:left-7" />
      <div className="max-md:[&_a]:text-marigold-text">
        <BackToMore />
      </div>
      <div className="flex flex-wrap items-center gap-4">
        <div className="relative flex-none">
          <LogoTile business={business} name={name} className="size-16 rounded-[18px] text-3xl md:size-21" />
          <button
            type="button"
            aria-label={t("business.page.changeLogo")}
            className="absolute -right-1.5 -bottom-1.5 flex size-8.5 cursor-pointer items-center justify-center rounded-full border-2 border-ledger bg-card text-primary hover:bg-accent"
            onClick={onLogo}
          >
            <Camera aria-hidden="true" className="size-4" />
          </button>
        </div>
        <div className="flex min-w-0 flex-[1_1_240px] flex-col gap-1.5">
          <h1 className="text-[26px] leading-tight font-bold break-words md:text-[34px]">
            {name.trim() || business.name}
          </h1>
          <div className="flex flex-wrap gap-1.5">
            <span className="rounded-full bg-white/15 px-2.5 text-sm font-bold">
              {t(`business.role.${business.role}`)}
            </span>
            {billing && (
              <Link
                to="/billing"
                className="rounded-full bg-marigold px-2.5 text-sm font-bold text-ledger no-underline hover:bg-marigold-text hover:text-ledger"
              >
                {planName(billing.plan)}
              </Link>
            )}
          </div>
          <p className="text-[15px] break-words text-ledger-text">{contact}</p>
        </div>
      </div>
      <dl className="grid grid-cols-2 gap-x-5 gap-y-2.5 border-t border-ledger-outline pt-3 md:grid-cols-4">
        {figures.map((figure) => (
          <div key={figure.label}>
            <dt className="text-sm font-semibold text-ledger-text">{figure.label}</dt>
            <dd className={cn("text-lg font-bold", figure.className)}>{figure.value}</dd>
          </div>
        ))}
      </dl>
    </header>
  );
}

function visibleSections(isOwner: boolean, business: Business): SectionId[] {
  return SECTIONS.filter((section) => section !== "danger" || (isOwner && !business.deletionDueAt));
}

/** Which part is in view, for the side list. */
function useCurrentSection(sections: SectionId[]) {
  const [current, setCurrent] = useState<SectionId>(sections[0] ?? "info");
  useEffect(() => {
    const shown = new Map<string, boolean>();
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          shown.set(entry.target.id, entry.isIntersecting);
        }
        const first = sections.find((section) => shown.get(sectionId(section)));
        if (first) {
          setCurrent(first);
        }
      },
      { rootMargin: "0px 0px -55% 0px" },
    );
    for (const section of sections) {
      const element = document.getElementById(sectionId(section));
      if (element) {
        observer.observe(element);
      }
    }
    return () => observer.disconnect();
  }, [sections]);
  return current;
}

function jumpTo(section: SectionId) {
  const element = document.getElementById(sectionId(section));
  element?.scrollIntoView({ behavior: "smooth", block: "start" });
  element?.querySelector<HTMLElement>("h2")?.focus({ preventScroll: true });
}

function SectionNav({ isOwner }: { isOwner: boolean }) {
  const { t } = useTranslation();
  const business = useBusiness();
  const [sections] = useState(() => visibleSections(isOwner, business));
  const current = useCurrentSection(sections);
  return (
    <nav aria-label={t("business.page.sections")} className="hidden w-52.5 flex-none md:block">
      <ul className="sticky top-6 flex flex-col gap-1">
        {sections.map((section) => {
          const on = current === section;
          const danger = section === "danger";
          return (
            <li key={section}>
              <a
                href={`#${sectionId(section)}`}
                aria-current={on ? "location" : undefined}
                className={cn(
                  "flex min-h-11 items-center gap-2.5 rounded-[10px] px-3 text-[15px] no-underline",
                  on
                    ? "bg-accent font-bold text-accent-foreground hover:text-accent-foreground"
                    : "font-semibold text-foreground hover:bg-muted hover:text-foreground",
                  danger && "text-destructive hover:text-destructive",
                )}
                onClick={(event) => {
                  event.preventDefault();
                  jumpTo(section);
                }}
              >
                <span
                  aria-hidden="true"
                  className={cn("size-2 rounded-full", on ? "bg-primary" : danger ? "bg-[#E4A49B]" : "bg-input")}
                />
                {t(`business.page.nav.${section}`)}
              </a>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

function SectionChips({ isOwner }: { isOwner: boolean }) {
  const { t } = useTranslation();
  const business = useBusiness();
  return (
    <nav aria-label={t("business.page.sections")} className="flex flex-wrap gap-1.5 md:hidden">
      {visibleSections(isOwner, business).map((section) => (
        <a
          key={section}
          href={`#${sectionId(section)}`}
          className={cn(
            "flex min-h-10 items-center rounded-full border-[1.5px] border-input bg-card px-3 text-sm font-bold text-foreground no-underline hover:text-foreground",
            section === "danger" && "text-destructive hover:text-destructive",
          )}
          onClick={(event) => {
            event.preventDefault();
            jumpTo(section);
          }}
        >
          {t(`business.page.nav.${section}`)}
        </a>
      ))}
    </nav>
  );
}

function CardTitle({ id, title, hint }: { id: string; title: string; hint: string }) {
  return (
    <div>
      <h2 id={id} tabIndex={-1} className="text-xl font-bold outline-none">
        {title}
      </h2>
      <p className="text-sm text-muted-foreground">{hint}</p>
    </div>
  );
}

function InfoCard({ form, business, logo }: { form: Form; business: Business; logo: Logo }) {
  const { t } = useTranslation();
  const name = useWatch({ control: form.control, name: "name" });
  const id = sectionId("info");
  return (
    <section id={id} aria-labelledby={`${id}-title`} className={CARD}>
      <CardTitle id={`${id}-title`} title={t("business.profile.title")} hint={t("business.page.infoHint")} />
      <div className="flex flex-wrap items-center gap-3.5 rounded-xl bg-background p-3">
        <LogoTile business={business} name={name} className="size-16 rounded-[14px] text-2xl" described />
        <div className="min-w-0 flex-[1_1_180px]">
          <p className="font-bold">{t("business.profile.logo")}</p>
          <p className="text-sm text-muted-foreground">{t("business.page.logoHint")}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            variant="outline"
            className="h-11 border-[1.5px] border-primary px-4 text-[15px] font-bold text-primary"
            disabled={logo.busy}
            onClick={logo.pick}
          >
            {business.logo ? t("business.profile.change") : t("business.profile.upload")}
          </Button>
          {business.logo && (
            <Button
              type="button"
              variant="outline"
              className="h-11 px-4 text-[15px] font-bold"
              disabled={logo.busy}
              onClick={logo.remove}
            >
              {t("business.profile.remove")}
            </Button>
          )}
        </div>
      </div>
      <div className="grid gap-3.5 md:grid-cols-2">
        <TextField form={form} name="name" label={t("business.name")} autoComplete="organization" />
        <TextField form={form} name="phone" label={t("business.phone")} type="tel" />
        <div className="md:col-span-2">
          <TextField form={form} name="address" label={t("business.address")} autoComplete="street-address" />
        </div>
      </div>
    </section>
  );
}

/** Where an order's stock goes on its way: set aside when confirmed, gone when handed to the courier. */
const STEPS = [
  { name: "new", mark: undefined },
  { name: "confirmed", mark: "held" },
  { name: "shipped", mark: "drops" },
] as const;

function StockCard({ form }: { form: Form }) {
  const { t } = useTranslation();
  const format = useFormat();
  const { data: lowStock } = useQuery({ ...lowStockQuery({ size: 1 }), enabled: can(useBusiness(), "STOCK") });
  const id = sectionId("stock");
  const step = (by: number) => {
    const now = Number(latinDigits(form.getValues("lowStockDefault").trim()));
    const next = Math.max(0, (Number.isFinite(now) ? now : 0) + by);
    form.setValue("lowStockDefault", String(next), { shouldDirty: true, shouldValidate: true });
  };
  return (
    <section id={id} aria-labelledby={`${id}-title`} className={CARD}>
      <CardTitle id={`${id}-title`} title={t("business.settings.title")} hint={t("business.page.stockHint")} />
      {/* Fixed for now: stock goes down when an order is handed to the courier. */}
      <div className="flex flex-col gap-3 rounded-[14px] border-[1.5px] border-primary bg-[#F3F9F6] p-3.5 md:max-w-xl">
        <div>
          <h3 className="font-bold">{t("business.settings.stockQuestion")}</h3>
          <p className="font-bold text-primary">{t("business.settings.SHIP")}</p>
          <p className="text-sm text-[#3E4842]">{t("business.settings.SHIPHint")}</p>
        </div>
        <div aria-hidden="true" className="flex items-start gap-1.5">
          {STEPS.map(({ name, mark }) => (
            <span key={name} className="flex flex-1 flex-col items-center gap-1 text-center">
              <span
                className={cn(
                  "h-1.5 w-full rounded-sm",
                  mark === "drops" ? "bg-[#C4820F]" : mark === "held" ? "bg-[#F0D9A8]" : "bg-border",
                )}
              />
              <span className="text-[13px] font-bold">{t(`business.page.step.${name}`)}</span>
              <span
                className={cn(
                  "min-h-4.5 text-xs font-bold",
                  mark === "drops" ? "text-warning-foreground" : "text-muted-foreground",
                )}
              >
                {mark && t(`business.page.step.${mark}`)}
              </span>
            </span>
          ))}
        </div>
        <p className="text-sm text-muted-foreground">{t("business.page.commitFixed")}</p>
      </div>
      <div className="border-t pt-3.5 md:max-w-md">
        <TextField
          form={form}
          name="lowStockDefault"
          label={t("business.settings.lowStock")}
          inputMode="numeric"
          className="h-11 text-center text-lg font-bold"
          hint={
            <>
              {t("business.settings.lowStockHint")}
              {!!lowStock?.totalItems && (
                <LowStockLink className="ml-1 font-bold">
                  {t("business.page.lowNow", { count: format.number(lowStock.totalItems) })}
                </LowStockLink>
              )}
            </>
          }
          beside={
            <>
              <button type="button" aria-label={t("business.page.less")} className={STEP} onClick={() => step(-1)}>
                <Minus aria-hidden="true" className="size-4.5" />
              </button>
              <button type="button" aria-label={t("business.page.more")} className={STEP} onClick={() => step(1)}>
                <Plus aria-hidden="true" className="size-4.5" />
              </button>
            </>
          }
        />
      </div>
    </section>
  );
}

function SlipCard({ form, business }: { form: Form; business: Business }) {
  const { t } = useTranslation();
  const format = useFormat();
  const [name, footer] = useWatch({ control: form.control, name: ["name", "invoiceFooter"] });
  const contact = useContactLine(form);
  const nextNumber = useNextNumber(form);
  const id = sectionId("slip");
  return (
    <section id={id} aria-labelledby={`${id}-title`} className={CARD}>
      <CardTitle id={`${id}-title`} title={t("business.page.nav.slip")} hint={t("business.page.slipHint")} />
      <div className="flex flex-wrap gap-4.5">
        <div className="flex min-w-0 flex-[1_1_280px] flex-col gap-3.5">
          <TextField
            form={form}
            name="orderNumberPrefix"
            label={t("business.settings.prefix")}
            hint={t("business.page.prefixHint", { number: nextNumber })}
            maxLength={10}
            autoCapitalize="characters"
            className="font-bold tracking-wide uppercase"
          />
          <TextAreaField
            form={form}
            name="invoiceFooter"
            label={t("business.settings.footer")}
            hint={`${t("business.settings.footerHint")} ${t("business.page.footerLeft", {
              count: format.number(Math.max(0, FOOTER_MAX - footer.length)),
            })}`}
          />
        </div>
        <figure
          aria-label={t("business.page.slipPreview")}
          className="flex flex-[0_1_280px] flex-col gap-2 self-start rounded-md border bg-card p-4 text-[13px] shadow-[0_10px_24px_rgba(7,57,43,0.12)] max-md:flex-1"
        >
          <div className="flex items-center gap-2">
            <LogoTile business={business} name={name} className="size-7.5 flex-none rounded-lg text-[13px]" />
            <span className="min-w-0">
              <span className="block text-sm font-bold break-words">{name.trim() || business.name}</span>
              <span className="block break-words text-muted-foreground">{contact}</span>
            </span>
          </div>
          <p className="border-y border-dashed border-input py-1.5 font-bold">
            {t("business.page.slipOrder", { number: nextNumber })}
          </p>
          <span aria-hidden="true" className="flex flex-col gap-1.5 py-1">
            <span className="h-2 w-4/5 rounded bg-muted" />
            <span className="h-2 w-3/5 rounded bg-muted" />
            <span className="h-2 w-2/3 rounded bg-muted" />
          </span>
          <p className="border-t border-dashed border-input pt-2 text-center whitespace-pre-wrap text-[#3E4842]">
            {footer.trim() || t("business.page.slipNoFooter")}
          </p>
        </figure>
      </div>
    </section>
  );
}
