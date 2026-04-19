package com.smoketest.yan.solace;

import com.smoketest.yan.config.AppConfig;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.Destination;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Scanner;

/**
 * Mode: publish-topic
 *
 * Publishes messages to a Solace topic.
 * Type a message and press Enter to publish. Type "exit" to quit.
 *
 * DeliveryMode.PERSISTENT is intentional: the broker spools these messages to
 * any durable queue that has a matching topic subscription (e.g. yan-queue →
 * subscription "yan-topic"), enabling the QueueListenerRunner to receive them
 * with guaranteed delivery.
 *
 * Resilience: same volatile-producer + sendWithRetry pattern as QueuePublisherRunner.
 */
public class TopicPublisherRunner {

    private static final Logger log = LoggerFactory.getLogger(TopicPublisherRunner.class);
    private static final int MAX_SEND_ATTEMPTS = 3;

    private JCSMPSession session;
    private volatile XMLMessageProducer producer;

    public static void run(AppConfig cfg) throws JCSMPException {
        new TopicPublisherRunner().execute(cfg);
    }

    private void execute(AppConfig cfg) throws JCSMPException {
        session = SolaceSessionFactory.create(cfg, () -> {
            Thread t = new Thread(() -> {
                try {
                    rebuildProducer();
                } catch (JCSMPException e) {
                    String errMsg = "[REBUILD FAILED] Could not rebuild producer after reconnect: " + e.getMessage();
                    log.error(errMsg, e);
                    System.err.println(errMsg);
                }
            }, "solace-producer-rebuild");
            t.setDaemon(true);
            t.start();
        });
        producer = createProducer();

        String topicName = cfg.solaceTopic();
        Topic topic = JCSMPFactory.onlyInstance().createTopic(topicName);

        System.out.println("\n[TOPIC PUBLISHER] Connected to topic: " + topicName);
        System.out.println("Type a message and press Enter. Type 'exit' to quit.\n");

        try (Scanner scanner = new Scanner(System.in)) {
            int seq = 1;
            while (true) {
                System.out.print("[msg #" + seq + "] > ");
                String input = scanner.nextLine().trim();

                if ("exit".equalsIgnoreCase(input)) {
                    System.out.println("Exiting topic publisher.");
                    break;
                }
                if (input.isEmpty()) continue;

                TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                msg.setText(input);
                msg.setDeliveryMode(DeliveryMode.PERSISTENT);
                msg.setCorrelationKey("seq-" + seq);
                msg.setApplicationMessageId("yan-topic-" + Instant.now().toEpochMilli());

                try {
                    sendWithRetry(msg, topic);
                    log.info("[TOPIC PUBLISH] topic={} seq={} payload={}", topicName, seq, input);
                    System.out.println("  → Published to topic [" + topicName + "]");
                    seq++;
                } catch (JCSMPException e) {
                    String errMsg = "[PUBLISH FAILED] seq=" + seq + " topic=" + topicName
                            + " — all " + MAX_SEND_ATTEMPTS + " attempts exhausted: " + e.getMessage();
                    log.error(errMsg, e);
                    System.err.println(errMsg);
                    throw e;
                }
            }
        } finally {
            closeQuietly(producer);
            session.closeSession();
            log.info("Session closed.");
        }
    }

    private XMLMessageProducer createProducer() throws JCSMPException {
        return session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
            @Override
            public void responseReceivedEx(Object correlationKey) {
                log.debug("ACK received for correlationKey={}", correlationKey);
            }

            @Override
            public void handleErrorEx(Object correlationKey, JCSMPException cause, long timestamp) {
                String errMsg = "[NACK] Broker rejected message correlationKey=" + correlationKey
                        + ": " + cause.getMessage();
                log.error(errMsg, cause);
                System.err.println(errMsg);
            }
        });
    }

    /**
     * Rebuilds the producer and throws if the rebuild itself fails, so
     * sendWithRetry() counts the rebuild failure as a failed attempt rather
     * than silently retrying on a still-stale producer.
     */
    private synchronized void rebuildProducer() throws JCSMPException {
        log.info("Rebuilding XMLMessageProducer after reconnect…");
        XMLMessageProducer old = producer;
        try {
            producer = createProducer();
            log.info("Producer rebuilt successfully.");
        } catch (JCSMPException e) {
            log.error("Failed to rebuild producer", e);
            throw e;
        } finally {
            closeQuietly(old);
        }
    }

    private void sendWithRetry(BytesXMLMessage msg, Destination dest) throws JCSMPException {
        for (int attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
            try {
                producer.send(msg, dest);
                return;
            } catch (JCSMPException e) {
                log.warn("Send attempt {}/{} failed: {}", attempt, MAX_SEND_ATTEMPTS, e.getMessage());
                if (attempt == MAX_SEND_ATTEMPTS) throw e;
                rebuildProducer();
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static void closeQuietly(XMLMessageProducer p) {
        if (p != null) {
            try { p.close(); } catch (Exception ignored) {}
        }
    }
}
