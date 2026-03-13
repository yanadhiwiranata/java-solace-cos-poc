package com.smoketest.yan.cos;

import com.qcloud.cos.auth.AbstractCOSCachedCredentialsProvider;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.tencentcloudapi.common.Credential;
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

/**
 * TKE OIDC credential provider for Tencent COS.
 *
 * Extends AbstractCOSCachedCredentialsProvider so the SDK handles caching and
 * scheduled refresh automatically — no manual scheduling needed.
 *
 * On each refresh, reads the projected ServiceAccount OIDC token and exchanges
 * it for temporary COS credentials via STS AssumeRoleWithWebIdentity.
 *
 * Env vars (auto-injected by TKE when the ServiceAccount is annotated):
 *   TKE_ROLE_ARN                - CAM role ARN (required)
 *   TKE_WEB_IDENTITY_TOKEN_FILE - projected SA token path
 *                                 (default: /var/run/secrets/tokens/oidc-token)
 *   TKE_REGION                  - STS region (fallback: cos.region from config)
 *   TKE_PROVIDER_ID             - OIDC provider ID (optional)
 */
public class TKEOIDCCredentialsProvider extends AbstractCOSCachedCredentialsProvider {

    private static final Logger log = LoggerFactory.getLogger(TKEOIDCCredentialsProvider.class);

    private static final String DEFAULT_SA_TOKEN_PATH = "/var/run/secrets/tokens/oidc-token";
    private static final String DEFAULT_SESSION_NAME  = "TKE-COS-Session";
    private static final long   CREDENTIAL_DURATION_S = 3600L;

    private final String roleArn;
    private final String tokenFilePath;
    private final String providerId;
    private final String region;

    /**
     * @param regionFallback  value from cos.region in config.properties,
     *                        used when TKE_REGION env var is absent
     */
    public TKEOIDCCredentialsProvider(String regionFallback) {
        super(CREDENTIAL_DURATION_S);

        this.roleArn       = System.getenv("TKE_ROLE_ARN");
        this.providerId    = System.getenv("TKE_PROVIDER_ID");
        this.tokenFilePath = resolveTokenFilePath();
        this.region        = resolveRegion(regionFallback);

        if (roleArn == null || roleArn.isEmpty()) {
            throw new CosClientException(
                "TKE_ROLE_ARN env var is missing. " +
                "Ensure the ServiceAccount has annotation: " +
                "tke.cloud.tencent.com/role-arn=<CAM role ARN>"
            );
        }
        if (region == null || region.isEmpty()) {
            throw new CosClientException(
                "Region not specified. Set TKE_REGION env var or cos.region in config.properties."
            );
        }
        log.info("TKE OIDC provider ready - roleArn={}, tokenFile={}, region={}", roleArn, tokenFilePath, region);
    }

    @Override
    public void refresh() {
        updateCOSCredentials();
    }

    /**
     * Called by the SDK whenever credentials need to be refreshed.
     * Reads the projected SA token and exchanges it via STS.
     */
    @Override
    public COSCredentials fetchNewCOSCredentials() {
        try {
            String token = readTokenFile();

            AssumeRoleWithWebIdentityRequest req = new AssumeRoleWithWebIdentityRequest();
            req.setRoleArn(roleArn);
            req.setRoleSessionName(DEFAULT_SESSION_NAME);
            req.setWebIdentityToken(token);
            req.setDurationSeconds(CREDENTIAL_DURATION_S);
            if (providerId != null && !providerId.isEmpty()) {
                req.setProviderId(providerId);
            }

            AssumeRoleWithWebIdentityResponse resp = buildStsClient().AssumeRoleWithWebIdentity(req);
            Credentials creds = resp.getCredentials();

            if (creds == null || creds.getTmpSecretId() == null || creds.getTmpSecretKey() == null) {
                throw new CosClientException("STS returned incomplete credentials");
            }
            log.info("TKE OIDC credentials refreshed, expire at epoch={}", resp.getExpiredTime());

            return new BasicSessionCredentials(
                creds.getTmpSecretId(),
                creds.getTmpSecretKey(),
                creds.getToken()
            );
        } catch (CosClientException e) {
            throw e;
        } catch (Exception e) {
            throw new CosClientException("Failed to fetch TKE OIDC credentials", e);
        }
    }

    private String readTokenFile() throws IOException {
        String token = new String(Files.readAllBytes(Paths.get(tokenFilePath))).trim();
        if (token.isEmpty()) {
            throw new IOException("Token file is empty: " + tokenFilePath);
        }
        return token;
    }

    private StsClient buildStsClient() {
        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint("sts.tencentcloudapi.com");
        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        return new StsClient(new Credential("", ""), region, clientProfile);
    }

    private static String resolveTokenFilePath() {
        String fromEnv = System.getenv("TKE_WEB_IDENTITY_TOKEN_FILE");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        log.warn("TKE_WEB_IDENTITY_TOKEN_FILE not set, using default path: {}", DEFAULT_SA_TOKEN_PATH);
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
}
