import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod";
import { api, call } from "@/api/client";
import { ConfirmDialog } from "@/components/form/ConfirmDialog";
import { FormError, SelectField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { BackToMore, PageHeader } from "@/components/layout/PageHeader";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { type CategoryNode, categoriesQuery, type Tag, tagsQuery } from "./api";

const nameSchema = z.object({ name: z.string().trim().min(1, "field.required").max(80, "field.nameLong") });

/** Two levels of categories, and tags. A product goes in one category and has any tags. */
export function CategoriesPage() {
  const { t } = useTranslation();
  return (
    <div className="flex max-w-2xl flex-col gap-5">
      <div>
        <BackToMore />
        <PageHeader title={t("categories.title")} />
      </div>
      <CategoriesSection />
      <TagsSection />
    </div>
  );
}

/** A name with one box and one button, to add something new. */
function AddByName({ label, onAdd }: { label: string; onAdd: (name: string) => Promise<unknown> }) {
  const { t } = useTranslation();
  const form = useForm({ resolver: zodResolver(nameSchema), defaultValues: { name: "" } });
  const add = useMutation({
    mutationFn: (values: { name: string }) => onAdd(values.name),
    meta: { showsOwnErrors: true },
    onSuccess: () => form.reset(),
    onError: (error) =>
      showServerError(form, error, { codes: { "category.name_taken": "name", "tag.name_taken": "name" } }),
  });
  return (
    <form noValidate className="flex flex-col gap-3" onSubmit={form.handleSubmit((values) => add.mutate(values))}>
      <FormError form={form} />
      <TextField form={form} name="name" label={label} />
      <Button type="submit" variant="outline" className="self-start" disabled={add.isPending}>
        {t("account.add")}
      </Button>
    </form>
  );
}

function CategoriesSection() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: tree } = useQuery(categoriesQuery);
  const refresh = () => queryClient.invalidateQueries(categoriesQuery);
  const add = async (name: string) => {
    await call(api.POST("/api/v1/categories", { body: { name } }));
    await refresh();
  };
  return (
    <Section title={t("categories.categories")}>
      {tree?.length === 0 && <p className="text-muted-foreground">{t("categories.none")}</p>}
      <ul className="flex flex-col">
        {tree?.map((top, index) => (
          <li key={top.id} className="border-t py-2 first:border-t-0">
            <CategoryRow node={top} siblings={tree} index={index} parentId={null} />
            {top.children.length > 0 && (
              <ul className="mt-1 ml-4 flex flex-col border-l pl-3">
                {top.children.map((child, childIndex) => (
                  <li key={child.id} className="py-1">
                    <CategoryRow node={child} siblings={top.children} index={childIndex} parentId={top.id} />
                  </li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ul>
      <AddByName label={t("categories.newCategory")} onAdd={add} />
    </Section>
  );
}

function CategoryRow({
  node,
  siblings,
  index,
  parentId,
}: {
  node: CategoryNode;
  siblings: CategoryNode[];
  index: number;
  parentId: string | null;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<"edit" | "delete">();
  const refresh = () => queryClient.invalidateQueries(categoriesQuery);
  const order = useMutation({
    mutationFn: (ids: string[]) =>
      call(api.PUT("/api/v1/categories/order", { body: { parentId: parentId ?? undefined, ids } })),
    onSettled: refresh,
  });
  const remove = useMutation({
    mutationFn: () => call(api.DELETE("/api/v1/categories/{id}", { params: { path: { id: node.id } } })),
    onSuccess: () => setDialog(undefined),
    onSettled: refresh,
  });
  const swap = (other: number) => {
    const ids = siblings.map((sibling) => sibling.id);
    const moving = ids[index] as string;
    ids[index] = ids[other] as string;
    ids[other] = moving;
    order.mutate(ids);
  };
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <span className="min-w-0 font-medium break-words">{node.name}</span>
      <span className="flex flex-wrap gap-1">
        <Button
          variant="ghost"
          size="icon"
          aria-label={t("categories.moveUp", { name: node.name })}
          disabled={index === 0 || order.isPending}
          onClick={() => swap(index - 1)}
        >
          ↑
        </Button>
        <Button
          variant="ghost"
          size="icon"
          aria-label={t("categories.moveDown", { name: node.name })}
          disabled={index === siblings.length - 1 || order.isPending}
          onClick={() => swap(index + 1)}
        >
          ↓
        </Button>
        <Button variant="outline" aria-label={`${t("products.edit")}: ${node.name}`} onClick={() => setDialog("edit")}>
          {t("products.edit")}
        </Button>
        <Button
          variant="outline"
          aria-label={`${t("categories.delete")}: ${node.name}`}
          onClick={() => setDialog("delete")}
        >
          {t("categories.delete")}
        </Button>
      </span>
      {dialog === "edit" && <CategoryDialog node={node} parentId={parentId} onClose={() => setDialog(undefined)} />}
      {dialog === "delete" && (
        <ConfirmDialog
          title={t("categories.deleteTitle", { name: node.name })}
          description={t("categories.deleteDescription")}
          confirm={t("categories.delete")}
          pending={remove.isPending}
          onConfirm={() => remove.mutate()}
          onClose={() => setDialog(undefined)}
        />
      )}
    </div>
  );
}

const categorySchema = z.object({
  name: z.string().trim().min(1, "field.required").max(80, "field.nameLong"),
  parentId: z.string(),
});

/** Rename, and where it sits: top level, or under another top-level category. */
function CategoryDialog({
  node,
  parentId,
  onClose,
}: {
  node: CategoryNode;
  parentId: string | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: tree } = useQuery(categoriesQuery);
  const form = useForm({
    resolver: zodResolver(categorySchema),
    defaultValues: { name: node.name, parentId: parentId ?? "" },
  });
  const save = useMutation({
    mutationFn: async (values: z.output<typeof categorySchema>) => {
      if (values.name !== node.name) {
        await call(
          api.PATCH("/api/v1/categories/{id}", { params: { path: { id: node.id } }, body: { name: values.name } }),
        );
      }
      if (values.parentId !== (parentId ?? "")) {
        await call(
          api.POST("/api/v1/categories/{id}/move", {
            params: { path: { id: node.id } },
            body: values.parentId ? { parentId: values.parentId } : { topLevel: true },
          }),
        );
      }
    },
    meta: { showsOwnErrors: true },
    onSuccess: async () => {
      await queryClient.invalidateQueries(categoriesQuery);
      onClose();
    },
    onError: (error) => {
      void queryClient.invalidateQueries(categoriesQuery);
      showServerError(form, error, { codes: { "category.name_taken": "name", "category.too_deep": "parentId" } });
    },
  });
  // A category with subcategories stays at the top: there are only two levels.
  const canMove = node.children.length === 0;
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{node.name}</DialogTitle>
        </DialogHeader>
        <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => save.mutate(values))}>
          <FormError form={form} />
          <TextField form={form} name="name" label={t("categories.name")} />
          {canMove && (
            <SelectField form={form} name="parentId" label={t("categories.under")}>
              <option value="">{t("categories.topLevel")}</option>
              {tree
                ?.filter((top) => top.id !== node.id)
                .map((top) => (
                  <option key={top.id} value={top.id}>
                    {top.name}
                  </option>
                ))}
            </SelectField>
          )}
          <Button type="submit" disabled={save.isPending}>
            {t("account.save")}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function TagsSection() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: tags } = useQuery(tagsQuery);
  const refresh = () => queryClient.invalidateQueries(tagsQuery);
  const add = async (name: string) => {
    await call(api.POST("/api/v1/tags", { body: { name } }));
    await refresh();
  };
  return (
    <Section title={t("products.tags")}>
      {tags?.length === 0 && <p className="text-muted-foreground">{t("categories.noTags")}</p>}
      <ul className="flex flex-col">
        {tags?.map((tag) => (
          <TagRow key={tag.id} tag={tag} />
        ))}
      </ul>
      <AddByName label={t("categories.newTag")} onAdd={add} />
    </Section>
  );
}

function TagRow({ tag }: { tag: Tag }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<"edit" | "delete">();
  const form = useForm({ resolver: zodResolver(nameSchema), values: { name: tag.name } });
  const refresh = () => queryClient.invalidateQueries(tagsQuery);
  const rename = useMutation({
    mutationFn: (body: { name: string }) =>
      call(api.PATCH("/api/v1/tags/{id}", { params: { path: { id: tag.id } }, body })),
    meta: { showsOwnErrors: true },
    onSuccess: async () => {
      await refresh();
      setDialog(undefined);
    },
    onError: (error) => showServerError(form, error, { codes: { "tag.name_taken": "name" } }),
  });
  const remove = useMutation({
    mutationFn: () => call(api.DELETE("/api/v1/tags/{id}", { params: { path: { id: tag.id } } })),
    onSuccess: () => setDialog(undefined),
    onSettled: refresh,
  });
  return (
    <li className="flex flex-wrap items-center justify-between gap-2 border-t py-2 first:border-t-0">
      <span className="min-w-0 font-medium break-words">{tag.name}</span>
      <span className="flex gap-1">
        <Button variant="outline" aria-label={`${t("products.edit")}: ${tag.name}`} onClick={() => setDialog("edit")}>
          {t("products.edit")}
        </Button>
        <Button
          variant="outline"
          aria-label={`${t("categories.delete")}: ${tag.name}`}
          onClick={() => setDialog("delete")}
        >
          {t("categories.delete")}
        </Button>
      </span>
      {dialog === "edit" && (
        <Dialog open onOpenChange={(open) => !open && setDialog(undefined)}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>{tag.name}</DialogTitle>
            </DialogHeader>
            <form
              noValidate
              className="flex flex-col gap-4"
              onSubmit={form.handleSubmit((values) => rename.mutate(values))}
            >
              <FormError form={form} />
              <TextField form={form} name="name" label={t("categories.name")} />
              <Button type="submit" disabled={rename.isPending}>
                {t("account.save")}
              </Button>
            </form>
          </DialogContent>
        </Dialog>
      )}
      {dialog === "delete" && (
        <ConfirmDialog
          title={t("categories.deleteTagTitle", { name: tag.name })}
          description={t("categories.deleteTagDescription")}
          confirm={t("categories.delete")}
          pending={remove.isPending}
          onConfirm={() => remove.mutate()}
          onClose={() => setDialog(undefined)}
        />
      )}
    </li>
  );
}
