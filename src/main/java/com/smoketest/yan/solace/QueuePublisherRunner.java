package com.smoketest.yan.solace;

import com.smoketest.yan.config.AppConfig;
import com.solacesystems.jcsmp.*;
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
 * Note: The queue must already exist on the broker.
 */
public class QueuePublisherRunner {

    private static final Logger log = LoggerFactory.getLogger(QueuePublisherRunner.class);

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

                if (input.isEmpty()) {
                    continue;
                }

                TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                msg.setText(input);
                // Persistent delivery mode is required for queues to guarantee delivery
                msg.setDeliveryMode(DeliveryMode.PERSISTENT);
                msg.setCorrelationKey("seq-" + seq);
                msg.setApplicationMessageId("yan-queue-" + Instant.now().toEpochMilli());

                producer.send(msg, queue);
                log.info("[QUEUE PUBLISH] queue={} seq={} payload={}", queueName, seq, input);
                System.out.println("  → Published to queue [" + queueName + "]");
                seq++;
            }
        } finally {
            producer.close();
            session.closeSession();
            log.info("Session closed.");
        }
    }
}
