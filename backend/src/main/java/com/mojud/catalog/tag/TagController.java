package com.mojud.catalog.tag;

import com.mojud.shared.db.Constraints;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.text.Text;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
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

    record TagResponse(UUID id, String name) {

        static TagResponse of(Tag tag) {
            return new TagResponse(tag.getId(), tag.getName());
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
        return tags.findAllInOrder().stream().map(TagResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TagResponse create(@Valid @RequestBody NameRequest body) {
        String name = Text.cleanName(body.name(), "name", MAX_NAME);
        return saving(() -> TagResponse.of(tags.saveAndFlush(new Tag(name, clock.instant()))));
    }

    @PatchMapping("/{id}")
    public TagResponse rename(@PathVariable UUID id, @Valid @RequestBody NameRequest body) {
        String name = Text.cleanName(body.name(), "name", MAX_NAME);
        return saving(() -> {
            Tag tag = tags.findById(id).orElseThrow(ApiException::notFound);
            tag.rename(name, clock.instant());
            return TagResponse.of(tags.saveAndFlush(tag));
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
        try {
            return transactions.execute(status -> work.get());
        } catch (DataIntegrityViolationException e) {
            if (Constraints.violated(e, "uq_tags_name")) {
                throw new ApiException(ErrorCode.TAG_NAME_TAKEN);
            }
            throw e;
        }
    }
}
