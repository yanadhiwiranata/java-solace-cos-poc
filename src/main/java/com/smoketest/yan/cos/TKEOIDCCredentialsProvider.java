package com.smoketest.yan.cos;

import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.auth.COSCredentialsProvider;
import com.qcloud.cos.exception.CosClientException;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.sts.v20180813.StsClient;
import com.tencentcloudapi.sts.v20180813.models.AssumeRoleWithWebIdentityRequest;
import com.tencentcloudapi.sts.v20180813.models.AssumeRoleWithWebIdentityResponse;
import com.tencentcloudapi.sts.v20180813.models.Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * TKE OIDC credentials provider for Tencent COS.
 *
 * Automatically obtains and refreshes temporary credentials via OIDC + STS
 * using the Kubernetes ServiceAccount projected token.
 *
 * Environment variables (auto-injected by TKE when ServiceAccount is annotated):
 *   TKE_ROLE_ARN                - CAM role ARN (required)
 *   TKE_WEB_IDENTITY_TOKEN_FILE - Path to projected SA token file
 *                                 (falls back to DEFAULT_SA_TOKEN_PATH)
 *   TKE_REGION                  - Tencent Cloud region
 *                                 (falls back to regionFallback from cos.region)
 *   TKE_PROVIDER_ID             - OIDC provider ID (optional)
 */
public class TKEOIDCCredentialsProvider implements COSCredentialsProvider {

    private static final Logger log = LoggerFactory.getLogger(TKEOIDCCredentialsProvider.class);

    private static final String DEFAULT_SESSION_NAME    = "TKE-COS-Session";
    private static final long   DEFAULT_DURATION_SECONDS = 3600L;
    private static final long   REFRESH_BUFFER_SECONDS  = 300L;

    /** Standard path where TKE/K8s mounts the projected ServiceAccount OIDC token. */
    private static final String DEFAULT_SA_TOKEN_PATH =
        "/var/run/secrets/tokens/oidc-token";

    private volatile COSCredentials credentials;
    private volatile long credentialsExpireTime = 0;
    private final ReentrantLock lock = new ReentrantLock();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TKE-OIDC-Credentials-Refresher");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> refreshTask;

    private final String roleArn;
    private final String tokenFilePath;
    private final String providerId;
    private final String region;

    /**
     * @param regionFallback  value from cos.region in config.properties,
     *                        used when TKE_REGION env var is absent
     */
    public TKEOIDCCredentialsProvider(String regionFallback) {
        this.roleArn       = System.getenv("TKE_ROLE_ARN");
        this.providerId    = System.getenv("TKE_PROVIDER_ID");
        this.tokenFilePath = resolveTokenFilePath();
        this.region        = resolveRegion(regionFallback);

        if (roleArn == null || roleArn.isEmpty()) {
            throw new CosClientException(
                "TKE_ROLE_ARN env var is missing. " +
                "Ensure the Kubernetes ServiceAccount has annotation: " +
                "tke.cloud.tencent.com/role-arn=<CAM role ARN>"
            );
        }
        if (this.region == null || this.region.isEmpty()) {
            throw new CosClientException(
                "Region not specified. Set TKE_REGION env var or cos.region in config.properties."
            );
        }

        log.info("TKE OIDC initialized - RoleArn={}, TokenFile={}, Region={}",
            roleArn, tokenFilePath, this.region);
    }

    private static String resolveTokenFilePath() {
        String fromEnv = System.getenv("TKE_WEB_IDENTITY_TOKEN_FILE");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        log.warn("TKE_WEB_IDENTITY_TOKEN_FILE not set, falling back to default path: {}",
            DEFAULT_SA_TOKEN_PATH);
        return DEFAULT_SA_TOKEN_PATH;
    }

    private static String resolveRegion(String fallback) {
        String fromEnv = System.getenv("TKE_REGION");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        if (fallback != null && !fallback.isEmpty()) {
            log.info("TKE_REGION not set, using cos.region fallback: {}", fallback);
            return fallback;
        }
        return null;
    }

    @Override
    public COSCredentials getCredentials() {
        if (credentials == null || isExpiredOrExpiring()) {
            lock.lock();
            try {
                if (credentials == null || isExpiredOrExpiring()) {
                    refreshCredentials();
                }
            } finally {
                lock.unlock();
            }
        }
        return credentials;
    }

    @Override
    public void refresh() {
        lock.lock();
        try {
            refreshCredentials();
        } finally {
            lock.unlock();
        }
    }

    private boolean isExpiredOrExpiring() {
        if (credentialsExpireTime == 0) return true;
        long currentTime = System.currentTimeMillis() / 1000;
        return (credentialsExpireTime - currentTime) <= REFRESH_BUFFER_SECONDS;
    }

    private void refreshCredentials() {
        try {
            String webIdentityToken = readTokenFile();
            AssumeRoleWithWebIdentityResponse response = callSTSAssumeRole(webIdentityToken);
            Credentials tempCreds = response.getCredentials();

            this.credentials = new BasicSessionCredentials(
                tempCreds.getTmpSecretId(),
                tempCreds.getTmpSecretKey(),
                tempCreds.getToken()
            );
            this.credentialsExpireTime = response.getExpiredTime();

            long timeUntilExpiry = credentialsExpireTime - (System.currentTimeMillis() / 1000);
            log.info("TKE OIDC credentials refreshed, expires in {} seconds", timeUntilExpiry);

            if (refreshTask != null && !refreshTask.isDone()) {
                refreshTask.cancel(false);
            }

            long delaySeconds = timeUntilExpiry - REFRESH_BUFFER_SECONDS;
            if (delaySeconds > 0) {
                refreshTask = scheduler.schedule(() -> {
                    try {
                        refresh();
                    } catch (Exception e) {
                        log.error("Scheduled credentials refresh failed", e);
                    }
                }, delaySeconds, TimeUnit.SECONDS);
                log.info("Next credentials refresh in {} seconds", delaySeconds);
            }
        } catch (Exception e) {
            throw new CosClientException("Failed to obtain TKE OIDC credentials", e);
        }
    }

    private String readTokenFile() throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(tokenFilePath));
        String token = new String(bytes).trim();
        if (token.isEmpty()) {
            throw new IOException("Token file is empty: " + tokenFilePath);
        }
        return token;
    }

    private AssumeRoleWithWebIdentityResponse callSTSAssumeRole(String webIdentityToken)
            throws TencentCloudSDKException {
        Credential credential = new Credential("", "");

        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint("sts.tencentcloudapi.com");

        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);

        StsClient client = new StsClient(credential, region, clientProfile);

        AssumeRoleWithWebIdentityRequest req = new AssumeRoleWithWebIdentityRequest();
        req.setRoleArn(roleArn);
        req.setRoleSessionName(DEFAULT_SESSION_NAME);
        req.setWebIdentityToken(webIdentityToken);
        req.setDurationSeconds(DEFAULT_DURATION_SECONDS);

        if (providerId != null && !providerId.isEmpty()) {
            req.setProviderId(providerId);
        }

        AssumeRoleWithWebIdentityResponse response = client.AssumeRoleWithWebIdentity(req);
        Credentials creds = response.getCredentials();

        if (creds == null || creds.getTmpSecretId() == null || creds.getTmpSecretKey() == null) {
            throw new TencentCloudSDKException("STS returned incomplete credentials");
        }

        return response;
    }

    public void shutdown() {
        if (refreshTask != null && !refreshTask.isDone()) {
            refreshTask.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
