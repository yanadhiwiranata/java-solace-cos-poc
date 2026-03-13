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
cos.region=ap-guangzhou
cos.bucket=your-bucket-1234567890
cos.read-key=smoketest/read-test.txt
cos.write-key=smoketest/write-test.txt
cos.write-content=Hello from com.smoketest.yan PoC!

# Auth: aksk (local) or oidc (TKE)
cos.auth-mode=aksk
cos.secret-id=YOUR_SECRET_ID
cos.secret-key=YOUR_SECRET_KEY
```

---

## Run — Multiple Consoles

Open **4 separate terminals** from the project directory.

### Terminal 1 — Listen on Topic

```bash
java -jar target/yan-smoketest-1.0.0-fat.jar --mode=listen-topic
```

Subscribes to `solace.topic` and prints every message received. Runs until `Ctrl+C`.

> **Note**: Direct topic subscription receives only messages published while this consumer is connected (non-persistent). For guaranteed delivery use a queue with a topic subscription.

---

### Terminal 2 — Publish to Topic

```bash
java -jar target/yan-smoketest-1.0.0-fat.jar --mode=publish-topic
```

Interactive prompt — type a message and press `Enter` to publish. Type `exit` to quit.

```
[TOPIC PUBLISHER] Connected to topic: smoketest/yan/topic/test
Type a message and press Enter. Type 'exit' to quit.

[msg #1] > hello world
  → Published to topic [smoketest/yan/topic/test]
[msg #2] > exit
Exiting topic publisher.
```

---

### Terminal 3 — Listen on Queue

```bash
java -jar target/yan-smoketest-1.0.0-fat.jar --mode=listen-queue
```

Binds to `solace.queue` using a flow (guaranteed delivery). Runs until `Ctrl+C`.

> The queue **must already exist** on the Solace broker. If you want queue listeners to also receive topic messages, add a topic subscription to the queue via the Solace admin console / CLI.

---

### Terminal 4 — Publish to Queue

```bash
java -jar target/yan-smoketest-1.0.0-fat.jar --mode=publish-queue
```

Interactive prompt — publishes persistent messages directly to the queue endpoint.

---

## Run — COS

### Write an object

```bash
java -jar target/yan-smoketest-1.0.0-fat.jar --mode=cos-write
```

### Read an object

```bash
java -jar target/yan-smoketest-1.0.0-fat.jar --mode=cos-read
```

---

## COS Authentication

### AKSK (local development)

Set in `config.properties`:
```properties
cos.auth-mode=aksk
cos.secret-id=YOUR_SECRET_ID
cos.secret-key=YOUR_SECRET_KEY
```

### OIDC via TKE (Kubernetes)

Set in `config.properties`:
```properties
cos.auth-mode=oidc
```

The following **must be provided as environment variables** (TKE auto-injects them when using an annotated ServiceAccount):

| Variable                    | Description                     |
|-----------------------------|---------------------------------|
| `TKE_ROLE_ARN`              | CAM role ARN                    |
| `TKE_WEB_IDENTITY_TOKEN_FILE` | Path to OIDC token file       |
| `TKE_REGION`                | Tencent Cloud region            |
| `TKE_PROVIDER_ID`           | OIDC provider ID (optional)     |

Credentials are automatically refreshed 5 minutes before expiry.

---

## Custom config path

```bash
java -jar target/yan-smoketest-1.0.0-fat.jar --mode=listen-topic --config=/etc/smoketest/config.properties
```

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
        ├── TKEOIDCCredentialsProvider.java ← OIDC credentials refresh
        ├── CosClientFactory.java           ← creates COSClient (oidc or aksk)
        ├── CosReadRunner.java              ← mode: cos-read
        └── CosWriteRunner.java             ← mode: cos-write
```

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| `com.solacesystems:sol-jcsmp` | 10.22.0 | Solace JCSMP API |
| `com.qcloud:cos_api` | 5.6.259 | Tencent COS SDK |
| `com.tencentcloudapi:tencentcloud-sdk-java-sts` | 3.1.1322 | STS AssumeRole for OIDC |
| `ch.qos.logback:logback-classic` | 1.4.14 | Logging |
| `com.google.code.gson:gson` | 2.10.1 | JSON |
