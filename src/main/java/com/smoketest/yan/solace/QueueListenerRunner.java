package com.smoketest.yan.solace;

import com.smoketest.yan.config.AppConfig;
import com.solacesystems.jcsmp.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/**
 * Mode: listen-queue
 *
 * Binds to a Solace queue using a flow (guaranteed delivery) and prints
 * every received message. Auto-acknowledges each message.
 * Runs forever until Ctrl+C.
 *
 * The queue must already exist on the broker.
 */
public class QueueListenerRunner {

    private static final Logger log = LoggerFactory.getLogger(QueueListenerRunner.class);

    public static void run(AppConfig cfg) throws JCSMPException, InterruptedException {
        JCSMPSession session = SolaceSessionFactory.create(cfg);

        String queueName = cfg.solaceQueue();
        Queue queue = JCSMPFactory.onlyInstance().createQueue(queueName);

        ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
        flowProps.setEndpoint(queue);
        flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_AUTO);

        EndpointProperties endpointProps = new EndpointProperties();
        endpointProps.setPermission(EndpointProperties.PERMISSION_CONSUME);
        endpointProps.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);

        final CountDownLatch latch = new CountDownLatch(1);

        FlowReceiver receiver = session.createFlow(new XMLMessageListener() {
            private int count = 0;

            @Override
            public void onReceive(BytesXMLMessage msg) {
                count++;
                String payload = extractPayload(msg);
                String destination = msg.getDestination() != null
                        ? msg.getDestination().getName() : "unknown";

                System.out.printf("%n[QUEUE RECEIVED #%d] destination=%s%n  payload : %s%n  msgId   : %s%n",
                        count, destination, payload, msg.getApplicationMessageId());
                log.info("[QUEUE RECEIVED #{}] destination={} payload={}", count, destination, payload);
            }

            @Override
            public void onException(JCSMPException e) {
                log.error("[QUEUE LISTENER] Exception: {}", e.getMessage(), e);
                latch.countDown();
            }
        }, flowProps, endpointProps);

        receiver.start();

        System.out.println("\n[QUEUE LISTENER] Bound to queue: " + queueName);
        System.out.println("Waiting for messages... Press Ctrl+C to stop.\n");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutdown requested.");
            latch.countDown();
        }));

        latch.await();

        receiver.close();
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
