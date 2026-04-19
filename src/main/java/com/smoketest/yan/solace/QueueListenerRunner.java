package com.smoketest.yan.solace;

import com.smoketest.yan.config.AppConfig;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.FlowEvent;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mode: listen-queue
 *
 * Binds to a Solace queue using a guaranteed-delivery flow and prints every
 * received message. Auto-acknowledges. Runs forever until Ctrl+C.
 *
 * Resilience:
 * - 4-arg createFlow with FlowEventHandler: FLOW_DOWN schedules a rebind on a
 *   dedicated daemon thread ("solace-flow-rebind"), never on the JCSMP event thread.
 * - Session reconnect callback force-triggers rebind as belt-and-suspenders.
 * - FlowReceiver is stored in AtomicReference so concurrent close+replace is safe.
 * - onException only logs; it is NOT the authoritative rebind trigger.
 * - Message counter uses AtomicInteger to avoid data races on JCSMP dispatcher threads.
 * - Blocking uses a plain Object monitor instead of CountDownLatch so that
 *   onException cannot race with the shutdown hook to trigger cleanup twice.
 */
public class QueueListenerRunner {

    private static final Logger log = LoggerFactory.getLogger(QueueListenerRunner.class);

    private JCSMPSession session;
    private ConsumerFlowProperties flowProps;
    private XMLMessageListener messageListener;

    private final AtomicReference<FlowReceiver> flowRef = new AtomicReference<>();
    private final ScheduledExecutorService rebindExec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "solace-flow-rebind");
        t.setDaemon(true);
        return t;
    });

    public static void run(AppConfig cfg) throws JCSMPException, InterruptedException {
        new QueueListenerRunner().execute(cfg);
    }

    private void execute(AppConfig cfg) throws JCSMPException, InterruptedException {
        String queueName = cfg.solaceQueue();
        Queue queue = JCSMPFactory.onlyInstance().createQueue(queueName);

        flowProps = new ConsumerFlowProperties();
        flowProps.setEndpoint(queue);
        flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_AUTO);

        AtomicInteger count = new AtomicInteger();

        messageListener = new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage msg) {
                int n = count.incrementAndGet();
                String payload = extractPayload(msg);
                String destination = msg.getDestination() != null
                        ? msg.getDestination().getName() : "unknown";
                System.out.printf("%n[QUEUE RECEIVED #%d] destination=%s%n  payload : %s%n  msgId   : %s%n",
                        n, destination, payload, msg.getApplicationMessageId());
                log.info("[QUEUE RECEIVED #{}] destination={} payload={}", n, destination, payload);
            }

            @Override
            public void onException(JCSMPException e) {
                // FLOW_DOWN via FlowEventHandler is the authoritative rebind signal.
                // onException fires for per-message protocol errors; logging only.
                log.error("[QUEUE LISTENER] onException (not authoritative for rebind): {}", e.getMessage());
            }
        };

        session = SolaceSessionFactory.create(cfg, () -> {
            log.info("Session RECONNECTED — force-rebinding flow");
            scheduleRebind(0);
        });

        bindFlow();

        System.out.println("\n[QUEUE LISTENER] Bound to queue: " + queueName);
        System.out.println("Waiting for messages... Press Ctrl+C to stop.\n");

        final Object blocker = new Object();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutdown requested.");
            synchronized (blocker) { blocker.notifyAll(); }
        }));

        synchronized (blocker) {
            blocker.wait();
        }

        shutdown();
    }

    /**
     * Creates a new FlowReceiver with a FlowEventHandler (4th arg).
     * The 3rd arg is EndpointProperties — null means use the existing endpoint definition.
     * Must NOT be called directly from a JCSMP callback; use scheduleRebind() instead.
     */
    private void bindFlow() {
        try {
            FlowReceiver newFlow = session.createFlow(
                    messageListener,
                    flowProps,
                    null,                  // EndpointProperties — null = use existing endpoint
                    (src, event) -> {
                        if (event.getEvent() == FlowEvent.FLOW_DOWN) {
                            log.warn("FLOW_DOWN — scheduling rebind in 5 s");
                            scheduleRebind(5);
                        }
                    }
            );
            newFlow.start();

            FlowReceiver old = flowRef.getAndSet(newFlow);
            closeFlow(old);

            log.info("Flow bound and started successfully.");
        } catch (JCSMPException e) {
            log.error("bindFlow failed — retrying in 10 s: {}", e.getMessage());
            scheduleRebind(10);
        }
    }

    private void scheduleRebind(int delaySec) {
        try {
            rebindExec.schedule(this::bindFlow, delaySec, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // executor already shut down during clean exit — safe to ignore
        }
    }

    private void shutdown() {
        rebindExec.shutdownNow();
        closeFlow(flowRef.get());
        session.closeSession();
        log.info("Session closed.");
    }

    private static void closeFlow(FlowReceiver f) {
        if (f != null) {
            try { f.close(); } catch (Exception ignored) {}
        }
    }

    private static String extractPayload(BytesXMLMessage msg) {
        if (msg instanceof TextMessage) return ((TextMessage) msg).getText();
        byte[] bytes = msg.getBytes();
        return bytes != null ? new String(bytes) : "<empty>";
    }
}
