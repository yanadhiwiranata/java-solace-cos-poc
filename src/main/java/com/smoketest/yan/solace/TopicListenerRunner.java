package com.smoketest.yan.solace;

import com.smoketest.yan.config.AppConfig;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mode: listen-topic
 *
 * Subscribes to a Solace topic using a direct (non-persistent) XMLMessageConsumer
 * and prints every received message. Runs forever until Ctrl+C.
 *
 * Note: messages published while this consumer is disconnected are NOT guaranteed
 * to be received. For guaranteed delivery use a durable queue with a topic
 * subscription and QueueListenerRunner.
 *
 * Resilience:
 * - REAPPLY_SUBSCRIPTIONS=true (set in SolaceSessionFactory) re-registers the
 *   topic subscription automatically after reconnect.
 * - The session reconnect callback additionally stop/starts the XMLMessageConsumer
 *   on a daemon thread ("solace-consumer-restart") as belt-and-suspenders in case
 *   the consumer stalls after the JCSMP reconnect path completes.
 * - onException only logs; it does NOT shut down the app.
 * - Message counter uses AtomicInteger — JCSMP dispatcher threads call onReceive.
 * - Blocking uses a plain Object monitor so there is no CountDownLatch race between
 *   onException and the shutdown hook.
 */
public class TopicListenerRunner {

    private static final Logger log = LoggerFactory.getLogger(TopicListenerRunner.class);

    private JCSMPSession session;
    private volatile XMLMessageConsumer consumer;

    public static void run(AppConfig cfg) throws JCSMPException, InterruptedException {
        new TopicListenerRunner().execute(cfg);
    }

    private void execute(AppConfig cfg) throws JCSMPException, InterruptedException {
        String topicName = cfg.solaceTopic();
        Topic topic = JCSMPFactory.onlyInstance().createTopic(topicName);
        AtomicInteger count = new AtomicInteger();

        session = SolaceSessionFactory.create(cfg, () -> {
            Thread t = new Thread(this::restartConsumer, "solace-consumer-restart");
            t.setDaemon(true);
            t.start();
        });

        consumer = session.getMessageConsumer(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage msg) {
                int n = count.incrementAndGet();
                String payload = extractPayload(msg);
                String destination = msg.getDestination() != null
                        ? msg.getDestination().getName() : "unknown";
                System.out.printf("%n[TOPIC RECEIVED #%d] destination=%s%n  payload : %s%n  msgId   : %s%n",
                        n, destination, payload, msg.getApplicationMessageId());
                log.info("[TOPIC RECEIVED #{}] destination={} payload={}", n, destination, payload);
            }

            @Override
            public void onException(JCSMPException e) {
                // Log only — reconnect + REAPPLY_SUBSCRIPTIONS handles recovery.
                log.error("[TOPIC LISTENER] onException: {}", e.getMessage());
            }
        });

        session.addSubscription(topic);
        consumer.start();

        System.out.println("\n[TOPIC LISTENER] Subscribed to topic: " + topicName);
        System.out.println("Waiting for messages... Press Ctrl+C to stop.\n");

        final Object blocker = new Object();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutdown requested.");
            synchronized (blocker) { blocker.notifyAll(); }
        }));

        synchronized (blocker) {
            blocker.wait();
        }

        consumer.close();
        session.closeSession();
        log.info("Session closed.");
    }

    /**
     * Called from a daemon thread after session reconnect.
     * stop() + start() ensures the consumer is active even if JCSMP's internal
     * reapply-subscriptions path left the consumer in a paused state.
     */
    private synchronized void restartConsumer() {
        log.info("Restarting XMLMessageConsumer after session reconnect…");
        try {
            consumer.stop();
            consumer.start();
            log.info("Consumer restarted successfully.");
        } catch (Exception e) {
            log.error("Failed to restart consumer after reconnect", e);
        }
    }

    private static String extractPayload(BytesXMLMessage msg) {
        if (msg instanceof TextMessage) return ((TextMessage) msg).getText();
        byte[] bytes = msg.getBytes();
        return bytes != null ? new String(bytes) : "<empty>";
    }
}
