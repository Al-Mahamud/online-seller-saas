import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { Minus, Plus, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call, newIdempotencyKey } from "@/api/client";
import { ApiError, errorAction } from "@/api/errors";
import { AreaField, Field, SelectBox } from "@/components/form/Field";
import { Section } from "@/components/layout/Section";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useCanWrite } from "@/features/billing/status";
import { customerSearch } from "@/features/customers/api";
import { PlacePicker, type PlaceValue } from "@/features/delivery/PlacePicker";
import { latinDigits } from "@/lib/digits";
import { useFormat } from "@/lib/format";
import { parseCount, parseTaka, takaInput } from "@/lib/money";
import { type Order, orderChanged, orderErrorText, PAYMENT_METHODS, type PaymentMethod } from "./api";
import { ItemPicker, type PickedItem } from "./ItemPicker";

const MAX_QUANTITY = 10_000;

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
export function OrderForm({ order, initialPhone }: { order?: Order; initialPhone?: string }) {
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

  function addItem(item: PickedItem) {
    setLines((current) => {
      const existing = current.find((line) => line.variantId === item.variantId);
      if (existing) {
        const quantity = String((parseCount(existing.quantity) ?? 0) + 1);
        return current.map((line) => (line === existing ? { ...line, quantity, available: item.available } : line));
      }
      return [
        ...current,
        {
          variantId: item.variantId,
          name: item.name,
          label: item.label,
          available: item.available,
          quantity: "1",
          price: takaInput(item.price),
        },
      ];
    });
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

  return (
    <div ref={box} className="flex flex-col gap-4">
      {!canWrite && (
        <Alert variant="destructive">
          <AlertDescription>{t("orders.readOnlyForm")}</AlertDescription>
        </Alert>
      )}
      <Section title={t("orders.customer")}>
        {customerFixed ? (
          <p>
            <span className="font-semibold">{order?.customer.name}</span> · {order?.customer.phone}
          </p>
        ) : (
          <>
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
            {found && (
              <p role="status" className="rounded-lg bg-accent p-2 text-accent-foreground">
                <span className="font-semibold">{found.name}</span> ·{" "}
                {t("customers.orderCount", { count: format.number(found.orderCount) })}
              </p>
            )}
            {!found && !lookup.isFetching && (
              <Field
                label={t("customers.name")}
                autoComplete="off"
                value={name}
                maxLength={200}
                error={errors.name}
                onChange={(event) => setName(event.target.value)}
              />
            )}
          </>
        )}
      </Section>

      <Section title={t("orders.deliverTo")}>
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
          onChange={(value) => {
            setPlace(value);
            setChargeTouched(false);
          }}
        />
        <details open={recipientOpen || undefined}>
          <summary className="flex min-h-11 cursor-pointer items-center text-primary">
            {t("orders.otherRecipient")}
          </summary>
          <div className="flex flex-col gap-4 pt-2">
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
      </Section>

      <Section title={t("orders.items")}>
        <ItemPicker onPick={addItem} />
        {errors.items && (
          <p data-problem tabIndex={-1} className="text-sm text-destructive outline-none">
            {errors.items}
          </p>
        )}
        {lines.length > 0 && (
          <ul className="flex flex-col gap-3">
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
        )}
      </Section>

      <Section title={t("orders.money")}>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label={t("orders.deliveryCharge")}
            inputMode="decimal"
            value={charge}
            error={errors.charge}
            hint={!chargeTouched && quote.data ? quote.data.zoneName : undefined}
            onChange={(event) => {
              setCharge(event.target.value);
              setChargeTouched(true);
            }}
          />
          <Field
            label={t("orders.discount")}
            inputMode="decimal"
            value={discount}
            error={errors.discount}
            onChange={(event) => setDiscount(event.target.value)}
          />
          {!order && (
            <Field
              label={t("orders.advance")}
              inputMode="decimal"
              value={advance}
              error={errors.advance}
              onChange={(event) => setAdvance(event.target.value)}
            />
          )}
          {!order && (amountOf(advance) ?? 0) > 0 && (
            <>
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
            </>
          )}
        </div>
        <dl className="flex flex-col gap-1">
          <Amount label={t("orders.subtotal")} value={format.taka(subtotal)} />
          <Amount label={t("orders.total")} value={format.taka(total)} />
          {paid > 0 && <Amount label={t("orders.advancePaid")} value={format.taka(paid)} />}
          <Amount label={t("orders.cod")} value={format.taka(Math.max(cod, 0))} strong />
        </dl>
        <AreaField
          label={t("orders.note")}
          hint={t("orders.noteHint")}
          maxLength={1000}
          value={note}
          error={errors.note}
          onChange={(event) => setNote(event.target.value)}
        />
      </Section>

      {problem && (
        <Alert variant="destructive">
          <AlertDescription>{problem}</AlertDescription>
        </Alert>
      )}
      <div className="flex flex-col gap-2 sm:flex-row-reverse sm:justify-start">
        {order?.status === "CONFIRMED" ? (
          <Button disabled={save.isPending || !canWrite} onClick={() => submit(false)}>
            {t("orders.saveChanges")}
          </Button>
        ) : (
          <>
            <Button disabled={save.isPending || !canWrite} onClick={() => submit(true)}>
              {t("orders.saveConfirm")}
            </Button>
            <Button variant="outline" disabled={save.isPending || !canWrite} onClick={() => submit(false)}>
              {t("orders.saveDraft")}
            </Button>
          </>
        )}
      </div>
    </div>
  );
}

function Amount({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className={`flex justify-between gap-3 ${strong ? "border-t pt-2 text-lg font-bold" : ""}`}>
      <dt>{label}</dt>
      <dd>{value}</dd>
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
  const quantityError = errors[`quantity:${line.variantId}`];
  const step = (by: number) => onChange({ quantity: String(Math.max(1, quantity + by)) });
  return (
    <li>
      <fieldset aria-label={lineName} className="flex flex-col gap-2 rounded-lg border p-3">
        <div className="flex items-start justify-between gap-2">
          <span className="min-w-0">
            <span className="block font-semibold break-words">{lineName}</span>
            {line.available !== null && quantity > line.available && (
              <span className="block text-sm font-semibold text-warning-foreground">
                {line.available > 0
                  ? t("orders.onlyAvailable", { count: format.number(line.available) })
                  : t("orders.outOfStock")}
              </span>
            )}
          </span>
          <Button
            variant="ghost"
            size="icon"
            aria-label={t("orders.removeItem", { name: lineName })}
            onClick={onRemove}
          >
            <X aria-hidden="true" />
          </Button>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div className="flex flex-col gap-1.5">
            <span className="text-[15px] leading-normal font-semibold">{t("orders.quantity")}</span>
            <div className="flex gap-1">
              <Button
                variant="outline"
                size="icon"
                className="size-12 flex-none md:size-11"
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
                className="px-1 text-center"
                value={line.quantity}
                onChange={(event) => onChange({ quantity: event.target.value })}
              />
              <Button
                variant="outline"
                size="icon"
                className="size-12 flex-none md:size-11"
                aria-label={t("orders.more")}
                onClick={() => step(1)}
              >
                <Plus aria-hidden="true" />
              </Button>
            </div>
            {quantityError && <p className="text-sm text-destructive">{quantityError}</p>}
          </div>
          <Field
            label={t("orders.unitPrice")}
            inputMode="decimal"
            value={line.price}
            error={errors[`price:${line.variantId}`]}
            onChange={(event) => onChange({ price: event.target.value })}
          />
        </div>
      </fieldset>
    </li>
  );
}
