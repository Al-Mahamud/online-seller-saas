package com.mojud.files;

import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import org.springframework.util.unit.DataSize;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploading images (managers and owners: they edit the catalog and the logo) and fetching them
 * (any member). The file name and the client's content type are ignored: the bytes decide.
 */
@RestController
class FileController {

    static final DataSize MAX_IMAGE = DataSize.ofMegabytes(8);

    private final FileService files;

    FileController(FileService files) {
        this.files = files;
    }

    /** Multipart form with one part named {@code file}: JPEG, PNG or WebP, up to 8 MB. */
    @PostMapping(path = "/api/v1/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresRole(BusinessRole.MANAGER)
    @ResponseStatus(HttpStatus.CREATED)
    FileService.Uploaded upload(@RequestParam("file") MultipartFile file) {
        if (file.getSize() > MAX_IMAGE.toBytes()) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE);
        }
        try {
            return files.upload(file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code size} is {@code large} or {@code small}. Files never change, so browsers may keep them;
     * "private" keeps shared caches from storing a business's images.
     */
    @GetMapping("/api/v1/files/{id}/{size}")
    @RequiresRole(BusinessRole.STAFF)
    ResponseEntity<byte[]> content(@PathVariable UUID id, @PathVariable String size) {
        FileService.Content content = files.content(id, size);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .body(content.bytes());
    }
}
