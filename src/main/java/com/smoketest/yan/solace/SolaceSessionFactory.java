package com.smoketest.yan.solace;

import com.smoketest.yan.config.AppConfig;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.SessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates and connects a JCSMP session.
 *
 * Channel properties (reconnect, keepalive) must be set on the
 * {@link JCSMPChannelProperties} object — NOT via top-level JCSMPProperties
 * string constants, which do not exist in JCSMP 10.x.
 */
public class SolaceSessionFactory {

    private static final Logger log = LoggerFactory.getLogger(SolaceSessionFactory.class);

    /**
     * @param onReconnect called from the JCSMP event thread each time the
     *                    session successfully reconnects; may be null.
     *                    Callers must dispatch expensive work to a daemon thread
     *                    to avoid blocking the JCSMP event dispatcher.
     */
    public static JCSMPSession create(AppConfig cfg, Runnable onReconnect) throws JCSMPException {
        JCSMPProperties props = new JCSMPProperties();
        props.setProperty(JCSMPProperties.HOST,     cfg.solaceHost() + ":" + cfg.solacePort());
        props.setProperty(JCSMPProperties.VPN_NAME,  cfg.solaceMsgVpn());
        props.setProperty(JCSMPProperties.USERNAME,  cfg.solaceUsername());
        props.setProperty(JCSMPProperties.PASSWORD,  cfg.solacePassword());
        props.setProperty(JCSMPProperties.SSL_VALIDATE_CERTIFICATE, cfg.solaceSslValidate());
        props.setProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);

        JCSMPChannelProperties ch =
                (JCSMPChannelProperties) props.getProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES);
        ch.setConnectRetries(cfg.solaceConnectRetries());
        ch.setReconnectRetries(-1);                      // retry forever
        ch.setReconnectRetryWaitInMillis(cfg.solaceReconnectWaitMs());
        ch.setKeepAliveIntervalInMillis(10_000);         // heartbeat every 10 s
        ch.setKeepAliveLimit(3);                         // 3 missed → declare dead, trigger reconnect

        log.info("Connecting to Solace: {}:{} vpn={} user={}",
                cfg.solaceHost(), cfg.solacePort(), cfg.solaceMsgVpn(), cfg.solaceUsername());

        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(props, null, event -> {
            log.info("Solace session event: {}", event.getEvent());
            if (event.getEvent() == SessionEvent.RECONNECTED && onReconnect != null) {
                onReconnect.run();
            }
        });
        session.connect();

        log.info("Solace session connected.");
        return session;
    }
}
