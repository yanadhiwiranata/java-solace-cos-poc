package com.smoketest.yan.solace;

import com.smoketest.yan.config.AppConfig;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates and connects a JCSMP session using AppConfig values.
 */
public class SolaceSessionFactory {

    private static final Logger log = LoggerFactory.getLogger(SolaceSessionFactory.class);

    public static JCSMPSession create(AppConfig cfg) throws JCSMPException {
        JCSMPProperties props = new JCSMPProperties();

        props.setProperty(JCSMPProperties.HOST,
                cfg.solaceHost() + ":" + cfg.solacePort());
        props.setProperty(JCSMPProperties.VPN_NAME,  cfg.solaceMsgVpn());
        props.setProperty(JCSMPProperties.USERNAME,  cfg.solaceUsername());
        props.setProperty(JCSMPProperties.PASSWORD,  cfg.solacePassword());

        // Connection/reconnect retry settings (JCSMP 10.x property names)
        props.setProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES_CONNECT_RETRIES,
                cfg.solaceConnectRetries());
        props.setProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES_RECONNECT_RETRIES,
                cfg.solaceReconnectRetries());
        props.setProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES_RECONNECT_RETRY_WAIT_IN_MILLIS,
                cfg.solaceReconnectWaitMs());

        // SSL settings
        props.setProperty(JCSMPProperties.SSL_VALIDATE_CERTIFICATE,
                cfg.solaceSslValidate());

        // Reapply subscriptions after reconnect
        props.setProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);

        log.info("Connecting to Solace: {}:{} vpn={} user={}",
                cfg.solaceHost(), cfg.solacePort(),
                cfg.solaceMsgVpn(), cfg.solaceUsername());

        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(props);
        session.connect();

        log.info("Solace session connected.");
        return session;
    }
}
