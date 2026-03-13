package com.smoketest.yan.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Loads configuration from config.properties.
 * Looks in: (1) path given via --config=<path>, (2) ./config.properties.
 */
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private final Properties props = new Properties();

    public AppConfig(String configPath) {
        load(configPath);
    }

    private void load(String configPath) {
        String path = (configPath != null) ? configPath : "config.properties";
        try (InputStream in = Files.exists(Paths.get(path))
                ? new FileInputStream(path)
                : getClass().getClassLoader().getResourceAsStream("config.properties")) {

            if (in == null) {
                throw new IllegalStateException("config.properties not found at: " + path);
            }
            props.load(in);
            log.info("Loaded config from: {}", path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config from: " + path, e);
        }
    }

    public String get(String key) {
        String val = props.getProperty(key);
        if (val == null) {
            throw new IllegalStateException("Missing required config key: " + key);
        }
        return val.trim();
    }

    public String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue).trim();
    }

    public int getInt(String key, int defaultValue) {
        String val = props.getProperty(key);
        return (val != null) ? Integer.parseInt(val.trim()) : defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = props.getProperty(key);
        return (val != null) ? Boolean.parseBoolean(val.trim()) : defaultValue;
    }

    // ---- Solace getters ------------------------------------------------

    public String solaceHost()         { return get("solace.host"); }
    public int    solacePort()         { return getInt("solace.port", 55555); }
    public String solaceMsgVpn()       { return get("solace.msg-vpn"); }
    public String solaceUsername()     { return get("solace.username"); }
    public String solacePassword()     { return get("solace.password"); }
    public int    solaceConnectRetries()     { return getInt("solace.connect-retries", 3); }
    public int    solaceReconnectRetries()   { return getInt("solace.reconnect-retries", 10); }
    public int    solaceReconnectWaitMs()    { return getInt("solace.reconnect-retry-wait-ms", 3000); }
    public boolean solaceSslValidate()       { return getBoolean("solace.ssl-validate-certificate", false); }
    public String solaceTopic()        { return get("solace.topic"); }
    public String solaceQueue()        { return get("solace.queue"); }

    // ---- COS getters ---------------------------------------------------

    public String cosRegion()          { return get("cos.region"); }
    public String cosBucket()          { return get("cos.bucket"); }
    public String cosReadKey()         { return get("cos.read-key"); }
    public String cosWriteKey()        { return get("cos.write-key"); }
    public String cosWriteContent()    { return get("cos.write-content", "Hello from com.smoketest.yan!"); }
    public String cosAuthMode()        { return get("cos.auth-mode", "aksk"); }
    public String cosSecretId()        { return get("cos.secret-id", ""); }
    public String cosSecretKey()       { return get("cos.secret-key", ""); }
}
