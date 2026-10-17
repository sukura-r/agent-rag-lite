package com.example.agentrag.document;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class DocumentObjectStorage {
    private static final Logger log = LoggerFactory.getLogger(DocumentObjectStorage.class);
    private final MinioClient client;
    private final String bucket;

    public DocumentObjectStorage(@Value("${app.document.storage.endpoint}") String endpoint,
                                 @Value("${app.document.storage.access-key}") String accessKey,
                                 @Value("${app.document.storage.secret-key}") String secretKey,
                                 @Value("${app.document.storage.bucket:knowledge-source}") String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    @PostConstruct
    public void initializeBucket() {
        try { ensureBucket(); }
        catch (RuntimeException exception) {
            // MinIO故障不应阻止已有知识库继续提供查询；上传时会再次检查并返回明确失败。
            log.warn("文档原件桶暂不可用，文件上传将在存储恢复前失败，bucket={}", bucket, exception);
        }
    }

    private void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception exception) {
            try {
                if (client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) return;
            } catch (Exception ignored) { }
            throw new IllegalStateException("初始化文档原件存储失败", exception);
        }
    }

    public void put(String objectKey, Path file, String mediaType) {
        ensureBucket();
        try (InputStream input = Files.newInputStream(file)) {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
                    .stream(input, Files.size(file), -1L).contentType(mediaType).build());
        } catch (Exception exception) {
            throw new IllegalStateException("保存文档原件失败", exception);
        }
    }

    public InputStream open(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new IllegalStateException("读取文档原件失败", exception);
        }
    }

    public byte[] read(String objectKey, long maximumBytes) {
        try (InputStream input = open(objectKey)) {
            byte[] value = input.readNBytes(Math.toIntExact(maximumBytes + 1));
            if (value.length > maximumBytes) throw new IllegalArgumentException("原文件超过下载限制");
            return value;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("下载文档原件失败", exception);
        }
    }

    public void remove(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new IllegalStateException("删除文档原件失败", exception);
        }
    }
}
