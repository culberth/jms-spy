package com.example.jfx.spring.jms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persists the user's last-used broker/topic/display choices between runs.
 * The password is never written here - it must be re-entered each session.
 */
@Slf4j
@Component
class UserPreferencesStore
{

    private static final Path CONFIG_FILE = Path.of(System.getProperty("user.home"), ".jms-spy", "config.properties");

    boolean hasSavedConfig()
    {
        return Files.exists(CONFIG_FILE);
    }

    JmsSpyPreferences load()
    {
        if (!Files.exists(CONFIG_FILE))
        {
            return JmsSpyPreferences.defaults();
        }

        var properties = new Properties();
        try (InputStream in = Files.newInputStream(CONFIG_FILE))
        {
            properties.load(in);
        }
        catch (IOException ex)
        {
            log.warn("Failed to load preferences from {}, falling back to defaults", CONFIG_FILE, ex);
            return JmsSpyPreferences.defaults();
        }

        return new JmsSpyPreferences(
                resolveBrokerHost(properties),
                resolveBrokerPort(properties),
                properties.getProperty("username", JmsSpyPreferences.defaultUsername()),
                properties.getProperty("subscribeDestination", ""),
                Boolean.parseBoolean(properties.getProperty("appendMode", "true")),
                Boolean.parseBoolean(properties.getProperty("darkMode", "false")),
                properties.getProperty("publishDestination", ""),
                parseIntOrDefault(properties.getProperty("jolokiaPort"), JolokiaClient.DEFAULT_JOLOKIA_PORT),
                properties.getProperty("jolokiaPath", JolokiaClient.DEFAULT_JOLOKIA_PATH),
                properties.getProperty("addressSearchMbean", JolokiaClient.DEFAULT_ADDRESS_SEARCH_MBEAN),
                Boolean.parseBoolean(properties.getProperty("virtualService", "false")),
                Boolean.parseBoolean(properties.getProperty("formatJson", "false")),
                Boolean.parseBoolean(properties.getProperty("anonymousLogin", "false")));
    }

    /**
     * Config files written before Broker URL was split into host/port fields only have a single
     * "brokerUrl" key (e.g. "tcp://host:port") - fall back to parsing that if the newer
     * "brokerHost"/"brokerPort" keys aren't present yet, so upgrading doesn't lose a saved broker.
     */
    private String resolveBrokerHost(Properties properties)
    {
        if (properties.containsKey("brokerHost"))
        {
            return properties.getProperty("brokerHost");
        }
        return properties.containsKey("brokerUrl")
                ? JmsSpyPreferences.parseBrokerHost(properties.getProperty("brokerUrl"))
                : JmsSpyPreferences.defaultBrokerHost();
    }

    private int resolveBrokerPort(Properties properties)
    {
        if (properties.containsKey("brokerPort"))
        {
            return parseIntOrDefault(properties.getProperty("brokerPort"), JmsSpyPreferences.DEFAULT_BROKER_PORT);
        }
        return properties.containsKey("brokerUrl")
                ? JmsSpyPreferences.parseBrokerPort(properties.getProperty("brokerUrl"))
                : JmsSpyPreferences.defaultBrokerPort();
    }

    private int parseIntOrDefault(String value, int defaultValue)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException | NullPointerException ex)
        {
            return defaultValue;
        }
    }

    void save(JmsSpyPreferences preferences)
    {
        var properties = new Properties();
        properties.setProperty("brokerHost", preferences.brokerHost());
        properties.setProperty("brokerPort", Integer.toString(preferences.brokerPort()));
        properties.setProperty("username", preferences.username());
        properties.setProperty("subscribeDestination", preferences.subscribeDestination());
        properties.setProperty("appendMode", Boolean.toString(preferences.appendMode()));
        properties.setProperty("darkMode", Boolean.toString(preferences.darkMode()));
        properties.setProperty("publishDestination", preferences.publishDestination());
        properties.setProperty("jolokiaPort", Integer.toString(preferences.jolokiaPort()));
        properties.setProperty("jolokiaPath", preferences.jolokiaPath());
        properties.setProperty("addressSearchMbean", preferences.addressSearchMbean());
        properties.setProperty("virtualService", Boolean.toString(preferences.virtualService()));
        properties.setProperty("formatJson", Boolean.toString(preferences.formatJson()));
        properties.setProperty("anonymousLogin", Boolean.toString(preferences.anonymousLogin()));

        try
        {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (OutputStream out = Files.newOutputStream(CONFIG_FILE))
            {
                properties.store(out, "jms-spy user preferences (password is intentionally not persisted)");
            }
        }
        catch (IOException ex)
        {
            log.warn("Failed to save preferences to {}", CONFIG_FILE, ex);
        }
    }
}
