import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Field } from "@/components/form/Field";
import { Button } from "@/components/ui/button";
import { couriersQuery } from "./api";

/** The courier's name: those used before come first, one tap away. */
export function CourierField({
  value,
  onChange,
  error,
}: {
  value: string;
  onChange: (name: string) => void;
  error?: string;
}) {
  const { t } = useTranslation();
  const { data: couriers } = useQuery(couriersQuery);
  return (
    <div className="flex flex-col gap-2">
      <Field
        label={t("orders.courier")}
        autoComplete="off"
        maxLength={50}
        value={value}
        error={error}
        onChange={(event) => onChange(event.target.value)}
      />
      <div className="flex flex-wrap gap-2">
        {couriers?.slice(0, 6).map((name) => (
          <Button
            key={name}
            variant={name === value ? "default" : "outline"}
            size="sm"
            className="min-h-11"
            aria-pressed={name === value}
            onClick={() => onChange(name)}
          >
            {name}
          </Button>
        ))}
      </div>
    </div>
  );
}
