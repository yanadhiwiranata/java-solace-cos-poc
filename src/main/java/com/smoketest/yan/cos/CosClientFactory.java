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
 * Factory that creates a COSClient.
 *
 * Credential resolution order:
 *   1. TKE_ROLE_ARN env var present → TKEOIDCCredentialsProvider (TKE ServiceAccount OIDC)
 *   2. cos.auth-mode=oidc in config → TKEOIDCCredentialsProvider (TKE ServiceAccount OIDC)
 *   3. Either of the above fails    → AKSK fallback (cos.secret-id / cos.secret-key)
 *   4. Neither configured           → AKSK (local dev)
 *
 * TKEOIDCCredentialsProvider extends AbstractCOSCachedCredentialsProvider, so the SDK
 * manages credential caching and refresh automatically — no manual scheduling needed.
 */
public class CosClientFactory {

    private static final Logger log = LoggerFactory.getLogger(CosClientFactory.class);

    /**
     * Returns a CosClientHolder. Caller must call holder.shutdown() when done.
     */
    public static CosClientHolder create(AppConfig cfg) {
        String region = cfg.cosRegion();
        ClientConfig clientConfig = new ClientConfig(new Region(region));

        COSClient cosClient;
        TKEOIDCCredentialsProvider oidcProvider = null;

        if (shouldUseOidc(cfg)) {
            try {
                log.info("COS auth: OIDC (TKE ServiceAccount)");
                oidcProvider = new TKEOIDCCredentialsProvider(region);
                cosClient = new COSClient(oidcProvider, clientConfig);
            } catch (CosClientException e) {
                log.warn("OIDC init failed: {}. Falling back to AKSK.", e.getMessage());
                closeQuietly(oidcProvider);
                oidcProvider = null;
                cosClient = createAkskClient(cfg, clientConfig);
            }
        } else {
            cosClient = createAkskClient(cfg, clientConfig);
        }

        log.info("COSClient ready - region={} bucket={}", region, cfg.cosBucket());
        return new CosClientHolder(cosClient, oidcProvider);
    }

    private static COSClient createAkskClient(AppConfig cfg, ClientConfig clientConfig) {
        log.info("COS auth: AKSK");
        String secretId  = cfg.cosSecretId();
        String secretKey = cfg.cosSecretKey();
        if (secretId.isEmpty() || secretKey.isEmpty()) {
            throw new IllegalStateException(
                "No valid COS credentials: OIDC unavailable and " +
                "cos.secret-id / cos.secret-key are not set in config.properties");
        }
        return new COSClient(new BasicCOSCredentials(secretId, secretKey), clientConfig);
    }

    /**
     * Auto-selects OIDC when TKE_ROLE_ARN is injected by TKE into the pod,
     * or when explicitly set via cos.auth-mode=oidc.
     */
    private static boolean shouldUseOidc(AppConfig cfg) {
        String roleArn = System.getenv("TKE_ROLE_ARN");
        if (roleArn != null && !roleArn.isEmpty()) {
            log.info("TKE_ROLE_ARN detected - auto-selecting OIDC auth");
            return true;
        }
        return "oidc".equalsIgnoreCase(cfg.cosAuthMode());
    }

    private static void closeQuietly(TKEOIDCCredentialsProvider provider) {
        if (provider != null) {
            try { provider.close(); } catch (Exception ignored) {}
        }
    }

    public record CosClientHolder(COSClient cosClient, TKEOIDCCredentialsProvider oidcProvider) {
        public void shutdown() {
            cosClient.shutdown();
            closeQuietly(oidcProvider);
        }

        private static void closeQuietly(TKEOIDCCredentialsProvider provider) {
            if (provider != null) {
                try { provider.close(); } catch (Exception ignored) {}
            }
        }
    }
}
