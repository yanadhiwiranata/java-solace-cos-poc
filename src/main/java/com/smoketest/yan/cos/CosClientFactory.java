package com.smoketest.yan.cos;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentialsProvider;
import com.qcloud.cos.region.Region;
import com.smoketest.yan.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory that creates a COSClient using either OIDC (TKE) or AKSK auth,
 * depending on cos.auth-mode in config.properties.
 */
public class CosClientFactory {

    private static final Logger log = LoggerFactory.getLogger(CosClientFactory.class);

    /**
     * Returns a (COSClient, optionally TKEOIDCCredentialsProvider) pair.
     * Caller must call shutdown() on the provider (if OIDC) and cosClient.shutdown() when done.
     */
    public static CosClientHolder create(AppConfig cfg) {
        String authMode = cfg.cosAuthMode();
        String region   = cfg.cosRegion();

        ClientConfig clientConfig = new ClientConfig(new Region(region));

        COSClient cosClient;
        TKEOIDCCredentialsProvider oidcProvider = null;

        if ("oidc".equalsIgnoreCase(authMode)) {
            log.info("COS auth mode: OIDC (TKE)");
            oidcProvider = new TKEOIDCCredentialsProvider();
            cosClient = new COSClient(oidcProvider, clientConfig);
        } else {
            log.info("COS auth mode: AKSK");
            String secretId  = cfg.cosSecretId();
            String secretKey = cfg.cosSecretKey();
            if (secretId.isEmpty() || secretKey.isEmpty()) {
                throw new IllegalStateException(
                    "cos.auth-mode=aksk but cos.secret-id / cos.secret-key are not set in config.properties");
            }
            cosClient = new COSClient(new BasicCOSCredentials(secretId, secretKey), clientConfig);
        }

        log.info("COSClient created - region={} bucket={}", region, cfg.cosBucket());
        return new CosClientHolder(cosClient, oidcProvider);
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
