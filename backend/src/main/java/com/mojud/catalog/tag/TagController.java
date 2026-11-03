package com.mojud.catalog.tag;

import com.mojud.shared.db.Constraints;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.text.Text;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.RequiresRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tags of the selected business: everyone reads them, managers and owners change them. Small
 * enough to need no separate service; Phase 3b puts tags on products.
 */
@RestController
@RequestMapping("/api/v1/tags")
@RequiresRole(BusinessRole.MANAGER)
class TagController {

    static final int MAX_NAME = 50;

    /** {@code productCount}: the products carrying it. */
    record TagResponse(UUID id, String name, int productCount) {

        static TagResponse of(Tag tag, int productCount) {
            return new TagResponse(tag.getId(), tag.getName(), productCount);
        }
    }

    record NameRequest(@NotBlank @Size(max = 200) String name) {
    }

    private final TagRepository tags;
    private final TransactionTemplate transactions;
    private final Clock clock;

    TagController(TagRepository tags, TransactionTemplate transactions, Clock clock) {
        this.tags = tags;
        this.transactions = transactions;
        this.clock = clock;
    }

    @GetMapping
    @RequiresRole(BusinessRole.STAFF)
    @Transactional(readOnly = true)
    public List<TagResponse> list() {
        Map<UUID, Integer> counts = new HashMap<>();
        for (Object[] row : tags.productCounts(CurrentBusiness.require().businessId())) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return tags.findAllInOrder().stream().map(tag -> TagResponse.of(tag, counts.getOrDefault(tag.getId(), 0))).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TagResponse create(@Valid @RequestBody NameRequest body) {
        String name = Text.cleanName(body.name(), "name", MAX_NAME);
        return saving(() -> TagResponse.of(tags.saveAndFlush(new Tag(name, clock.instant())), 0));
    }

    @PatchMapping("/{id}")
    public TagResponse rename(@PathVariable UUID id, @Valid @RequestBody NameRequest body) {
        String name = Text.cleanName(body.name(), "name", MAX_NAME);
        return saving(() -> {
            Tag tag = tags.findById(id).orElseThrow(ApiException::notFound);
            tag.rename(name, clock.instant());
            return TagResponse.of(tags.saveAndFlush(tag), tags.countProducts(CurrentBusiness.require().businessId(), id));
        });
    }

    /** Phase 3b: also takes the tag off every product. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID id) {
        tags.delete(tags.findById(id).orElseThrow(ApiException::notFound));
    }

    private TagResponse saving(Supplier<TagResponse> work) {
        return Constraints.translating(transactions, Map.of("uq_tags_name", ErrorCode.TAG_NAME_TAKEN), work);
    }
}
