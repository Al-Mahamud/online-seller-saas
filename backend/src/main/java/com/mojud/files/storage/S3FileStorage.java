package com.mojud.files.storage;

import java.net.URI;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Files in a bucket through the S3 API: Cloudflare R2 on servers (backend-plan.md §3), or any
 * S3-compatible store. Settings under {@code mojud.storage.s3} (environment: S3_ENDPOINT, S3_BUCKET,
 * S3_ACCESS_KEY_ID, S3_SECRET_ACCESS_KEY, S3_REGION; R2's region is "auto"); missing ones stop the
 * app from starting. The bucket stays private: files are only ever served through the API.
 *
 * <p>Checksums only where the API requires them: R2 does not support the SDK's newer default ones.
 */
@Component
@ConditionalOnProperty(name = "mojud.storage.provider", havingValue = "s3")
class S3FileStorage implements FileStorage {

    private final S3Client s3;
    private final String bucket;

    S3FileStorage(Environment environment) {
        this.bucket = required(environment, "mojud.storage.s3.bucket");
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(required(environment, "mojud.storage.s3.endpoint")))
                .region(Region.of(environment.getProperty("mojud.storage.s3.region", "auto")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        required(environment, "mojud.storage.s3.access-key-id"),
                        required(environment, "mojud.storage.s3.secret-access-key"))))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Override
    public void store(String key, byte[] content) {
        s3.putObject(request -> request.bucket(bucket).key(FileStorage.checkKey(key)), RequestBody.fromBytes(content));
    }

    @Override
    public Optional<byte[]> load(String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(request -> request.bucket(bucket).key(FileStorage.checkKey(key)))
                    .asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(request -> request.bucket(bucket).key(FileStorage.checkKey(key)));
    }

    /** One object at a time: rare (deleting a business) and simpler for every S3-compatible store. */
    @Override
    public void deleteAll(String prefix) {
        FileStorage.checkPrefix(prefix);
        for (S3Object object : s3.listObjectsV2Paginator(request -> request.bucket(bucket).prefix(prefix)).contents()) {
            s3.deleteObject(request -> request.bucket(bucket).key(object.key()));
        }
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required when mojud.storage.provider=s3");
        }
        return value;
    }
}
