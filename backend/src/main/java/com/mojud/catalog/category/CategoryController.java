package com.mojud.catalog.category;

import com.mojud.shared.error.ApiException;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Everyone in the business reads categories; managers and owners change them (backend-plan.md §7). */
@RestController
@RequestMapping("/api/v1/categories")
@RequiresRole(BusinessRole.MANAGER)
class CategoryController {

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    @RequiresRole(BusinessRole.STAFF)
    List<CategoryService.CategoryNode> tree() {
        return categories.tree();
    }

    /** {@code parentId} makes it a subcategory of that top-level category. */
    record CreateRequest(@NotBlank @Size(max = 200) String name, UUID parentId) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CategoryService.CategoryNode create(@Valid @RequestBody CreateRequest body) {
        return categories.create(body.name(), body.parentId());
    }

    record RenameRequest(@NotBlank @Size(max = 200) String name) {
    }

    @PatchMapping("/{id}")
    CategoryService.CategoryNode rename(@PathVariable UUID id, @Valid @RequestBody RenameRequest body) {
        return categories.rename(id, body.name());
    }

    /** Exactly one of: {@code parentId} (under that top-level category) or {@code topLevel: true}. */
    record MoveRequest(UUID parentId, Boolean topLevel) {
    }

    @PostMapping("/{id}/move")
    CategoryService.CategoryNode move(@PathVariable UUID id, @RequestBody MoveRequest body) {
        boolean toTopLevel = Boolean.TRUE.equals(body.topLevel());
        if (toTopLevel == (body.parentId() != null)) {
            throw ApiException.invalidField("parentId", "field.move_target");
        }
        return categories.move(id, body.parentId());
    }

    /** One group's new order: the top level when {@code parentId} is null. */
    record OrderRequest(UUID parentId, @NotNull @Size(max = 1000) List<@NotNull UUID> ids) {
    }

    @PutMapping("/order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reorder(@Valid @RequestBody OrderRequest body) {
        categories.reorder(body.parentId(), body.ids());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        categories.delete(id);
    }
}
