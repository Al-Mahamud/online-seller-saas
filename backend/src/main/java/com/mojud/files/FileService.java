package com.mojud.files;

import com.mojud.files.image.ImageProcessor;
import com.mojud.files.storage.FileStorage;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.files.FileLinks;
import com.mojud.shared.id.Ids;
import com.mojud.shared.ratelimit.RateLimiter;
import com.mojud.tenancy.CurrentBusiness;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Image uploads for the selected business (implementation-plan.md 3c). The upload is processed at
 * once (small enough to need no background job), stored in two sizes, and recorded; only members of
 * the business can fetch it back.
 */
@Service
class FileService implements StoredFiles {

    record Uploaded(UUID id, int width, int height, FileLinks.Links links) {
    }

    record Content(byte[] bytes, String contentType) {
    }

    private final ImageProcessor images;
    private final FileStorage storage;
    private final UploadRepository uploads;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final RateLimiter uploadsPerBusiness;

    FileService(ImageProcessor images, FileStorage storage, UploadRepository uploads, TransactionTemplate transactions,
            Clock clock, @Value("${mojud.files.uploads-per-business.max}") int perBusinessMax,
            @Value("${mojud.files.uploads-per-business.window}") Duration perBusinessWindow) {
        this.images = images;
        this.storage = storage;
        this.uploads = uploads;
        this.transactions = transactions;
        this.clock = clock;
        this.uploadsPerBusiness = new RateLimiter("uploads-per-business", perBusinessMax, perBusinessWindow);
    }

    /**
     * Files are written before the row, and removed again if the row cannot be saved, so a
     * recorded upload always has its files.
     */
    Uploaded upload(byte[] content) {
        CurrentBusiness business = CurrentBusiness.require();
        uploadsPerBusiness.acquire(business.businessId().toString());
        ImageProcessor.Processed image = images.process(content);
        UUID id = Ids.newId();
        String prefix = business.businessId() + "/" + id + "/";
        String large = prefix + "large." + image.extension();
        String small = prefix + "small." + image.extension();
        storage.store(large, image.large());
        storage.store(small, image.small());
        try {
            transactions.executeWithoutResult(status -> uploads.save(new Upload(id, image.contentType(), image.width(),
                    image.height(), image.large().length, image.small().length, business.userId(), clock.instant())));
        } catch (RuntimeException e) {
            storage.delete(large);
            storage.delete(small);
            throw e;
        }
        return new Uploaded(id, image.width(), image.height(), FileLinks.of(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredFiles.Content> small(UUID fileId) {
        return uploads.findById(fileId)
                .flatMap(upload -> storage.load(upload.key("small"))
                        .map(bytes -> new StoredFiles.Content(bytes, upload.getContentType())));
    }

    /** @param size "large" or "small" */
    @Transactional(readOnly = true)
    Content content(UUID fileId, String size) {
        if (!size.equals("large") && !size.equals("small")) {
            throw ApiException.notFound();
        }
        Upload upload = uploads.findById(fileId).orElseThrow(ApiException::notFound);
        byte[] bytes = storage.load(upload.key(size)).orElseThrow(ApiException::notFound);
        return new Content(bytes, upload.getContentType());
    }
}
