package com.smoketest.yan.cos;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.smoketest.yan.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Mode: cos-write
 *
 * Writes a text object to Tencent COS.
 * Content and key are read from config.properties.
 */
public class CosWriteRunner {

    private static final Logger log = LoggerFactory.getLogger(CosWriteRunner.class);

    public static void run(AppConfig cfg) {
        CosClientFactory.CosClientHolder holder = CosClientFactory.create(cfg);
        COSClient cosClient = holder.cosClient();

        String bucket  = cfg.cosBucket();
        String key     = cfg.cosWriteKey();
        String content = cfg.cosWriteContent() + "\n\nWritten at: " + Instant.now();

        try {
            System.out.println("\n[COS WRITE] Writing to bucket=" + bucket + " key=" + key);

            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            InputStream inputStream = new ByteArrayInputStream(bytes);

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(bytes.length);
            metadata.setContentType("text/plain; charset=UTF-8");
            metadata.addUserMetadata("created-by", "com.smoketest.yan");

            PutObjectRequest putReq = new PutObjectRequest(bucket, key, inputStream, metadata);
            PutObjectResult result = cosClient.putObject(putReq);

            System.out.println("[COS WRITE] Success!");
            System.out.println("  bucket  : " + bucket);
            System.out.println("  key     : " + key);
            System.out.println("  etag    : " + result.getETag());
            System.out.println("  content : " + content);
            log.info("[COS WRITE] bucket={} key={} etag={} size={}", bucket, key, result.getETag(), bytes.length);

        } catch (Exception e) {
            log.error("[COS WRITE] Failed: {}", e.getMessage(), e);
            System.err.println("[COS WRITE] Error: " + e.getMessage());
        } finally {
            holder.shutdown();
        }
    }
}
