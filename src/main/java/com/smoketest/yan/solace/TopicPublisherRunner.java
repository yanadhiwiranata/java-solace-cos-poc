package com.smoketest.yan.solace;

import com.smoketest.yan.config.AppConfig;
import com.solacesystems.jcsmp.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Scanner;

/**
 * Mode: publish-topic
 *
 * Publishes messages to a Solace topic.
 * Type a message and press Enter to publish. Type "exit" to quit.
 */
public class TopicPublisherRunner {

    private static final Logger log = LoggerFactory.getLogger(TopicPublisherRunner.class);

    public static void run(AppConfig cfg) throws JCSMPException {
        JCSMPSession session = SolaceSessionFactory.create(cfg);

        XMLMessageProducer producer = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
            @Override
            public void responseReceivedEx(Object correlationKey) {
                log.debug("ACK received for correlationKey={}", correlationKey);
            }

            @Override
            public void handleErrorEx(Object correlationKey, JCSMPException cause, long timestamp) {
                log.error("NACK for correlationKey={}: {}", correlationKey, cause.getMessage());
            }
        });

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

                if (input.isEmpty()) {
                    continue;
                }

                TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                msg.setText(input);
                msg.setDeliveryMode(DeliveryMode.PERSISTENT);
                msg.setCorrelationKey("seq-" + seq);
                msg.setApplicationMessageId("yan-topic-" + Instant.now().toEpochMilli());

                producer.send(msg, topic);
                log.info("[TOPIC PUBLISH] topic={} seq={} payload={}", topicName, seq, input);
                System.out.println("  → Published to topic [" + topicName + "]");
                seq++;
            }
        } finally {
            producer.close();
            session.closeSession();
            log.info("Session closed.");
        }
    }
}
