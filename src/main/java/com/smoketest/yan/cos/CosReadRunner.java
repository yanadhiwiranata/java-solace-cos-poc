package com.smoketest.yan.cos;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.smoketest.yan.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Mode: cos-read
 *
 * Reads an object from Tencent COS and prints its content.
 * Also lists a few objects in the bucket to confirm access.
 */
public class CosReadRunner {

    private static final Logger log = LoggerFactory.getLogger(CosReadRunner.class);

    public static void run(AppConfig cfg) {
        CosClientFactory.CosClientHolder holder = CosClientFactory.create(cfg);
        COSClient cosClient = holder.cosClient();

        String bucket = cfg.cosBucket();
        String key    = cfg.cosReadKey();

        try {
            System.out.println("\n[COS READ] Listing objects in bucket: " + bucket);

            ListObjectsRequest listReq = new ListObjectsRequest();
            listReq.setBucketName(bucket);
            listReq.setPrefix("yan/");
            listReq.setMaxKeys(10);

            ObjectListing listing = cosClient.listObjects(listReq);
            if (listing.getObjectSummaries().isEmpty()) {
                System.out.println("  (no objects found with prefix 'smoketest/')");
            } else {
                for (COSObjectSummary obj : listing.getObjectSummaries()) {
                    System.out.printf("  key=%-50s  size=%d bytes%n", obj.getKey(), obj.getSize());
                }
            }

            System.out.println("\n[COS READ] Reading object: " + key);
            COSObject cosObject = cosClient.getObject(bucket, key);

            String content;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(cosObject.getObjectContent(), StandardCharsets.UTF_8))) {
                content = reader.lines().collect(Collectors.joining("\n"));
            }

            System.out.println("─────────────────────────────────────────");
            System.out.println(content);
            System.out.println("─────────────────────────────────────────");
            System.out.println("[COS READ] Done. Content length: " + content.length() + " chars");
            log.info("[COS READ] bucket={} key={} size={}", bucket, key, content.length());

        } catch (Exception e) {
            log.error("[COS READ] Failed: {}", e.getMessage(), e);
            System.err.println("[COS READ] Error: " + e.getMessage());
        } finally {
            holder.shutdown();
        }
    }
}
