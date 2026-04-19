package com.smoketest.yan.solace;

import com.smoketest.yan.config.AppConfig;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.Destination;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Scanner;

/**
 * Mode: publish-queue
 *
 * Publishes messages directly to a Solace queue endpoint.
 * Type a message and press Enter to publish. Type "exit" to quit.
 *
 * Resilience:
 * - producer is volatile so the reconnect-thread write is visible to the main thread.
 * - rebuildProducer() is synchronized: only one rebuild at a time regardless of
 *   whether it was triggered by the reconnect callback or a failed send.
 * - sendWithRetry() attempts up to 3 sends, rebuilding the producer between retries
 *   with exponential back-off (500 ms, 1 000 ms).
 * - The reconnect callback dispatches the rebuild to a daemon thread so the JCSMP
 *   session event dispatcher is never blocked.
 */
public class QueuePublisherRunner {

    private static final Logger log = LoggerFactory.getLogger(QueuePublisherRunner.class);
    private static final int MAX_SEND_ATTEMPTS = 3;

    private JCSMPSession session;
    private volatile XMLMessageProducer producer;

    public static void run(AppConfig cfg) throws JCSMPException {
        new QueuePublisherRunner().execute(cfg);
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

        String queueName = cfg.solaceQueue();
        Queue queue = JCSMPFactory.onlyInstance().createQueue(queueName);

        System.out.println("\n[QUEUE PUBLISHER] Connected to queue: " + queueName);
        System.out.println("Type a message and press Enter. Type 'exit' to quit.\n");

        try (Scanner scanner = new Scanner(System.in)) {
            int seq = 1;
            while (true) {
                System.out.print("[msg #" + seq + "] > ");
                String input = scanner.nextLine().trim();

                if ("exit".equalsIgnoreCase(input)) {
                    System.out.println("Exiting queue publisher.");
                    break;
                }
                if (input.isEmpty()) continue;

                TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                msg.setText(input);
                msg.setDeliveryMode(DeliveryMode.PERSISTENT);
                msg.setCorrelationKey("seq-" + seq);
                msg.setApplicationMessageId("yan-queue-" + Instant.now().toEpochMilli());

                try {
                    sendWithRetry(msg, queue);
                    log.info("[QUEUE PUBLISH] queue={} seq={} payload={}", queueName, seq, input);
                    System.out.println("  → Published to queue [" + queueName + "]");
                    seq++;
                } catch (JCSMPException e) {
                    String errMsg = "[PUBLISH FAILED] seq=" + seq + " queue=" + queueName
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
