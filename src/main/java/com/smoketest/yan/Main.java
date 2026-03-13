package com.smoketest.yan;

import com.smoketest.yan.config.AppConfig;
import com.smoketest.yan.cos.CosReadRunner;
import com.smoketest.yan.cos.CosWriteRunner;
import com.smoketest.yan.solace.QueueListenerRunner;
import com.smoketest.yan.solace.QueuePublisherRunner;
import com.smoketest.yan.solace.TopicListenerRunner;
import com.smoketest.yan.solace.TopicPublisherRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * yan-smoketest — PoC entry point.
 *
 * Usage:
 *   java -jar yan-smoketest-1.0.0-fat.jar --mode=<MODE> [--config=<path/to/config.properties>]
 *
 * Available modes:
 *   publish-topic    Publish messages to a Solace topic (interactive console)
 *   publish-queue    Publish messages to a Solace queue (interactive console)
 *   listen-topic     Subscribe to a Solace topic and print received messages (runs forever)
 *   listen-queue     Bind to a Solace queue and print received messages (runs forever)
 *   cos-read         Read an object from Tencent COS
 *   cos-write        Write an object to Tencent COS
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        String mode       = null;
        String configPath = null;

        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                mode = arg.substring("--mode=".length()).trim();
            } else if (arg.startsWith("--config=")) {
                configPath = arg.substring("--config=".length()).trim();
            }
        }

        if (mode == null || mode.isEmpty()) {
            printUsage();
            System.exit(1);
        }

        log.info("Starting yan-smoketest | mode={} | config={}", mode,
                configPath != null ? configPath : "config.properties");

        AppConfig cfg = new AppConfig(configPath);

        switch (mode.toLowerCase()) {
            case "publish-topic"  -> TopicPublisherRunner.run(cfg);
            case "publish-queue"  -> QueuePublisherRunner.run(cfg);
            case "listen-topic"   -> TopicListenerRunner.run(cfg);
            case "listen-queue"   -> QueueListenerRunner.run(cfg);
            case "cos-read"       -> CosReadRunner.run(cfg);
            case "cos-write"      -> CosWriteRunner.run(cfg);
            default -> {
                System.err.println("Unknown mode: " + mode);
                printUsage();
                System.exit(1);
            }
        }
    }

    private static void printUsage() {
        System.out.println("""
                ╔══════════════════════════════════════════════════════════════╗
                ║           yan-smoketest — Solace + Tencent COS PoC          ║
                ╚══════════════════════════════════════════════════════════════╝

                Usage:
                  java -jar yan-smoketest-1.0.0-fat.jar --mode=<MODE> [--config=<path>]

                Modes:
                  publish-topic    Publish messages to a Solace topic (interactive)
                  publish-queue    Publish messages to a Solace queue (interactive)
                  listen-topic     Listen to a Solace topic forever (Ctrl+C to stop)
                  listen-queue     Bind to a Solace queue forever  (Ctrl+C to stop)
                  cos-read         Read an object from Tencent COS
                  cos-write        Write an object to Tencent COS

                Options:
                  --config=<path>  Path to config.properties (default: ./config.properties)

                Examples:
                  # Terminal 1 - listen on topic
                  java -jar yan-smoketest-1.0.0-fat.jar --mode=listen-topic

                  # Terminal 2 - publish to topic
                  java -jar yan-smoketest-1.0.0-fat.jar --mode=publish-topic

                  # Terminal 3 - listen on queue
                  java -jar yan-smoketest-1.0.0-fat.jar --mode=listen-queue

                  # Terminal 4 - publish to queue
                  java -jar yan-smoketest-1.0.0-fat.jar --mode=publish-queue

                  # COS
                  java -jar yan-smoketest-1.0.0-fat.jar --mode=cos-write
                  java -jar yan-smoketest-1.0.0-fat.jar --mode=cos-read
                """);
    }
}
