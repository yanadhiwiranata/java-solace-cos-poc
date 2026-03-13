package com.smoketest.yan.solace;

import com.smoketest.yan.config.AppConfig;
import com.solacesystems.jcsmp.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/**
 * Mode: listen-topic
 *
 * Subscribes to a Solace topic and prints every received message.
 * Runs forever until Ctrl+C.
 *
 * Note: This uses a direct (non-persistent) topic subscription via XMLMessageConsumer.
 * Messages published while not connected are NOT guaranteed to be received.
 * For guaranteed delivery on topics, use a queue with a topic subscription.
 */
public class TopicListenerRunner {

    private static final Logger log = LoggerFactory.getLogger(TopicListenerRunner.class);

    public static void run(AppConfig cfg) throws JCSMPException, InterruptedException {
        JCSMPSession session = SolaceSessionFactory.create(cfg);

        String topicName = cfg.solaceTopic();
        Topic topic = JCSMPFactory.onlyInstance().createTopic(topicName);

        final CountDownLatch latch = new CountDownLatch(1);

        XMLMessageConsumer consumer = session.getMessageConsumer(new XMLMessageListener() {
            private int count = 0;

            @Override
            public void onReceive(BytesXMLMessage msg) {
                count++;
                String payload = extractPayload(msg);
                String destination = msg.getDestination() != null
                        ? msg.getDestination().getName() : "unknown";

                System.out.printf("%n[TOPIC RECEIVED #%d] destination=%s%n  payload : %s%n  msgId   : %s%n",
                        count, destination, payload, msg.getApplicationMessageId());
                log.info("[TOPIC RECEIVED #{}] destination={} payload={}", count, destination, payload);
            }

            @Override
            public void onException(JCSMPException e) {
                log.error("[TOPIC LISTENER] Exception: {}", e.getMessage(), e);
                latch.countDown();
            }
        });

        session.addSubscription(topic);
        consumer.start();

        System.out.println("\n[TOPIC LISTENER] Subscribed to topic: " + topicName);
        System.out.println("Waiting for messages... Press Ctrl+C to stop.\n");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutdown requested.");
            latch.countDown();
        }));

        latch.await();

        consumer.close();
        session.closeSession();
        log.info("Session closed.");
    }

    private static String extractPayload(BytesXMLMessage msg) {
        if (msg instanceof TextMessage) {
            return ((TextMessage) msg).getText();
        }
        byte[] bytes = msg.getBytes();
        return bytes != null ? new String(bytes) : "<empty>";
    }
}
