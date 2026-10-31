import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { useFormat } from "@/lib/format";

/** The server allows one code a minute to each destination. */
const COOLDOWN_SECONDS = 60;

export function ResendCode({ onResend, pending }: { onResend: () => void; pending: boolean }) {
  const { t } = useTranslation();
  const format = useFormat();
  const [seconds, setSeconds] = useState(COOLDOWN_SECONDS);

  useEffect(() => {
    if (seconds <= 0) {
      return;
    }
    const timer = setTimeout(() => setSeconds(seconds - 1), 1000);
    return () => clearTimeout(timer);
  }, [seconds]);

  return (
    <Button
      type="button"
      variant="link"
      className="self-start px-0 disabled:text-muted-foreground disabled:opacity-100"
      disabled={seconds > 0 || pending}
      onClick={() => {
        setSeconds(COOLDOWN_SECONDS);
        onResend();
      }}
    >
      {seconds > 0 ? t("auth.resendIn", { seconds: format.number(seconds) }) : t("auth.resend")}
    </Button>
  );
}
