import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { useEffect, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import { errorText } from "@/api/query";
import type { components } from "@/api/schema";
import { uploadImport } from "@/api/uploads";
import { PageHeader } from "@/components/layout/PageHeader";
import { Section } from "@/components/layout/Section";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { BackToProducts } from "./LowStockPage";

type Job = components["schemas"]["ImportJob"];
type Column = components["schemas"]["ImportColumnView"];
type Field = NonNullable<Column["field"]>;

const route = getRouteApi("/_app/products/import");

const FINISHED = ["DONE", "FAILED"];
const FIELDS: Field[] = [
  "NAME",
  "SKU",
  "PRICE",
  "COST_PRICE",
  "STOCK",
  "CATEGORY",
  "DESCRIPTION",
  "LOW_STOCK_THRESHOLD",
  "OPTION",
];

const jobQuery = (id: string) => ({
  queryKey: ["import", id],
  queryFn: () => call(api.GET("/api/v1/imports/{id}", { params: { path: { id } } })),
  // Follows the import while it runs in the background.
  refetchInterval: (query: { state: { data?: Job } }) => {
    const status = query.state.data?.status;
    return status === "QUEUED" || status === "RUNNING" ? 1500 : false;
  },
});

/** Upload a sheet, check how it was read, import it, and follow it to the end. */
export function ImportPage() {
  const { t } = useTranslation();
  const { job: jobId } = route.useSearch();
  const { data: job, isError } = useQuery({
    ...jobQuery(jobId ?? ""),
    enabled: !!jobId,
    meta: { showsOwnErrors: true },
  });
  return (
    <div className="flex max-w-3xl flex-col gap-5">
      <div>
        <BackToProducts />
        <PageHeader title={t("imports.title")} />
      </div>
      {!jobId && <UploadStep />}
      {jobId && isError && (
        <>
          <Alert variant="destructive">
            <AlertDescription>{t("imports.notFound")}</AlertDescription>
          </Alert>
          <UploadStep />
        </>
      )}
      {job?.status === "MAPPING" && <CheckStep job={job} />}
      {job && (job.status === "QUEUED" || job.status === "RUNNING") && <RunningStep job={job} />}
      {job && FINISHED.includes(job.status) && <FinishedStep job={job} />}
    </div>
  );
}

function UploadStep() {
  const { t } = useTranslation();
  const navigate = route.useNavigate();
  const input = useRef<HTMLInputElement>(null);
  const upload = useMutation({
    mutationFn: uploadImport,
    meta: { showsOwnErrors: true },
    onSuccess: (job) => void navigate({ search: { job: job.id } }),
  });
  return (
    <Section title={t("imports.choose")}>
      <p>{t("imports.step1")}</p>
      {upload.error && (
        <Alert variant="destructive">
          <AlertDescription>{errorText(upload.error)}</AlertDescription>
        </Alert>
      )}
      <input
        ref={input}
        type="file"
        accept=".xlsx,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv"
        aria-label={t("imports.file")}
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
      <div className="flex flex-wrap gap-2">
        <Button disabled={upload.isPending} onClick={() => input.current?.click()}>
          {t("imports.choose")}
        </Button>
        <Button variant="outline" asChild>
          {/* The server sends it as a download, in the account's language. */}
          <a href="/api/v1/imports/template.xlsx" download>
            {t("imports.template")}
          </a>
        </Button>
      </div>
      <p className="text-sm text-muted-foreground">{t("imports.fileHint")}</p>
    </Section>
  );
}

function CheckStep({ job }: { job: Job }) {
  const { t } = useTranslation();
  const format = useFormat();
  const navigate = route.useNavigate();
  const queryClient = useQueryClient();
  const applied = (updated: Job) => {
    queryClient.setQueryData(jobQuery(job.id).queryKey, updated);
    void queryClient.invalidateQueries({ queryKey: ["import-preview", job.id] });
  };
  const commit = useMutation({
    mutationFn: () => call(api.POST("/api/v1/imports/{id}/commit", { params: { path: { id: job.id } } })),
    meta: { showsOwnErrors: true },
    onSuccess: (updated) => queryClient.setQueryData(jobQuery(job.id).queryKey, updated),
  });
  const { totals } = job;
  const missing = job.missingColumns.map((field) => t(`imports.field.${field}`));
  return (
    <>
      <Section title={t("imports.checkTitle")}>
        <p className="text-sm font-medium break-all">{job.fileName}</p>
        {missing.length > 0 && (
          <Alert variant="destructive">
            <AlertDescription>{t("imports.missing", { fields: missing.join(", ") })}</AlertDescription>
          </Alert>
        )}
        <ul className="flex flex-wrap gap-x-4 gap-y-1">
          <li>{t("imports.rows", { count: format.number(totals.rows) })}</li>
          <li className="font-semibold text-primary">{t("imports.valid", { count: format.number(totals.valid) })}</li>
          {totals.invalid > 0 && (
            <li className="font-semibold text-destructive">
              {t("imports.invalid", { count: format.number(totals.invalid) })}
            </li>
          )}
          <li>{t("imports.create", { count: format.number(totals.create) })}</li>
          {totals.update > 0 && <li>{t("imports.update", { count: format.number(totals.update) })}</li>}
        </ul>
        {job.newCategories.length > 0 && (
          <p className="text-sm">{t("imports.newCategories", { names: job.newCategories.join(", ") })}</p>
        )}
        <PreviewRows job={job} />
        {totals.invalid > 0 && missing.length === 0 && (
          <div className="flex flex-col items-start gap-1">
            <ErrorsLink job={job} />
            <p className="text-sm text-muted-foreground">{t("imports.errorsHint")}</p>
          </div>
        )}
        {commit.error && (
          <Alert variant="destructive">
            <AlertDescription>{errorText(commit.error)}</AlertDescription>
          </Alert>
        )}
        {totals.invalid > 0 && totals.valid > 0 && (
          <p className="text-sm font-medium">{t("imports.skipNote", { count: format.number(totals.invalid) })}</p>
        )}
        <div className="flex flex-wrap gap-2">
          <Button disabled={totals.valid === 0 || commit.isPending} onClick={() => commit.mutate()}>
            {t("imports.commit", { count: format.number(totals.valid) })}
          </Button>
          <Button variant="outline" onClick={() => void navigate({ search: {} })}>
            {t("imports.another")}
          </Button>
        </div>
      </Section>
      <Section title={t("imports.columnsTitle")}>
        <p className="text-sm text-muted-foreground">{t("imports.columnsHint")}</p>
        <ColumnsEditor job={job} onApplied={applied} />
      </Section>
    </>
  );
}

function ErrorsLink({ job }: { job: Job }) {
  const { t } = useTranslation();
  return (
    <Button variant="outline" asChild>
      <a href={`/api/v1/imports/${job.id}/errors.csv`} download>
        {t("imports.errorsFile")}
      </a>
    </Button>
  );
}

/** Each change is sent at once: the server checks every row again and answers with the new totals. */
function ColumnsEditor({ job, onApplied }: { job: Job; onApplied: (job: Job) => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const headerId = useId();
  const remap = useMutation({
    mutationFn: ({ columns, headerRow }: { columns: Column[]; headerRow?: number }) =>
      call(
        api.PUT("/api/v1/imports/{id}/mapping", {
          params: { path: { id: job.id } },
          body: {
            headerRow,
            columns: columns.flatMap((column) =>
              column.field
                ? [{ index: column.index, field: column.field, optionName: column.optionName ?? undefined }]
                : [],
            ),
          },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: onApplied,
  });
  const change = (index: number, update: Partial<Column>) =>
    remap.mutate({
      columns: job.columns.map((column) => (column.index === index ? { ...column, ...update } : column)),
    });
  return (
    <div className="flex flex-col gap-4">
      <div className="flex w-fit flex-col gap-1.5">
        <Label htmlFor={headerId} className="text-[15px] leading-normal font-semibold">
          {t("imports.headerRow")}
        </Label>
        <select
          id={headerId}
          className="h-12 rounded-lg border border-input bg-card px-2 md:h-11"
          value={job.headerRow}
          disabled={remap.isPending}
          onChange={(event) => remap.mutate({ columns: [], headerRow: Number(event.target.value) })}
        >
          {Array.from({ length: Math.max(10, job.headerRow) }, (_, i) => i + 1).map((row) => (
            <option key={row} value={row}>
              {format.number(row)}
            </option>
          ))}
        </select>
      </div>
      {remap.error && (
        <Alert variant="destructive">
          <AlertDescription>{errorText(remap.error)}</AlertDescription>
        </Alert>
      )}
      <ul className="flex flex-col">
        {job.columns.map((column) => (
          <ColumnRow
            key={column.index}
            column={column}
            disabled={remap.isPending}
            onField={(field) =>
              change(column.index, {
                field,
                optionName: field === "OPTION" ? (column.optionName ?? column.header) : null,
              })
            }
            onOptionName={(optionName) => change(column.index, { optionName })}
          />
        ))}
      </ul>
    </div>
  );
}

function ColumnRow({
  column,
  disabled,
  onField,
  onOptionName,
}: {
  column: Column;
  disabled: boolean;
  onField: (field: Field | null) => void;
  onOptionName: (name: string) => void;
}) {
  const { t } = useTranslation();
  const id = useId();
  const [optionName, setOptionName] = useState(column.optionName ?? "");
  useEffect(() => setOptionName(column.optionName ?? ""), [column.optionName]);
  return (
    <li className="flex flex-col gap-2 border-t py-3 first:border-t-0 first:pt-0 md:flex-row md:items-start md:justify-between">
      <div className="min-w-0">
        <Label htmlFor={id} className="text-[15px] leading-normal font-semibold break-words">
          {t("imports.column", { letter: column.letter, header: column.header || t("imports.noHeader") })}
        </Label>
        {column.samples.length > 0 && (
          <p className="text-sm break-words text-muted-foreground">
            {t("imports.samples", { samples: column.samples.join(", ") })}
          </p>
        )}
      </div>
      <div className="flex flex-none flex-col gap-2 md:w-64">
        <select
          id={id}
          className="h-12 w-full rounded-lg border border-input bg-card px-2 md:h-11"
          value={column.field ?? ""}
          disabled={disabled}
          onChange={(event) => onField((event.target.value || null) as Field | null)}
        >
          <option value="">{t("imports.field.IGNORE")}</option>
          {FIELDS.map((field) => (
            <option key={field} value={field}>
              {t(`imports.field.${field}`)}
            </option>
          ))}
        </select>
        {column.field === "OPTION" && (
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${id}-option`} className="text-sm font-normal text-muted-foreground">
              {t("imports.optionName")}
            </Label>
            <Input
              id={`${id}-option`}
              value={optionName}
              maxLength={30}
              disabled={disabled}
              onChange={(event) => setOptionName(event.target.value)}
              onBlur={() => {
                if (optionName.trim() && optionName !== column.optionName) {
                  onOptionName(optionName.trim());
                }
              }}
            />
          </div>
        )}
      </div>
    </li>
  );
}

/** What the sheet will do, row by row. Problems first when there are any. */
function PreviewRows({ job }: { job: Job }) {
  const { t } = useTranslation();
  const format = useFormat();
  const [choice, setChoice] = useState<boolean>();
  // Without the required columns nothing can be imported, but no single row is at fault.
  const complete = job.missingColumns.length === 0;
  const onlyProblems = complete && (choice ?? job.totals.invalid > 0);
  const { data: preview } = useQuery({
    queryKey: ["import-preview", job.id, onlyProblems],
    queryFn: () =>
      call(
        api.GET("/api/v1/imports/{id}/preview", {
          params: { path: { id: job.id }, query: { invalidOnly: onlyProblems } },
        }),
      ),
  });
  return (
    <div className="flex flex-col gap-2">
      {complete && job.totals.invalid > 0 && (
        <fieldset className="flex w-fit overflow-hidden rounded-lg border border-input">
          {[true, false].map((value) => (
            <button
              key={String(value)}
              type="button"
              aria-pressed={onlyProblems === value}
              className={cn(
                "min-h-11 cursor-pointer px-3",
                onlyProblems === value ? "bg-foreground font-semibold text-background" : "bg-card text-foreground",
              )}
              onClick={() => setChoice(value)}
            >
              {value ? t("imports.onlyProblems") : t("imports.allRows")}
            </button>
          ))}
        </fieldset>
      )}
      {preview?.rows.length === 0 && <p className="text-muted-foreground">{t("imports.noRows")}</p>}
      <ul className="flex flex-col">
        {preview?.rows.map((row) => (
          <li key={row.row} className="border-t py-2 first:border-t-0">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <p className="min-w-0 font-medium break-words">
                <span className="text-sm font-normal text-muted-foreground">
                  {t("imports.row", { row: format.number(row.row) })} ·{" "}
                </span>
                {row.name || t("imports.unnamed")}
                {Object.keys(row.options).length > 0 && (
                  <span className="text-sm font-normal text-muted-foreground">
                    {" "}
                    ({Object.values(row.options).join(" / ")})
                  </span>
                )}
              </p>
              <p className="flex-none text-sm">
                {row.price !== null && <span className="font-semibold">{format.taka(row.price)}</span>}
                {row.action && row.errors.length === 0 && (
                  <span className="text-muted-foreground"> · {t(`imports.rowAction${row.action}`)}</span>
                )}
              </p>
            </div>
            {row.errors.map((error) => (
              <p key={`${error.column}-${error.message}`} className="text-sm text-destructive">
                {t("imports.errorAt", { column: error.column, field: error.field, message: error.message })}
              </p>
            ))}
          </li>
        ))}
      </ul>
      {!!preview?.rows.length && preview.rows.length >= 20 && (
        <p className="text-sm text-muted-foreground">{t("imports.firstRows")}</p>
      )}
    </div>
  );
}

function RunningStep({ job }: { job: Job }) {
  const { t } = useTranslation();
  const format = useFormat();
  const done = job.progress?.done ?? 0;
  const total = job.progress?.toImport ?? job.totals.valid;
  return (
    <Section title={t("imports.running")}>
      <progress
        aria-label={t("imports.running")}
        className="h-3 w-full accent-primary"
        value={done}
        max={Math.max(total, 1)}
      />
      <p role="status">{t("imports.progress", { done: format.number(done), total: format.number(total) })}</p>
      <p className="text-sm text-muted-foreground">{t("imports.runningHint")}</p>
    </Section>
  );
}

function FinishedStep({ job }: { job: Job }) {
  const { t } = useTranslation();
  const format = useFormat();
  const navigate = route.useNavigate();
  const queryClient = useQueryClient();
  // What the import changed shows in the lists straight away.
  // biome-ignore lint/correctness/useExhaustiveDependencies: once, when the import is seen to have finished.
  useEffect(() => {
    for (const queryKey of [["products"], ["categories"], ["low-stock"]]) {
      void queryClient.invalidateQueries({ queryKey });
    }
  }, [job.id]);
  const progress = job.progress;
  const failed = job.status === "FAILED";
  const left = (progress?.failed ?? 0) + (progress?.skipped ?? 0);
  return (
    <Section title={failed ? t("imports.failedTitle") : t("imports.doneTitle")}>
      {failed && <p>{t("imports.failedHint")}</p>}
      {progress && (
        <ul className="flex flex-wrap gap-x-4 gap-y-1">
          <li className="font-semibold text-primary">
            {t("imports.created", { count: format.number(progress.created) })}
          </li>
          <li>{t("imports.updated", { count: format.number(progress.updated) })}</li>
          <li>{t("imports.unchanged", { count: format.number(progress.unchanged) })}</li>
          {progress.failed > 0 && (
            <li className="font-semibold text-destructive">
              {t("imports.failed", { count: format.number(progress.failed) })}
            </li>
          )}
          {progress.skipped > 0 && (
            <li className="font-semibold text-destructive">
              {t("imports.skipped", { count: format.number(progress.skipped) })}
            </li>
          )}
        </ul>
      )}
      {left > 0 && (
        <div className="flex flex-col items-start gap-1">
          <ErrorsLink job={job} />
          <p className="text-sm text-muted-foreground">{t("imports.errorsHint")}</p>
        </div>
      )}
      <div className="flex flex-wrap gap-2">
        <Button asChild>
          <Link to="/products">{t("imports.viewProducts")}</Link>
        </Button>
        <Button variant="outline" onClick={() => void navigate({ search: {} })}>
          {t("imports.importAnother")}
        </Button>
      </div>
    </Section>
  );
}
