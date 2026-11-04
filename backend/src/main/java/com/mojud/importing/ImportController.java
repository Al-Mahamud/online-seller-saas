package com.mojud.importing;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import com.mojud.tenancy.RequiresPermission;
import com.mojud.tenancy.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Product imports: managers and owners, who edit the catalog. */
@RestController
@RequiresRole(BusinessRole.STAFF)
@RequiresPermission(Permission.PRODUCTS)
class ImportController {

    static final DataSize MAX_FILE = DataSize.ofMegabytes(10);
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ImportService imports;
    private final ImportTemplate template;

    ImportController(ImportService imports, ImportTemplate template) {
        this.imports = imports;
        this.template = template;
    }

    /** Multipart form with one part named {@code file}: .xlsx or .csv (UTF-8), up to 10 MB. */
    @PostMapping(path = "/api/v1/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ImportService.Job upload(@RequestParam("file") MultipartFile file) {
        if (file.getSize() > MAX_FILE.toBytes()) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE);
        }
        try {
            return imports.create(fileName(file.getOriginalFilename()), file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/api/v1/imports/{id}")
    ImportService.Job get(@PathVariable UUID id) {
        return imports.job(id);
    }

    record ColumnRequest(@NotNull @PositiveOrZero Integer index, @NotNull ImportField field, @Size(max = 100) String optionName) {
    }

    /** {@code headerRow} optional (the sheet row holding the headers); {@code columns} replace the mapping. */
    record MappingRequest(@Positive Integer headerRow, @NotNull @Size(max = 100) List<@Valid @NotNull ColumnRequest> columns) {
    }

    @PutMapping("/api/v1/imports/{id}/mapping")
    ImportService.Job remap(@PathVariable UUID id, @Valid @RequestBody MappingRequest body) {
        return imports.remap(id, body.headerRow(), body.columns().stream()
                .map(c -> new ImportService.ColumnRequest(c.index(), c.field(), c.optionName())).toList());
    }

    /** Queues the valid rows; the import runs in the background (follow it with GET /imports/{id}). */
    @PostMapping("/api/v1/imports/{id}/commit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ImportService.Job commit(@PathVariable UUID id) {
        return imports.commit(id);
    }

    /** Rows with problems, ready to fix and upload again. */
    @GetMapping("/api/v1/imports/{id}/errors.csv")
    ResponseEntity<byte[]> errors(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("import-errors.csv").build().toString())
                .body(imports.errorsCsv(id));
    }

    /** The first 20 data rows, or with {@code invalidOnly} the first 20 with errors. */
    @GetMapping("/api/v1/imports/{id}/preview")
    ImportService.Preview preview(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean invalidOnly) {
        return imports.preview(id, invalidOnly);
    }

    @GetMapping("/api/v1/imports/template.xlsx")
    ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("mojud-products.xlsx").build().toString())
                .body(template.build(LocaleContextHolder.getLocale()));
    }

    /** The name without any folder, as some browsers send it; "import" when there is none. */
    private static String fileName(String original) {
        String name = original == null ? "" : original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1).strip();
        if (name.isEmpty()) {
            return "import";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }
}
