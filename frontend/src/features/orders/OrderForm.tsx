import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { Check, ChevronDown, Minus, Plus, X } from "lucide-react";
import { type ComponentProps, lazy, type ReactNode, Suspense, useEffect, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call, newIdempotencyKey } from "@/api/client";
import { ApiError, errorAction } from "@/api/errors";
import { AreaField, Field, SelectBox } from "@/components/form/Field";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useCanWrite } from "@/features/billing/status";
import { customerSearch } from "@/features/customers/api";
import { PlacePicker, type PlaceValue } from "@/features/delivery/PlacePicker";
import { latinDigits } from "@/lib/digits";
import { useFormat } from "@/lib/format";
import { parseCount, parseTaka, takaInput } from "@/lib/money";
import { cn } from "@/lib/utils";
import { type Order, orderChanged, orderErrorText, PAYMENT_METHODS, type PaymentMethod } from "./api";
import type { PickedItem } from "./ItemsDialog";

// Brings the dialog library, which the form otherwise doesn't need until items are added.
const ItemsDialog = lazy(() => import("./ItemsDialog"));

const MAX_QUANTITY = 10_000;

const CARD = "flex flex-col gap-3 rounded-[14px] border bg-card p-3.5 md:px-5 md:py-4";
const LINE_COLUMNS = "md:grid-cols-[minmax(0,1fr)_140px_104px_88px_44px] md:gap-x-3";
const KNOWN_CUSTOMER =
  "flex min-h-12 flex-wrap items-center gap-x-2.5 gap-y-0.5 rounded-[10px] border-[1.5px] border-ledger-outline px-3 py-2";
const ON_DARK_FIELD =
  "min-w-0 [&_input]:h-13 [&_input]:rounded-[10px] [&_input]:border-0 [&_input]:bg-card [&_input]:px-3.5 [&_input]:text-foreground md:[&_input]:h-12 [&_input:focus-visible]:outline-3 [&_input:focus-visible]:outline-offset-2 [&_input:focus-visible]:outline-marigold-text [&_label]:text-ledger-text [&_p]:font-semibold [&_p]:text-marigold-text";

interface Line {
  variantId: string;
  name: string;
  label: string;
  /** Null for a line already on the order: its stock isn't re-read. */
  available: number | null;
  quantity: string;
  price: string;
}

type Errors = Record<string, string>;

/** Where the server's field names land in this form. */
const SERVER_FIELDS: Record<string, string> = {
  phone: "phone",
  "customer.phone": "phone",
  name: "name",
  "customer.name": "name",
  address: "address",
  "customer.address": "address",
  "delivery.address": "address",
  districtId: "district",
  areaId: "district",
  "customer.districtId": "district",
  "customer.areaId": "district",
  "delivery.districtId": "district",
  "delivery.areaId": "district",
  "delivery.name": "recipientName",
  "delivery.phone": "recipientPhone",
  items: "items",
  discount: "discount",
  deliveryCharge: "charge",
  advancePaid: "advance",
  note: "note",
};

const amountOf = (text: string) => (text.trim() === "" ? 0 : parseTaka(text));

/** Goes first to whatever needs fixing: on a phone it is usually off screen. */
function useFocusProblem(errors: Errors) {
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (Object.keys(errors).length > 0) {
      const problem = box.current?.querySelector<HTMLElement>('[aria-invalid="true"], [data-problem]');
      problem?.focus();
      problem?.scrollIntoView({ block: "center" });
    }
  }, [errors]);
  return box;
}

/**
 * A new order, or changes to `order` (a draft, or a confirmed one, whose customer stays). Not a
 * <form>: the Enter key in the product search must never save an order.
 */
export function OrderForm({
  order,
  initialPhone,
  heading,
}: {
  order?: Order;
  initialPhone?: string;
  /** The way back and the title, which on phones share the dark header. */
  heading: ReactNode;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const customerFixed = order?.status === "CONFIRMED";
  const canWrite = useCanWrite();

  const [phone, setPhone] = useState(order?.customer.phone ?? initialPhone ?? "");
  const [name, setName] = useState(order?.customer.name ?? "");
  const [address, setAddress] = useState(order?.delivery.address ?? "");
  const [place, setPlace] = useState<PlaceValue>({
    districtId: order?.delivery.district?.id ?? null,
    areaId: order?.delivery.area?.id ?? null,
  });
  const [recipientName, setRecipientName] = useState(
    order && order.delivery.name !== order.customer.name ? order.delivery.name : "",
  );
  const [recipientPhone, setRecipientPhone] = useState(
    order && order.delivery.phone !== order.customer.phone ? order.delivery.phone : "",
  );
  // Open on an order that has one; after that it stays as the seller leaves it.
  const [recipientOpen] = useState(!!(recipientName || recipientPhone));
  const [lines, setLines] = useState<Line[]>(
    () =>
      order?.items.map((item) => ({
        variantId: item.variantId,
        name: item.productName,
        label: item.variantLabel,
        available: null,
        quantity: String(item.quantity),
        price: takaInput(item.unitPrice),
      })) ?? [],
  );
  const [charge, setCharge] = useState(order ? takaInput(order.deliveryCharge) : "");
  const [chargeTouched, setChargeTouched] = useState(!!order);
  const [discount, setDiscount] = useState(order?.discount ? takaInput(order.discount) : "");
  const [advance, setAdvance] = useState("");
  const [advanceMethod, setAdvanceMethod] = useState<PaymentMethod>("BKASH");
  const [advanceReference, setAdvanceReference] = useState("");
  const [note, setNote] = useState(order?.note ?? "");
  const [adding, setAdding] = useState(false);
  const [errors, setErrors] = useState<Errors>({});
  const [problem, setProblem] = useState<string>();
  const box = useFocusProblem(errors);
  // Kept for retries of this one order, so a double tap or a bad connection never makes two.
  const idempotencyKey = useRef(newIdempotencyKey());

  const wholeNumber = customerFixed ? undefined : customerSearch(phone).phone;
  const lookup = useQuery({
    queryKey: ["customer-by-phone", wholeNumber],
    queryFn: async () =>
      (await call(api.GET("/api/v1/customers", { params: { query: { phone: wholeNumber } } }))).items[0] ?? null,
    enabled: !!wholeNumber,
    meta: { showsOwnErrors: true },
  });
  const found = wholeNumber ? lookup.data : undefined;

  // A customer we know: their address and place, unless something is already typed there.
  const creating = !order;
  useEffect(() => {
    if (found && creating) {
      setAddress((typed) => typed || found.address || "");
      setPlace((chosen) =>
        chosen.districtId !== null
          ? chosen
          : { districtId: found.district?.id ?? null, areaId: found.area?.id ?? null },
      );
    }
  }, [found, creating]);

  const quote = useQuery({
    queryKey: ["delivery-quote", place.districtId, place.areaId],
    queryFn: () =>
      call(
        api.GET("/api/v1/delivery-zones/quote", {
          params: { query: { districtId: place.districtId ?? undefined, areaId: place.areaId ?? undefined } },
        }),
      ),
  });
  const quoted = quote.data?.charge;
  useEffect(() => {
    if (quoted !== undefined && !chargeTouched) {
      setCharge(takaInput(quoted));
    }
  }, [quoted, chargeTouched]);

  function addItems(items: PickedItem[]) {
    setLines((current) => [
      ...current,
      ...items.map((item) => ({
        variantId: item.variantId,
        name: item.name,
        label: item.label,
        available: item.available,
        quantity: "1",
        price: takaInput(item.price),
      })),
    ]);
    setAdding(false);
  }

  const changeLine = (variantId: string, change: Partial<Line>) =>
    setLines((current) => current.map((line) => (line.variantId === variantId ? { ...line, ...change } : line)));

  const subtotal = lines.reduce(
    (sum, line) => sum + (parseCount(line.quantity) ?? 0) * (parseTaka(line.price) ?? 0),
    0,
  );
  const total = subtotal - (amountOf(discount) ?? 0) + (amountOf(charge) ?? 0);
  const paid = order ? order.advancePaid : (amountOf(advance) ?? 0);
  const cod = total - paid;

  function check(confirming: boolean): Errors {
    const wrong: Errors = {};
    if (!customerFixed) {
      if (!phone.trim()) {
        wrong.phone = t("field.required");
      }
      if (!found && !lookup.isFetching && !name.trim()) {
        wrong.name = t("field.required");
      }
    }
    if (confirming && !address.trim()) {
      wrong.address = t("orders.neededToConfirm");
    }
    if (confirming && place.districtId === null) {
      wrong.district = t("orders.neededToConfirm");
    }
    if (lines.length === 0) {
      wrong.items = t("orders.noItems");
    }
    for (const line of lines) {
      const quantity = parseCount(line.quantity);
      if (quantity === null || quantity < 1 || quantity > MAX_QUANTITY) {
        wrong[`quantity:${line.variantId}`] = t("field.countInvalid");
      }
      if (parseTaka(line.price) === null) {
        wrong[`price:${line.variantId}`] = t("field.amountInvalid");
      }
    }
    if (amountOf(discount) === null) {
      wrong.discount = t("field.amountInvalid");
    }
    if (amountOf(charge) === null) {
      wrong.charge = t("field.amountInvalid");
    }
    if (amountOf(advance) === null) {
      wrong.advance = t("field.amountInvalid");
    }
    return wrong;
  }

  const delivery = () => ({
    address: address.trim(),
    districtId: place.districtId ?? undefined,
    areaId: place.areaId ?? undefined,
    name: recipientName.trim() || undefined,
    phone: latinDigits(recipientPhone).trim() || undefined,
  });

  const newCustomer = () => ({
    name: name.trim(),
    phone: latinDigits(phone).trim(),
    address: address.trim() || undefined,
    districtId: place.districtId ?? undefined,
    areaId: place.areaId ?? undefined,
  });

  const items = () =>
    lines.map((line) => ({
      variantId: line.variantId,
      quantity: parseCount(line.quantity) ?? 0,
      unitPrice: parseTaka(line.price) ?? 0,
    }));

  function create() {
    const advanceAmount = amountOf(advance) ?? 0;
    return call(
      api.POST("/api/v1/orders", {
        headers: { "Idempotency-Key": idempotencyKey.current },
        body: {
          ...(found ? { customerId: found.id } : { customer: newCustomer() }),
          delivery: delivery(),
          items: items(),
          discount: amountOf(discount) ?? 0,
          deliveryCharge: charge.trim() ? (parseTaka(charge) ?? 0) : undefined,
          advancePaid: advanceAmount,
          advanceMethod: advanceAmount > 0 ? advanceMethod : undefined,
          advanceReference: advanceAmount > 0 ? advanceReference.trim() || undefined : undefined,
          note: note.trim() || undefined,
        },
      }),
    );
  }

  function update(current: Order) {
    // On a draft the customer can still change, by number; the recipient follows unless given.
    const customerChanged = !customerFixed && found !== undefined && found?.id !== current.customer.id;
    const change = customerChanged ? (found ? { customerId: found.id } : { customer: newCustomer() }) : {};
    const recipient = delivery();
    return call(
      api.PATCH("/api/v1/orders/{id}", {
        params: { path: { id: current.id } },
        body: {
          ...change,
          delivery: {
            ...recipient,
            name: recipient.name ?? (customerChanged ? undefined : current.customer.name),
            phone: recipient.phone ?? (customerChanged ? undefined : current.customer.phone),
          },
          items: items(),
          discount: amountOf(discount) ?? 0,
          deliveryCharge: charge.trim() ? (parseTaka(charge) ?? 0) : undefined,
          note: note.trim(),
          version: current.version,
        },
      }),
    );
  }

  const save = useMutation({
    mutationFn: async (confirming: boolean) => {
      const saved = await (order ? update(order) : create());
      orderChanged(queryClient, saved);
      if (!confirming) {
        return { saved };
      }
      // The order is safe by now. If it can't be confirmed, it stays a draft, and its page says why.
      try {
        const confirmed = await call(
          api.POST("/api/v1/orders/{id}/confirm", {
            params: { path: { id: saved.id } },
            body: { version: saved.version },
          }),
        );
        return { saved: confirmed };
      } catch (error) {
        return { saved, notConfirmed: error };
      }
    },
    meta: { showsOwnErrors: true },
    onSuccess: async ({ saved, notConfirmed }) => {
      orderChanged(queryClient, saved);
      if (notConfirmed) {
        toast.error(t("orders.savedNotConfirmed", { reason: orderErrorText(notConfirmed) }), { duration: 10_000 });
      } else {
        toast.success(t(saved.status === "DRAFT" ? "orders.savedDraft" : "orders.saved"));
      }
      await navigate({ to: "/orders/$orderId", params: { orderId: saved.id }, replace: true });
    },
    onError: (error) => {
      // The server answered: this attempt is finished, so a new one is a new order.
      if (error instanceof ApiError) {
        idempotencyKey.current = newIdempotencyKey();
      }
      const action = errorAction(error);
      if (action.kind === "upgrade") {
        return;
      }
      const onFields: Errors = {};
      const others: string[] = [];
      if (action.kind === "fields") {
        for (const field of action.fields) {
          const target = SERVER_FIELDS[field.field];
          if (target) {
            onFields[target] = field.message;
          } else {
            others.push(field.message);
          }
        }
      } else {
        others.push(orderErrorText(error));
      }
      setErrors(onFields);
      setProblem(others.join(" ") || undefined);
      if (order) {
        void queryClient.invalidateQueries({ queryKey: ["order", order.id] });
      }
    },
  });

  function submit(confirming: boolean) {
    if (save.isPending) {
      return;
    }
    const problems = check(confirming);
    setErrors(problems);
    setProblem(undefined);
    if (Object.keys(problems).length === 0) {
      save.mutate(confirming);
    }
  }

  const primaryConfirms = order?.status !== "CONFIRMED";
  const primaryLabel = t(primaryConfirms ? "orders.saveConfirm" : "orders.saveChanges");
  const codText = format.taka(Math.max(cod, 0));
  return (
    <div ref={box} className="flex w-full flex-col gap-4">
      {/* On phones the title and the customer share one dark header. */}
      <div className="max-md:relative max-md:-mx-4 max-md:-mt-4 max-md:flex max-md:flex-col max-md:gap-2.5 max-md:bg-ledger max-md:pt-1 max-md:pr-4.5 max-md:pb-10.5 max-md:pl-6.5 max-md:text-white md:contents">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:hidden" />
        <div className="max-md:[&_a]:text-marigold-text">{heading}</div>
        <section
          aria-labelledby="order-form-customer"
          className="flex flex-col gap-2.5 md:relative md:overflow-hidden md:rounded-2xl md:bg-ledger md:pt-4.5 md:pr-6 md:pb-5 md:pl-14 md:text-white"
        >
          <div aria-hidden="true" className="absolute inset-y-0 left-7 hidden w-0.5 bg-marigold md:block" />
          <h2 id="order-form-customer" className="text-[17px] font-bold md:text-[19px]">
            {t("orders.customer")}
          </h2>
          {customerFixed && order ? (
            <div className={KNOWN_CUSTOMER}>
              <span className="text-[17px] font-bold">{order.customer.name}</span>
              <span className="text-[15px] text-ledger-text">{format.phone(order.customer.phone)}</span>
            </div>
          ) : (
            <div className="flex flex-col gap-2.5 md:flex-row md:items-start md:gap-4">
              <div className={cn(ON_DARK_FIELD, "md:flex-[1_1_0%] [&_input]:text-lg [&_input]:font-semibold")}>
                <Field
                  label={t("customers.phone")}
                  type="tel"
                  autoComplete="off"
                  value={phone}
                  error={errors.phone}
                  onChange={(event) => {
                    setPhone(event.target.value);
                    if (order) {
                      setName("");
                    }
                  }}
                />
              </div>
              {found && (
                <div role="status" className={cn(KNOWN_CUSTOMER, "md:mt-7.5 md:flex-[2_1_0%]")}>
                  <Check aria-hidden="true" className="size-5 flex-none text-marigold-text" strokeWidth={2.5} />
                  <span className="text-[17px] font-bold">{found.name}</span>
                  <span className="text-[15px] text-ledger-text">
                    {t("orders.form.returning")} ·{" "}
                    {t("customers.orderCount", { count: format.number(found.orderCount) })}
                  </span>
                </div>
              )}
              {!found && !lookup.isFetching && (
                <div className={cn(ON_DARK_FIELD, "md:flex-[2_1_0%] [&_input]:text-[17px]")}>
                  <Field
                    label={t("customers.name")}
                    autoComplete="off"
                    value={name}
                    maxLength={200}
                    error={errors.name}
                    onChange={(event) => setName(event.target.value)}
                  />
                </div>
              )}
            </div>
          )}
        </section>
      </div>

      <div className="flex flex-col gap-3.5 max-md:relative max-md:-mx-4 max-md:-mt-7.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-3.5 md:gap-4">
        {!canWrite && (
          <Alert variant="destructive">
            <AlertDescription>{t("orders.readOnlyForm")}</AlertDescription>
          </Alert>
        )}
        {/* Two columns from a laptop up, where the money stays in view beside what it adds up. */}
        <div className="flex flex-col gap-3.5 lg:flex-row lg:items-start lg:gap-4">
          <div className="flex min-w-0 flex-col gap-3.5 lg:flex-[7_1_0%] lg:gap-4">
            <section aria-labelledby="order-form-place" className={CARD}>
              <h2 id="order-form-place" className="text-[19px] font-bold">
                {t("orders.deliverTo")}
              </h2>
              <AreaField
                label={t("orders.address")}
                value={address}
                maxLength={500}
                error={errors.address}
                onChange={(event) => setAddress(event.target.value)}
              />
              <PlacePicker
                value={place}
                districtLabel={t("orders.district")}
                districtError={errors.district}
                sideBySide
                onChange={(value) => {
                  setPlace(value);
                  setChargeTouched(false);
                }}
              />
              <details open={recipientOpen || undefined} className="group">
                <summary className="flex min-h-11 w-fit cursor-pointer list-none items-center gap-1 text-[15px] font-semibold text-primary [&::-webkit-details-marker]:hidden">
                  {t("orders.otherRecipient")}
                  <ChevronDown aria-hidden="true" className="size-4.5 group-open:rotate-180" />
                </summary>
                <div className="grid gap-3 pt-2 md:grid-cols-2">
                  <Field
                    label={t("orders.recipientName")}
                    autoComplete="off"
                    value={recipientName}
                    error={errors.recipientName}
                    onChange={(event) => setRecipientName(event.target.value)}
                  />
                  <Field
                    label={t("orders.recipientPhone")}
                    type="tel"
                    autoComplete="off"
                    value={recipientPhone}
                    error={errors.recipientPhone}
                    onChange={(event) => setRecipientPhone(event.target.value)}
                  />
                </div>
              </details>
            </section>

            <section aria-labelledby="order-form-items" className={CARD}>
              <div className="flex flex-wrap items-center gap-x-2.5 gap-y-2">
                <h2 id="order-form-items" className="text-[19px] font-bold">
                  {t("orders.items")}
                </h2>
                {lines.length > 0 && (
                  <span className="text-[15px] text-muted-foreground">
                    {t("orders.list.itemCount", { count: format.number(lines.length) })}
                  </span>
                )}
                <Button
                  variant="outline"
                  className="ml-auto h-11 rounded-[10px] border-[1.5px] border-primary px-3 text-[15px] font-bold text-primary hover:text-primary md:px-3.5"
                  onClick={() => setAdding(true)}
                >
                  <Plus aria-hidden="true" />
                  {t("orders.picker.add")}
                </Button>
              </div>
              {adding && (
                <Suspense fallback={null}>
                  <ItemsDialog
                    taken={lines.map((line) => line.variantId)}
                    onDone={addItems}
                    onClose={() => setAdding(false)}
                  />
                </Suspense>
              )}
              {lines.length === 0 &&
                (errors.items ? (
                  <p
                    data-problem
                    tabIndex={-1}
                    className="rounded-[10px] border-[1.5px] border-dashed border-destructive px-3.5 py-4 text-center text-[15px] font-semibold text-destructive outline-none"
                  >
                    {errors.items}
                  </p>
                ) : (
                  <p className="rounded-[10px] border-[1.5px] border-dashed px-3.5 py-4 text-center text-[15px] text-muted-foreground">
                    {t("orders.form.noLines")}
                  </p>
                ))}
              {lines.length > 0 && (
                <div>
                  <div
                    aria-hidden="true"
                    className={cn(LINE_COLUMNS, "hidden pb-1.5 text-sm font-semibold text-muted-foreground md:grid")}
                  >
                    <span>{t("orders.items")}</span>
                    <span>{t("orders.quantity")}</span>
                    <span>{t("orders.unitPrice")}</span>
                    <span className="text-right">{t("orders.total")}</span>
                  </div>
                  <ul className="flex flex-col">
                    {lines.map((line) => (
                      <LineRow
                        key={line.variantId}
                        line={line}
                        errors={errors}
                        onChange={(change) => changeLine(line.variantId, change)}
                        onRemove={() => setLines(lines.filter((other) => other !== line))}
                      />
                    ))}
                  </ul>
                </div>
              )}
            </section>

            <section aria-label={t("orders.note")} className={CARD}>
              <AreaField
                label={t("orders.note")}
                hint={t("orders.noteHint")}
                maxLength={1000}
                value={note}
                error={errors.note}
                onChange={(event) => setNote(event.target.value)}
              />
            </section>
          </div>

          <div className="flex min-w-0 flex-col gap-3.5 lg:sticky lg:top-8 lg:min-w-80 lg:flex-[4_1_0%] md:gap-2.5 md:rounded-[14px] md:border md:bg-card md:px-5 md:pt-4 md:pb-5">
            <section
              aria-labelledby="order-form-money"
              className="flex flex-col gap-2.5 max-md:rounded-[14px] max-md:border max-md:bg-card max-md:p-3.5"
            >
              <h2 id="order-form-money" className="mb-0.5 text-[19px] font-bold">
                {t("orders.money")}
              </h2>
              <Amount label={t("orders.subtotal")} value={format.taka(subtotal)} />
              <MoneyField
                label={t("orders.deliveryCharge")}
                value={charge}
                error={errors.charge}
                hint={!chargeTouched && quote.data ? quote.data.zoneName : undefined}
                onChange={(event) => {
                  setCharge(event.target.value);
                  setChargeTouched(true);
                }}
              />
              <MoneyField
                label={t("orders.discount")}
                value={discount}
                error={errors.discount}
                onChange={(event) => setDiscount(event.target.value)}
              />
              <Amount label={t("orders.total")} value={format.taka(total)} strong />
              {order && paid > 0 && <Amount label={t("orders.advancePaid")} value={format.taka(paid)} />}
              {!order && (
                <MoneyField
                  label={t("orders.advance")}
                  value={advance}
                  error={errors.advance}
                  onChange={(event) => setAdvance(event.target.value)}
                />
              )}
              {!order && (amountOf(advance) ?? 0) > 0 && (
                <div className="grid gap-2.5 rounded-[10px] bg-muted p-2.5 md:grid-cols-2">
                  <SelectBox
                    label={t("orders.paidBy")}
                    value={advanceMethod}
                    onChange={(event) => setAdvanceMethod(event.target.value as PaymentMethod)}
                  >
                    {PAYMENT_METHODS.map((method) => (
                      <option key={method} value={method}>
                        {t(`paymentMethod.${method}`)}
                      </option>
                    ))}
                  </SelectBox>
                  <Field
                    label={t("orders.reference")}
                    autoComplete="off"
                    maxLength={100}
                    value={advanceReference}
                    onChange={(event) => setAdvanceReference(event.target.value)}
                  />
                </div>
              )}
              {/* On phones this is in the bar that stays on screen. */}
              <div className="flex items-center justify-between gap-2.5 rounded-[10px] bg-accent p-3 text-accent-foreground max-md:hidden">
                <span className="text-[17px] font-bold">{t("orders.cod")}</span>
                <span className="text-2xl font-bold whitespace-nowrap">{codText}</span>
              </div>
            </section>
            {problem && (
              <Alert variant="destructive">
                <AlertDescription>{problem}</AlertDescription>
              </Alert>
            )}
            <Button
              className="mt-1 h-13 rounded-[10px] text-lg font-bold max-md:hidden"
              disabled={save.isPending || !canWrite}
              onClick={() => submit(primaryConfirms)}
            >
              {primaryLabel}
            </Button>
            {primaryConfirms && (
              <Button
                variant="outline"
                className="h-12 rounded-[10px] border-[1.5px] text-base font-semibold md:h-11.5"
                disabled={save.isPending || !canWrite}
                onClick={() => submit(false)}
              >
                {t("orders.saveDraft")}
              </Button>
            )}
          </div>
        </div>
      </div>

      {/* Above the app's menu. The phone's keyboard opens over both of them. */}
      <div className="sticky bottom-[calc(68px+env(safe-area-inset-bottom))] z-10 -mx-4 -mb-11 flex flex-col gap-2 bg-ledger px-3.5 pt-2.5 pb-3 text-white md:hidden">
        <p className="flex items-baseline justify-between gap-2.5">
          <span className="text-[15px] font-semibold text-ledger-text">{t("orders.cod")}</span>
          <span className="text-[22px] font-bold whitespace-nowrap text-marigold-text">{codText}</span>
        </p>
        <button
          type="button"
          className="min-h-12.5 cursor-pointer rounded-[10px] bg-marigold-text px-3 text-lg font-bold text-ledger hover:bg-marigold focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-marigold-text disabled:opacity-60"
          disabled={save.isPending || !canWrite}
          onClick={() => submit(primaryConfirms)}
        >
          {primaryLabel}
        </button>
      </div>
    </div>
  );
}

function Amount({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <p className={cn("flex justify-between gap-3", strong && "border-t pt-2.5 text-[17px] font-bold")}>
      <span>{label}</span>
      <span className={cn(!strong && "font-semibold")}>{value}</span>
    </p>
  );
}

/** One line of the sum: what it is on the left, the amount to fill in on the right. */
function MoneyField({
  label,
  hint,
  error,
  ...input
}: { label: string; hint?: string; error?: string } & ComponentProps<typeof Input>) {
  const id = useId();
  return (
    <div className="grid grid-cols-[minmax(0,1fr)_112px] items-center gap-x-3 gap-y-1">
      <span className="min-w-0">
        <Label htmlFor={id} className="text-base leading-normal font-normal">
          {label}
        </Label>
        {hint && (
          <span id={`${id}-hint`} className="block text-sm text-muted-foreground">
            {hint}
          </span>
        )}
      </span>
      <Input
        id={id}
        inputMode="decimal"
        aria-invalid={error ? true : undefined}
        aria-describedby={[hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(" ") || undefined}
        className="text-right"
        {...input}
      />
      {error && (
        <p id={`${id}-error`} className="col-span-2 text-right text-sm text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}

function LineRow({
  line,
  errors,
  onChange,
  onRemove,
}: {
  line: Line;
  errors: Errors;
  onChange: (change: Partial<Line>) => void;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const lineName = [line.name, line.label].filter(Boolean).join(" · ");
  const quantity = parseCount(line.quantity) ?? 0;
  const price = parseTaka(line.price) ?? 0;
  const quantityError = errors[`quantity:${line.variantId}`];
  const step = (by: number) => onChange({ quantity: String(Math.max(1, quantity + by)) });
  return (
    <li>
      <fieldset
        aria-label={lineName}
        className={cn(
          LINE_COLUMNS,
          "relative grid grid-cols-[auto_minmax(0,1fr)] items-end gap-x-2.5 gap-y-2 border-t py-3 md:items-center md:py-2.5",
        )}
      >
        <span className="col-span-2 min-w-0 pr-10 md:col-span-1 md:pr-0">
          <span className="block font-semibold break-words">{line.name}</span>
          {line.label && <span className="block text-sm text-muted-foreground">{line.label}</span>}
          {line.available !== null && quantity > line.available && (
            <span className="block text-sm font-semibold text-warning-foreground">
              {line.available > 0
                ? t("orders.onlyAvailable", { count: format.number(line.available) })
                : t("orders.outOfStock")}
            </span>
          )}
        </span>
        <div className="flex flex-col gap-1">
          <span className="text-sm font-semibold md:hidden">{t("orders.quantity")}</span>
          <div className="flex gap-1">
            <Button
              variant="outline"
              size="icon"
              className="size-12 flex-none rounded-[10px] md:size-11"
              aria-label={t("orders.fewer")}
              disabled={quantity <= 1}
              onClick={() => step(-1)}
            >
              <Minus aria-hidden="true" />
            </Button>
            <Input
              inputMode="numeric"
              aria-label={t("orders.quantity")}
              aria-invalid={quantityError ? true : undefined}
              className="h-12 w-11 flex-none px-0.5 text-center font-semibold md:h-11"
              value={line.quantity}
              onChange={(event) => onChange({ quantity: event.target.value })}
            />
            <Button
              variant="outline"
              size="icon"
              className="size-12 flex-none rounded-[10px] md:size-11"
              aria-label={t("orders.more")}
              onClick={() => step(1)}
            >
              <Plus aria-hidden="true" />
            </Button>
          </div>
          {quantityError && <p className="text-sm text-destructive">{quantityError}</p>}
        </div>
        <div className="[&>div]:gap-1 max-md:[&_label]:text-sm md:[&_label]:sr-only">
          <Field
            label={t("orders.unitPrice")}
            inputMode="decimal"
            value={line.price}
            error={errors[`price:${line.variantId}`]}
            onChange={(event) => onChange({ price: event.target.value })}
          />
        </div>
        <p className="col-span-2 flex items-baseline justify-between gap-2.5 md:col-span-1 md:justify-end">
          <span className="text-[15px] text-muted-foreground md:hidden">{t("orders.total")}</span>
          <span className="font-bold">{format.taka(quantity * price)}</span>
        </p>
        <Button
          variant="ghost"
          size="icon"
          className="absolute top-1.5 right-0 size-11 text-muted-foreground md:static"
          aria-label={t("orders.removeItem", { name: lineName })}
          onClick={onRemove}
        >
          <X aria-hidden="true" />
        </Button>
      </fieldset>
    </li>
  );
}
