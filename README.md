# com.smoketest.yan

Proof-of-concept project for:

- **Solace** — publish and receive on both **topic** and **queue** (JCSMP)
- **Tencent COS** — read and write objects with **OIDC (TKE)** or **AKSK** authentication

---

## Build

```bash
cd com.smoketest.yan
mvn clean package -DskipTests
```

Produces a fat JAR: `target/yan-smoketest-1.0.0-fat.jar`

---

## Configure

Copy the example file and fill in your values:

```bash
cp config.properties.example config.properties
```

> `config.properties` is git-ignored so your credentials are never committed.

```properties
# Solace
solace.host=tcps://YOUR_SOLACE_HOST
solace.port=55443
solace.msg-vpn=YOUR_MSG_VPN
solace.username=YOUR_USERNAME
solace.password=YOUR_PASSWORD
solace.topic=smoketest/yan/topic/test
solace.queue=q.smoketest.yan

# Tencent COS
cos.region=ap-jakarta
cos.bucket=core-epc-1394748486
cos.read-key=yan/yan-test.txt
cos.write-key=yan/yan-test.txt
cos.write-content=ping

# Auth: aksk (local) or oidc (TKE)
cos.auth-mode=aksk
cos.secret-id=YOUR_SECRET_ID
cos.secret-key=YOUR_SECRET_KEY
```

---

## Run

All modes use the same JAR. Replace `--mode=` with the mode you want.

```bash
# ── Solace ──────────────────────────────────────────────────────────────────
java -jar target/yan-smoketest-1.0.0-fat.jar --mode=listen-topic
java -jar target/yan-smoketest-1.0.0-fat.jar --mode=publish-topic
java -jar target/yan-smoketest-1.0.0-fat.jar --mode=listen-queue
java -jar target/yan-smoketest-1.0.0-fat.jar --mode=publish-queue

# ── Tencent COS ─────────────────────────────────────────────────────────────
java -jar target/yan-smoketest-1.0.0-fat.jar --mode=cos-write
java -jar target/yan-smoketest-1.0.0-fat.jar --mode=cos-read

# ── Custom config path (optional) ───────────────────────────────────────────
java -jar target/yan-smoketest-1.0.0-fat.jar --mode=listen-topic --config=/etc/smoketest/config.properties
```

| Mode | What it does | Exits when |
|---|---|---|
| `listen-topic` | Subscribe to `solace.topic`, print every message | `Ctrl+C` |
| `publish-topic` | Interactive prompt → publish to `solace.topic` | type `exit` |
| `listen-queue` | Bind flow to `solace.queue`, print every message | `Ctrl+C` |
| `publish-queue` | Interactive prompt → publish persistent msgs to `solace.queue` | type `exit` |
| `cos-write` | Write `cos.write-content` to `cos.write-key` in `cos.bucket` | immediately |
| `cos-read` | Download `cos.read-key` from `cos.bucket` and print it | immediately |

### Typical 4-terminal Solace test

Open 4 terminals in the project directory and run one command per terminal:

```
Terminal 1  →  listen-topic
Terminal 2  →  publish-topic
Terminal 3  →  listen-queue
Terminal 4  →  publish-queue
```

> **Topic vs Queue**: `listen-topic` uses a direct (non-persistent) subscription — messages published while it is disconnected are lost. For guaranteed delivery, use `listen-queue` against a queue that has a topic subscription configured on the broker.
>
> The queue (`solace.queue`) **must already exist** on the Solace broker. Add a topic subscription to it via the Solace admin console or CLI if you want topic-published messages to flow into the queue.

### Publisher sample output

```
[TOPIC PUBLISHER] Connected to topic: yan-topic
Type a message and press Enter. Type 'exit' to quit.

[msg #1] > hello world
  → Published to topic [yan-topic]
[msg #2] > exit
Exiting topic publisher.
```

---

## COS Credential Priority

When the app starts a COS operation, `CosClientFactory` resolves credentials in the following order — stopping at the first that succeeds:

```
1. TKE_ROLE_ARN env var present?  (auto-injected by TKE into the pod)
   └─ YES → TKEOIDCCredentialsProvider
             ├─ reads projected SA token from TKE_WEB_IDENTITY_TOKEN_FILE
             │   (default: /var/run/secrets/tokens/oidc-token)
             ├─ calls STS AssumeRoleWithWebIdentity with the token
             ├─ caches credentials and auto-refreshes (AbstractCOSCachedCredentialsProvider)
             └─ fails (missing role ARN, token unreadable, STS error)?
                 └─ fall through to step 3

2. cos.auth-mode=oidc in config.properties?
   └─ YES → TKEOIDCCredentialsProvider (same flow as above)
             └─ fails?
                 └─ fall through to step 3

3. AKSK (last resort)
   ├─ cos.secret-id and cos.secret-key set in config.properties?
   │   └─ YES → BasicCOSCredentials (static secret-id / secret-key)
   └─ NO → error: no valid credentials available
```

> **In Kubernetes (TKE):** `TKE_ROLE_ARN` is auto-injected when the pod uses an annotated ServiceAccount. No changes to `config.properties` are needed.
>
> **Local development:** Set `cos.auth-mode=aksk` with `cos.secret-id` / `cos.secret-key` in `config.properties`.

---

## COS Authentication Setup

### AKSK (local development)

Set in `config.properties`:
```properties
cos.auth-mode=aksk
cos.secret-id=YOUR_SECRET_ID
cos.secret-key=YOUR_SECRET_KEY
```

### TKE Kubernetes ServiceAccount (recommended for k8s)

`TKEOIDCCredentialsProvider` extends the SDK's `AbstractCOSCachedCredentialsProvider`. On each refresh it reads the projected ServiceAccount OIDC token and exchanges it for temporary credentials via STS `AssumeRoleWithWebIdentity`. The SDK handles caching and scheduling the refresh automatically.

**1. Annotate the ServiceAccount** with the required TKE OIDC annotations:
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: yan-smoketest
  annotations:
    tke.cloud.tencent.com/role-arn: "qcs::cam::uin/<UIN>:roleName/<RoleName>"
    tke.cloud.tencent.com/audience: "sts.cloud.tencent.com"
    tke.cloud.tencent.com/token-expiration: "86400"
```

**2. Reference it in the Pod/Deployment:**
```yaml
spec:
  serviceAccountName: yan-smoketest
```

TKE **auto-injects** these env vars into every pod using that ServiceAccount:

| Variable | Description | Fallback |
|---|---|---|
| `TKE_ROLE_ARN` | CAM role ARN | *(required — no fallback)* |
| `TKE_WEB_IDENTITY_TOKEN_FILE` | Path to projected SA token | `/var/run/secrets/tokens/oidc-token` |
| `TKE_REGION` | STS region | `cos.region` in `config.properties` |
| `TKE_PROVIDER_ID` | OIDC provider ID | *(optional)* |

---

## Project Structure

```
com.smoketest.yan/
├── config.properties                       ← edit this before running
├── pom.xml
└── src/main/java/com/smoketest/yan/
    ├── Main.java                           ← entry point, mode routing
    ├── config/
    │   └── AppConfig.java                 ← reads config.properties
    ├── solace/
    │   ├── SolaceSessionFactory.java      ← creates JCSMP session
    │   ├── TopicPublisherRunner.java      ← mode: publish-topic
    │   ├── QueuePublisherRunner.java      ← mode: publish-queue
    │   ├── TopicListenerRunner.java       ← mode: listen-topic
    │   └── QueueListenerRunner.java       ← mode: listen-queue
    └── cos/
        ├── TKEOIDCCredentialsProvider.java ← OIDC via STS (extends AbstractCOSCachedCredentialsProvider)
        ├── CosClientFactory.java           ← credential resolution + COSClient creation
        ├── CosReadRunner.java              ← mode: cos-read
        └── CosWriteRunner.java             ← mode: cos-write
```

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| `com.solacesystems:sol-jcsmp` | 10.22.0 | Solace JCSMP API |
| `com.qcloud:cos_api` | 5.6.259 | Tencent COS SDK (`AbstractCOSCachedCredentialsProvider`, etc.) |
| `com.tencentcloudapi:tencentcloud-sdk-java-sts` | 3.1.1322 | STS `AssumeRoleWithWebIdentity` for OIDC |
| `ch.qos.logback:logback-classic` | 1.4.14 | Logging |
| `com.google.code.gson:gson` | 2.10.1 | JSON |
