package com.smoketest.yan.cos;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.region.Region;
import com.smoketest.yan.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory that creates a COSClient using either OIDC (TKE ServiceAccount) or AKSK auth.
 *
 * Auth mode resolution order:
 *   1. If TKE_ROLE_ARN env var is present → OIDC (auto-detected, k8s ServiceAccount)
 *   2. If cos.auth-mode=oidc in config     → OIDC
 *   3. If OIDC init fails                  → AKSK fallback (if secret-id/key are set)
 *   4. Otherwise                           → AKSK (local dev)
 */
public class CosClientFactory {

    private static final Logger log = LoggerFactory.getLogger(CosClientFactory.class);

    /**
     * Returns a (COSClient, optionally TKEOIDCCredentialsProvider) pair.
     * Caller must call shutdown() on the holder when done.
     */
    public static CosClientHolder create(AppConfig cfg) {
        String region = cfg.cosRegion();
        ClientConfig clientConfig = new ClientConfig(new Region(region));

        COSClient cosClient;
        TKEOIDCCredentialsProvider oidcProvider = null;

        if (shouldUseOidc(cfg)) {
            try {
                log.info("COS auth mode: OIDC (TKE ServiceAccount)");
                oidcProvider = new TKEOIDCCredentialsProvider(region);
                cosClient = new COSClient(oidcProvider, clientConfig);
            } catch (CosClientException e) {
                log.warn("OIDC init failed: {}. Falling back to AKSK.", e.getMessage());
                oidcProvider = null;
                cosClient = createAkskClient(cfg, clientConfig);
            }
        } else {
            cosClient = createAkskClient(cfg, clientConfig);
        }

        log.info("COSClient created - region={} bucket={}", region, cfg.cosBucket());
        return new CosClientHolder(cosClient, oidcProvider);
    }

    private static COSClient createAkskClient(AppConfig cfg, ClientConfig clientConfig) {
        log.info("COS auth mode: AKSK");
        String secretId  = cfg.cosSecretId();
        String secretKey = cfg.cosSecretKey();
        if (secretId.isEmpty() || secretKey.isEmpty()) {
            throw new IllegalStateException(
                "No valid COS credentials: OIDC is unavailable and " +
                "cos.secret-id / cos.secret-key are not set in config.properties");
        }
        return new COSClient(new BasicCOSCredentials(secretId, secretKey), clientConfig);
    }

    /**
     * Returns true if OIDC should be attempted.
     * Auto-selects OIDC when TKE_ROLE_ARN is present in the environment,
     * regardless of the cos.auth-mode setting in config.
     */
    private static boolean shouldUseOidc(AppConfig cfg) {
        String roleArnFromEnv = System.getenv("TKE_ROLE_ARN");
        if (roleArnFromEnv != null && !roleArnFromEnv.isEmpty()) {
            log.info("TKE_ROLE_ARN detected in environment - auto-selecting OIDC auth");
            return true;
        }
        return "oidc".equalsIgnoreCase(cfg.cosAuthMode());
    }

    public record CosClientHolder(COSClient cosClient, TKEOIDCCredentialsProvider oidcProvider) {
        public void shutdown() {
            cosClient.shutdown();
            if (oidcProvider != null) {
                oidcProvider.shutdown();
            }
        }
    }
}
