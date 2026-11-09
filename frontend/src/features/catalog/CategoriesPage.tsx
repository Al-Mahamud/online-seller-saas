import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ArrowDown, ArrowUp, Check, ChevronRight, Ellipsis, Layers, Plus, Search, X } from "lucide-react";
import { lazy, type ReactNode, Suspense, useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod/mini";
import { api, call } from "@/api/client";
import { errorText } from "@/api/query";
import { ConfirmDialog } from "@/components/form/ConfirmDialog";
import { FormError, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { BackToMore } from "@/components/layout/PageHeader";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { useFormat } from "@/lib/format";
import { containsText } from "@/lib/search";
import { cn } from "@/lib/utils";
import { type CategoryNode, categoriesQuery, type Tag, tagsQuery } from "./api";
import { MENU_TRIGGER, type MenuAction } from "./categoryMenuParts";

const CategoryMenu = lazy(() => import("./CategoryMenu"));

const nameSchema = z.object({
  name: z.string().check(z.trim(), z.minLength(1, "field.required"), z.maxLength(80, "field.nameLong")),
});

const CARD = "flex flex-col gap-3 rounded-2xl border bg-card p-3.5 md:p-5";
const ICON =
  "flex size-9 flex-none cursor-pointer items-center justify-center rounded-lg text-[#3E4842] hover:bg-muted disabled:cursor-default disabled:text-[#C4C9C4] disabled:hover:bg-transparent";
const SOLID =
  "h-11.5 flex-none cursor-pointer rounded-[10px] bg-primary px-4 text-[15px] font-bold text-primary-foreground hover:bg-[#0a5c44] disabled:opacity-70";
const OUTLINE =
  "h-11 cursor-pointer rounded-[10px] border-[1.5px] border-input bg-card px-4 text-[15px] font-bold hover:bg-muted";

type Tab = "cats" | "tags";

/** A category with what it holds counted: its own products, and a top-level one's subcategories' too. */
const totalOf = (node: CategoryNode) =>
  node.productCount + node.children.reduce((sum, child) => sum + child.productCount, 0);

/**
 * Two levels of categories, and tags, side by side from 768 px and one at a time on a phone. A
 * product goes in one category and has any tags; each says how many products it holds.
 */
export function CategoriesPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const { data: tree } = useQuery(categoriesQuery);
  const { data: tags } = useQuery(tagsQuery);
  const [q, setQ] = useState("");
  const [tab, setTab] = useState<Tab>("cats");
  const subs = tree?.reduce((sum, top) => sum + top.children.length, 0) ?? 0;
  const count = (value: number) => t("categories.page.count", { count: format.number(value) });
  const figures = [
    { label: t("categories.page.figTop"), value: tree && count(tree.length), className: "text-marigold-text" },
    { label: t("categories.page.figSub"), value: tree && count(subs) },
    { label: t("categories.page.figTags"), value: tags && count(tags.length) },
  ];
  return (
    <div className="flex flex-col gap-4">
      <header className="relative flex flex-col gap-3.5 bg-ledger pt-1 pr-4.5 pb-10.5 pl-6.5 text-white max-md:-mx-4 max-md:-mt-4 md:overflow-hidden md:rounded-2xl md:pt-4.5 md:pr-7 md:pb-5 md:pl-14">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:left-7" />
        <div className="[&_a]:text-marigold-text">
          <BackToMore />
          <h1 className="text-[28px] leading-tight font-bold md:text-[32px]">{t("categories.title")}</h1>
          <p className="text-[15px] text-ledger-text">{t("categories.page.subtitle")}</p>
        </div>
        <div className="flex flex-wrap items-center gap-x-7 gap-y-3">
          <dl className="flex gap-6">
            {figures.map((figure) => (
              <div key={figure.label}>
                <dt className="text-sm font-semibold text-ledger-text">{figure.label}</dt>
                <dd className={cn("text-2xl font-bold", figure.className)}>{figure.value ?? "–"}</dd>
              </div>
            ))}
          </dl>
          <div className="relative min-w-0 flex-[1_1_260px]">
            <Search aria-hidden="true" className="absolute top-1/2 left-3 size-4.5 -translate-y-1/2 text-primary" />
            <Input
              type="search"
              aria-label={t("categories.page.search")}
              placeholder={t("categories.page.search")}
              className="h-11.5 rounded-xl border-0 bg-card pl-9.5 text-base text-foreground"
              value={q}
              onChange={(event) => setQ(event.target.value)}
            />
          </div>
        </div>
      </header>
      <div className="flex flex-col gap-3.5 max-md:relative max-md:-mx-4 max-md:-mt-11.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3 max-md:pt-3.5">
        {/* biome-ignore lint/a11y/useSemanticElements: two buttons that belong together, with no form around them. */}
        <div
          role="group"
          aria-label={t("categories.page.which")}
          className="grid grid-cols-2 gap-1.5 rounded-[14px] bg-[#E9ECE8] p-1 md:hidden"
        >
          {(
            [
              ["cats", t("categories.categories"), tree && tree.length + subs],
              ["tags", t("products.tags"), tags?.length],
            ] as const
          ).map(([key, label, total]) => (
            <button
              key={key}
              type="button"
              aria-pressed={tab === key}
              className={cn(
                "h-11 cursor-pointer rounded-[10px] text-[15px] font-bold",
                tab === key && "bg-card shadow-[0_2px_6px_rgba(7,26,20,0.12)]",
              )}
              onClick={() => setTab(key)}
            >
              {label}{" "}
              {total !== undefined && (
                <span className={cn("rounded-full px-1.75 text-[13px]", tab === key ? "bg-accent" : "bg-white/60")}>
                  {format.number(total)}
                </span>
              )}
            </button>
          ))}
        </div>
        <div className="grid items-start gap-4.5 md:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
          <Categories tree={tree} q={q} className={cn(tab !== "cats" && "max-md:hidden")} />
          <Tags tags={tags} q={q} className={cn(tab !== "tags" && "max-md:hidden")} />
        </div>
      </div>
    </div>
  );
}

/** A name box with its button, for adding something new; empties once added. */
function AddByName({
  label,
  solid = true,
  autoFocus = false,
  onAdd,
  onDone,
}: {
  label: string;
  solid?: boolean;
  autoFocus?: boolean;
  onAdd: (name: string) => Promise<unknown>;
  onDone?: () => void;
}) {
  const { t } = useTranslation();
  const form = useForm({ resolver: zodResolver(nameSchema), defaultValues: { name: "" } });
  const add = useMutation({
    mutationFn: (values: { name: string }) => onAdd(values.name),
    meta: { showsOwnErrors: true },
    onSuccess: () => {
      form.reset();
      onDone?.();
    },
    onError: (error) =>
      showServerError(form, error, { codes: { "category.name_taken": "name", "tag.name_taken": "name" } }),
  });
  return (
    <form noValidate className="flex flex-col gap-2" onSubmit={form.handleSubmit((values) => add.mutate(values))}>
      <FormError form={form} />
      <TextField
        form={form}
        name="name"
        label={label}
        labelHidden
        autoFocus={autoFocus}
        placeholder={label}
        className="h-11.5 rounded-[10px]"
        beside={
          <button
            type="submit"
            disabled={add.isPending}
            className={cn(
              solid
                ? SOLID
                : "h-11.5 flex-none cursor-pointer rounded-[10px] border-[1.5px] border-primary bg-card px-4 text-[15px] font-bold text-primary hover:bg-accent",
              "flex items-center gap-1.5",
            )}
          >
            {solid && <Plus aria-hidden="true" className="size-4" strokeWidth={2.6} />}
            {t("categories.page.add")}
          </button>
        }
      />
    </form>
  );
}

const matches = (name: string, q: string) => !q.trim() || containsText(name, q.trim());

function Categories({ tree, q, className }: { tree?: CategoryNode[]; q: string; className?: string }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState<Record<string, boolean>>({});
  // The category whose new-subcategory box is showing.
  const [addingTo, setAddingTo] = useState<string>();
  const add = async (name: string, parentId?: string) => {
    await call(api.POST("/api/v1/categories", { body: { name, parentId } }));
    await queryClient.invalidateQueries(categoriesQuery);
  };
  const searching = !!q.trim();
  const shown = (tree ?? []).filter(
    (top) => matches(top.name, q) || top.children.some((child) => matches(child.name, q)),
  );
  return (
    <section aria-labelledby="categories-title" className={cn(CARD, className)}>
      <div>
        <h2 id="categories-title" className="text-xl font-bold">
          {t("categories.categories")}
        </h2>
        <p className="text-sm text-muted-foreground">{t("categories.page.catsHint")}</p>
      </div>
      <AddByName label={t("categories.page.newCategory")} onAdd={(name) => add(name)} />
      {tree?.length === 0 && <Empty onAdd={(name) => add(name)} />}
      {shown.length > 0 && (
        <ul className="rounded-xl border">
          {shown.map((top, index) => {
            const children = top.children.filter(
              (child) => !searching || matches(top.name, q) || matches(child.name, q),
            );
            const isOpen = !!open[top.id] || (searching && children.length > 0 && !matches(top.name, q));
            return (
              <li key={top.id} className={cn(index > 0 && "border-t")}>
                <Row
                  node={top}
                  tree={tree ?? []}
                  siblings={shown.length === tree?.length ? tree : undefined}
                  index={index}
                  parent={null}
                  open={isOpen}
                  onToggle={() => setOpen((current) => ({ ...current, [top.id]: !isOpen }))}
                  onAddSub={() => {
                    setOpen((current) => ({ ...current, [top.id]: true }));
                    setAddingTo(top.id);
                  }}
                />
                {isOpen && (
                  <ul className="relative">
                    <span aria-hidden="true" className="absolute inset-y-0 left-7.5 w-0.5 bg-border" />
                    {children.map((child, childIndex) => (
                      <li key={child.id} className="border-t">
                        <Row
                          node={child}
                          tree={tree ?? []}
                          siblings={children.length === top.children.length ? top.children : undefined}
                          index={childIndex}
                          parent={top}
                        />
                      </li>
                    ))}
                    <li className="border-t py-2 pr-2 pl-11">
                      <AddSub
                        parent={top}
                        adding={addingTo === top.id}
                        onAdding={(adding) => setAddingTo(adding ? top.id : undefined)}
                        onAdd={(name) => add(name, top.id)}
                      />
                    </li>
                  </ul>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {!!tree?.length && shown.length === 0 && (
        <p className="p-4 text-center font-bold">{t("categories.page.noMatchCats")}</p>
      )}
    </section>
  );
}

/** One tap per common category, for a shop starting out. */
function Empty({ onAdd }: { onAdd: (name: string) => Promise<unknown> }) {
  const { t } = useTranslation();
  const add = useMutation({ mutationFn: onAdd });
  return (
    <div className="flex flex-col items-center gap-2.5 px-4 py-6 text-center">
      <span aria-hidden="true" className="flex size-13 items-center justify-center rounded-[14px] bg-accent">
        <Layers className="size-6.5 text-primary" />
      </span>
      <p className="text-[17px] font-bold">{t("categories.page.emptyTitle")}</p>
      <p className="max-w-sm text-sm text-muted-foreground">{t("categories.page.emptyHint")}</p>
      <div className="flex flex-wrap justify-center gap-1.5">
        {t("categories.page.suggestions")
          .split("|")
          .map((name) => (
            <button
              key={name}
              type="button"
              aria-label={t("categories.page.suggest", { name })}
              disabled={add.isPending}
              className="flex min-h-10 cursor-pointer items-center gap-1 rounded-full border-[1.5px] border-dashed border-[#9FC7B6] bg-card px-3 text-sm font-bold text-primary hover:bg-accent"
              onClick={() => add.mutate(name)}
            >
              <Plus aria-hidden="true" className="size-3.5" strokeWidth={2.6} />
              {name}
            </button>
          ))}
      </div>
    </div>
  );
}

function AddSub({
  parent,
  adding,
  onAdding,
  onAdd,
}: {
  parent: CategoryNode;
  adding: boolean;
  onAdding: (adding: boolean) => void;
  onAdd: (name: string) => Promise<unknown>;
}) {
  const { t } = useTranslation();
  const setAdding = onAdding;
  if (adding) {
    return (
      <div className="flex items-start gap-1.5">
        <div className="min-w-0 flex-1">
          <AddByName
            label={t("categories.page.subName", { name: parent.name })}
            solid={false}
            autoFocus
            onAdd={onAdd}
            onDone={() => setAdding(false)}
          />
        </div>
        <button
          type="button"
          aria-label={t("categories.page.cancel")}
          className={cn(ICON, "mt-1")}
          onClick={() => setAdding(false)}
        >
          <X aria-hidden="true" className="size-4.5" />
        </button>
      </div>
    );
  }
  return (
    <button
      type="button"
      className="flex min-h-10 cursor-pointer items-center gap-1.5 rounded-[10px] border-[1.5px] border-dashed border-[#9FC7B6] bg-card px-2.5 text-sm font-bold text-primary hover:bg-accent"
      onClick={() => setAdding(true)}
    >
      <Plus aria-hidden="true" className="size-3.5" strokeWidth={2.6} />
      {t("categories.page.addSubIn", { name: parent.name })}
    </button>
  );
}

function Row({
  node,
  tree,
  siblings,
  index,
  parent,
  open,
  onToggle,
  onAddSub,
}: {
  node: CategoryNode;
  tree: CategoryNode[];
  /** The whole group, to reorder in; none while a search hides some of it. */
  siblings?: CategoryNode[];
  index: number;
  parent: CategoryNode | null;
  open?: boolean;
  onToggle?: () => void;
  onAddSub?: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<"delete" | "cant" | "move">();
  const [renaming, setRenaming] = useState(false);
  const refresh = () => queryClient.invalidateQueries(categoriesQuery);
  const isTop = parent === null;
  const total = isTop ? totalOf(node) : node.productCount;
  const empty = node.productCount === 0 && node.children.length === 0;
  const order = useMutation({
    mutationFn: (ids: string[]) => call(api.PUT("/api/v1/categories/order", { body: { parentId: parent?.id, ids } })),
    onSettled: refresh,
  });
  const remove = useMutation({
    mutationFn: () => call(api.DELETE("/api/v1/categories/{id}", { params: { path: { id: node.id } } })),
    meta: { showsOwnErrors: true },
    onSuccess: () => setDialog(undefined),
    onSettled: refresh,
  });
  const swap = (other: number) => {
    if (!siblings) {
      return;
    }
    const ids = siblings.map((sibling) => sibling.id);
    const moving = ids[index] as string;
    ids[index] = ids[other] as string;
    ids[other] = moving;
    order.mutate(ids);
  };
  const last = !siblings || index === siblings.length - 1;
  return (
    <div className={cn("flex min-h-14 items-center gap-2 py-1.5 pr-2", isTop ? "pl-1.5" : "pl-11")}>
      {isTop &&
        (node.children.length > 0 ? (
          <button
            type="button"
            aria-expanded={!!open}
            aria-label={t(open ? "categories.page.close" : "categories.page.open", { name: node.name })}
            className={ICON}
            onClick={onToggle}
          >
            <ChevronRight aria-hidden="true" className={cn("size-4.5 transition-transform", open && "rotate-90")} />
          </button>
        ) : (
          <span aria-hidden="true" className="w-9 flex-none" />
        ))}
      {renaming ? (
        <Rename node={node} onDone={() => setRenaming(false)} />
      ) : (
        <>
          <div className="min-w-0 flex-1">
            <p className={cn("font-bold break-words", !isTop && "text-[15px]")}>{node.name}</p>
            <p className="flex flex-wrap gap-x-2.5 text-[13px] text-muted-foreground">
              <Link to="/products" search={{ categoryId: node.id }} className="font-bold no-underline">
                {t("categories.page.products", { count: format.number(total) })}
              </Link>
              {node.children.length > 0 && (
                <span>{t("categories.page.subs", { count: format.number(node.children.length) })}</span>
              )}
            </p>
          </div>
          <div className="flex flex-none items-center gap-0.5">
            <button
              type="button"
              aria-label={t("categories.moveUp", { name: node.name })}
              disabled={!siblings || index === 0 || order.isPending}
              className={ICON}
              onClick={() => swap(index - 1)}
            >
              <ArrowUp aria-hidden="true" className="size-4.25" />
            </button>
            <button
              type="button"
              aria-label={t("categories.moveDown", { name: node.name })}
              disabled={last || order.isPending}
              className={ICON}
              onClick={() => swap(index + 1)}
            >
              <ArrowDown aria-hidden="true" className="size-4.25" />
            </button>
            <RowMenu
              label={t("categories.page.more", { name: node.name })}
              actions={[
                { label: t("categories.page.rename"), onSelect: () => setRenaming(true) },
                ...(isTop && onAddSub ? [{ label: t("categories.page.addSub"), onSelect: onAddSub }] : []),
                // Somewhere to go: a subcategory can always go to the top; a top-level one, with none of its own, under another.
                ...(!isTop || (node.children.length === 0 && tree.length > 1)
                  ? [
                      {
                        label: t(isTop ? "categories.page.moveIn" : "categories.page.moveElsewhere"),
                        onSelect: () => setDialog("move"),
                      },
                    ]
                  : []),
                {
                  label: t("categories.delete"),
                  danger: true,
                  hint: empty
                    ? undefined
                    : node.children.length
                      ? t("categories.page.whyNotSubs")
                      : t("categories.page.whyNotProducts", { count: format.number(node.productCount) }),
                  onSelect: () => setDialog(empty ? "delete" : "cant"),
                },
              ]}
            />
          </div>
        </>
      )}
      {dialog === "delete" && (
        <ConfirmDialog
          title={t("categories.deleteTitle", { name: node.name })}
          description={t("categories.page.deleteEmpty")}
          problem={remove.error ? errorText(remove.error) : undefined}
          confirm={t("categories.delete")}
          pending={remove.isPending}
          onConfirm={() => remove.mutate()}
          onClose={() => setDialog(undefined)}
        />
      )}
      {dialog === "cant" && <CantDelete node={node} onClose={() => setDialog(undefined)} />}
      {dialog === "move" && <Move node={node} tree={tree} parent={parent} onClose={() => setDialog(undefined)} />}
    </div>
  );
}

/** The ⋯ button; the menu itself loads on the first tap, and opens. */
function RowMenu({ label, actions }: { label: string; actions: MenuAction[] }) {
  const [wanted, setWanted] = useState(false);
  const button = (
    <button
      type="button"
      aria-label={label}
      aria-haspopup="menu"
      className={MENU_TRIGGER}
      onClick={() => setWanted(true)}
    >
      <Ellipsis aria-hidden="true" className="size-4.5" />
    </button>
  );
  if (!wanted) {
    return button;
  }
  return (
    <Suspense fallback={button}>
      <CategoryMenu label={label} actions={actions} />
    </Suspense>
  );
}

function Rename({ node, onDone }: { node: CategoryNode; onDone: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const form = useForm({ resolver: zodResolver(nameSchema), defaultValues: { name: node.name } });
  const save = useMutation({
    mutationFn: (body: { name: string }) =>
      call(api.PATCH("/api/v1/categories/{id}", { params: { path: { id: node.id } }, body })),
    meta: { showsOwnErrors: true },
    onSuccess: async () => {
      await queryClient.invalidateQueries(categoriesQuery);
      onDone();
    },
    onError: (error) => showServerError(form, error, { codes: { "category.name_taken": "name" } }),
  });
  return (
    <form
      noValidate
      className="min-w-0 flex-1"
      onSubmit={form.handleSubmit((values) => (values.name === node.name ? onDone() : save.mutate(values)))}
    >
      <FormError form={form} />
      <TextField
        form={form}
        name="name"
        label={t("categories.page.renameLabel", { name: node.name })}
        labelHidden
        autoFocus
        className="h-10 rounded-[10px] border-primary"
        beside={
          <>
            <button
              type="submit"
              aria-label={t("categories.page.saveName")}
              disabled={save.isPending}
              className="flex size-10 flex-none cursor-pointer items-center justify-center rounded-[10px] bg-primary text-primary-foreground"
            >
              <Check aria-hidden="true" className="size-4.5" strokeWidth={2.6} />
            </button>
            <button
              type="button"
              aria-label={t("categories.page.cancel")}
              className="flex size-10 flex-none cursor-pointer items-center justify-center rounded-[10px] border-[1.5px] border-input bg-card"
              onClick={onDone}
            >
              <X aria-hidden="true" className="size-4" />
            </button>
          </>
        }
      />
    </form>
  );
}

function SmallDialog({
  title,
  children,
  footer,
  onClose,
}: {
  title: string;
  children: ReactNode;
  footer: ReactNode;
  onClose: () => void;
}) {
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-md">
        <div className="flex flex-col gap-2.5 p-4.5">
          <DialogTitle className="text-xl font-bold">{title}</DialogTitle>
          {children}
        </div>
        <div className="flex justify-end gap-2 border-t bg-background px-4.5 pt-3 pb-4">{footer}</div>
      </DialogContent>
    </Dialog>
  );
}

function CantDelete({ node, onClose }: { node: CategoryNode; onClose: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  return (
    <SmallDialog
      title={t("categories.page.cantTitle", { name: node.name })}
      onClose={onClose}
      footer={
        <button type="button" className={SOLID} onClick={onClose}>
          {t("categories.page.ok")}
        </button>
      }
    >
      <DialogDescription className="text-[15px] text-[#3E4842]">
        {node.children.length
          ? t("categories.page.cantSubs", { count: format.number(node.children.length) })
          : t("categories.page.cantProducts", { count: format.number(node.productCount) })}
      </DialogDescription>
      {!node.children.length && (
        <Link
          to="/products"
          search={{ categoryId: node.id }}
          className="flex min-h-10 w-fit items-center text-[15px] font-bold"
        >
          {t("categories.page.seeProducts", { count: format.number(node.productCount) })}
        </Link>
      )}
    </SmallDialog>
  );
}

function Move({
  node,
  tree,
  parent,
  onClose,
}: {
  node: CategoryNode;
  tree: CategoryNode[];
  parent: CategoryNode | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const queryClient = useQueryClient();
  const here = parent?.id ?? "";
  const [to, setTo] = useState(here);
  const move = useMutation({
    mutationFn: () =>
      call(
        api.POST("/api/v1/categories/{id}/move", {
          params: { path: { id: node.id } },
          body: to ? { parentId: to } : { topLevel: true },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: onClose,
    onSettled: () => queryClient.invalidateQueries(categoriesQuery),
  });
  const places = [
    { id: "", name: t("categories.page.moveTop") },
    ...tree.filter((top) => top.id !== node.id).map((top) => ({ id: top.id, name: top.name })),
  ];
  return (
    <SmallDialog
      title={t("categories.page.moveTitle", { name: node.name })}
      onClose={onClose}
      footer={
        <>
          <button type="button" className={OUTLINE} onClick={onClose}>
            {t("categories.page.cancel")}
          </button>
          <button
            type="button"
            disabled={move.isPending || to === here}
            className={SOLID}
            onClick={() => move.mutate()}
          >
            {t("categories.page.moveSave")}
          </button>
        </>
      }
    >
      <DialogDescription className="text-sm text-muted-foreground">
        {t("categories.page.moveNote", { count: format.number(node.productCount) })}
      </DialogDescription>
      {move.error && (
        <p role="alert" className="rounded-[10px] bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {errorText(move.error)}
        </p>
      )}
      <fieldset className="flex max-h-80 flex-col gap-1 overflow-y-auto">
        <legend className="sr-only">{t("categories.page.moveWhere")}</legend>
        {places.map((place) => (
          <label
            key={place.id || "top"}
            className={cn(
              "flex min-h-11.5 cursor-pointer items-center gap-2.5 rounded-[10px] px-2.5 text-[15px] has-focus-visible:ring-3 has-focus-visible:ring-ring/50",
              to === place.id ? "bg-[#F3F9F6] font-bold" : "hover:bg-muted",
            )}
          >
            <input
              type="radio"
              name="move-to"
              checked={to === place.id}
              className="size-5 flex-none accent-primary"
              onChange={() => setTo(place.id)}
            />
            <span>
              {place.name}
              {place.id === here && (
                <span className="font-normal text-muted-foreground"> {t("categories.page.moveNow")}</span>
              )}
            </span>
          </label>
        ))}
      </fieldset>
    </SmallDialog>
  );
}

function Tags({ tags, q, className }: { tags?: Tag[]; q: string; className?: string }) {
  const { t } = useTranslation();
  const format = useFormat();
  const queryClient = useQueryClient();
  const [picked, setPicked] = useState<string>();
  const add = async (name: string) => {
    await call(api.POST("/api/v1/tags", { body: { name } }));
    await queryClient.invalidateQueries(tagsQuery);
  };
  const shown = (tags ?? []).filter((tag) => matches(tag.name, q));
  const selected = tags?.find((tag) => tag.id === picked);
  return (
    <section aria-labelledby="tags-title" className={cn(CARD, className)}>
      <div>
        <h2 id="tags-title" className="text-xl font-bold">
          {t("products.tags")}
        </h2>
        <p className="text-sm text-muted-foreground">{t("categories.page.tagsHint")}</p>
      </div>
      <AddByName label={t("categories.page.newTag")} solid={false} onAdd={add} />
      {tags?.length === 0 && <p className="text-muted-foreground">{t("categories.noTags")}</p>}
      {shown.length > 0 && (
        <ul aria-label={t("categories.page.tagsLabel")} className="flex flex-wrap gap-2">
          {shown.map((tag) => {
            const on = tag.id === picked;
            return (
              <li key={tag.id}>
                <button
                  type="button"
                  aria-pressed={on}
                  className={cn(
                    "flex min-h-10 cursor-pointer items-center gap-2 rounded-full border-[1.5px] py-1 pr-1.5 pl-3 text-[15px] font-bold break-all",
                    on ? "border-primary bg-primary text-primary-foreground" : "border-input bg-card hover:bg-muted",
                  )}
                  onClick={() => setPicked(on ? undefined : tag.id)}
                >
                  {tag.name}
                  <span className={cn("min-w-5.5 rounded-full px-1.75 text-[13px]", on ? "bg-white/20" : "bg-muted")}>
                    {format.number(tag.productCount)}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {!!tags?.length && shown.length === 0 && (
        <p className="p-3 text-center font-bold">{t("categories.page.noMatchTags")}</p>
      )}
      {selected ? (
        <TagEditor key={selected.id} tag={selected} onDone={() => setPicked(undefined)} />
      ) : (
        shown.length > 0 && <p className="text-sm text-muted-foreground">{t("categories.page.tagPick")}</p>
      )}
    </section>
  );
}

function TagEditor({ tag, onDone }: { tag: Tag; onDone: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const queryClient = useQueryClient();
  const [deleting, setDeleting] = useState(false);
  const form = useForm({ resolver: zodResolver(nameSchema), defaultValues: { name: tag.name } });
  const refresh = () => queryClient.invalidateQueries(tagsQuery);
  const rename = useMutation({
    mutationFn: (body: { name: string }) =>
      call(api.PATCH("/api/v1/tags/{id}", { params: { path: { id: tag.id } }, body })),
    meta: { showsOwnErrors: true },
    onSuccess: async () => {
      await refresh();
      onDone();
    },
    onError: (error) => showServerError(form, error, { codes: { "tag.name_taken": "name" } }),
  });
  const remove = useMutation({
    mutationFn: () => call(api.DELETE("/api/v1/tags/{id}", { params: { path: { id: tag.id } } })),
    onSuccess: () => {
      setDeleting(false);
      onDone();
    },
    onSettled: refresh,
  });
  return (
    <form
      noValidate
      className="flex flex-col gap-2.5 rounded-xl border-[1.5px] border-mint-line bg-[#F3F9F6] p-3.5"
      onSubmit={form.handleSubmit((values) => (values.name === tag.name ? onDone() : rename.mutate(values)))}
    >
      <FormError form={form} />
      <TextField form={form} name="name" label={t("categories.page.tagName")} className="h-11.5 rounded-[10px]" />
      {tag.productCount > 0 ? (
        <Link to="/products" search={{ tagId: tag.id }} className="flex min-h-8 w-fit items-center text-sm font-bold">
          {t("categories.page.tagProducts", { count: format.number(tag.productCount) })}
        </Link>
      ) : (
        <p className="text-sm text-muted-foreground">{t("categories.page.tagNoProducts")}</p>
      )}
      <div className="flex flex-wrap justify-between gap-2">
        <button
          type="button"
          className="h-11 cursor-pointer rounded-[10px] border-[1.5px] border-destructive bg-card px-3.5 text-[15px] font-bold text-destructive hover:bg-danger-soft"
          onClick={() => setDeleting(true)}
        >
          {t("categories.page.deleteTag")}
        </button>
        <div className="flex gap-2">
          <button type="button" className={OUTLINE} onClick={onDone}>
            {t("categories.page.cancel")}
          </button>
          <button type="submit" disabled={rename.isPending} className={SOLID}>
            {t("categories.page.tagSave")}
          </button>
        </div>
      </div>
      <p className="text-[13px] text-muted-foreground">{t("categories.page.tagStays")}</p>
      {deleting && (
        <ConfirmDialog
          title={t("categories.deleteTagTitle", { name: tag.name })}
          description={t("categories.deleteTagDescription")}
          confirm={t("categories.delete")}
          pending={remove.isPending}
          onConfirm={() => remove.mutate()}
          onClose={() => setDeleting(false)}
        />
      )}
    </form>
  );
}
