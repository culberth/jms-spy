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
                properties.getProperty("brokerUrl", JmsSpyPreferences.defaultBrokerUrl()),
                properties.getProperty("username", JmsSpyPreferences.defaultUsername()),
                properties.getProperty("subscribeDestination", ""),
                parseDestinationType(properties.getProperty("subscribeDestinationType")),
                Boolean.parseBoolean(properties.getProperty("appendMode", "true")),
                Boolean.parseBoolean(properties.getProperty("darkMode", "false")),
                properties.getProperty("publishDestination", ""),
                parseDestinationType(properties.getProperty("publishDestinationType")),
                parseJolokiaPort(properties.getProperty("jolokiaPort")),
                properties.getProperty("jolokiaPath", JolokiaClient.DEFAULT_JOLOKIA_PATH),
                properties.getProperty("addressSearchMbean", JolokiaClient.DEFAULT_ADDRESS_SEARCH_MBEAN),
                Boolean.parseBoolean(properties.getProperty("jolokiaVirtualService", "false")));
    }

    private int parseJolokiaPort(String value)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException | NullPointerException ex)
        {
            return JolokiaClient.DEFAULT_JOLOKIA_PORT;
        }
    }

    private DestinationType parseDestinationType(String value)
    {
        try
        {
            return DestinationType.valueOf(value);
        }
        catch (IllegalArgumentException | NullPointerException ex)
        {
            return DestinationType.QUEUE;
        }
    }

    void save(JmsSpyPreferences preferences)
    {
        var properties = new Properties();
        properties.setProperty("brokerUrl", preferences.brokerUrl());
        properties.setProperty("username", preferences.username());
        properties.setProperty("subscribeDestination", preferences.subscribeDestination());
        properties.setProperty("subscribeDestinationType", preferences.subscribeDestinationType().name());
        properties.setProperty("appendMode", Boolean.toString(preferences.appendMode()));
        properties.setProperty("darkMode", Boolean.toString(preferences.darkMode()));
        properties.setProperty("publishDestination", preferences.publishDestination());
        properties.setProperty("publishDestinationType", preferences.publishDestinationType().name());
        properties.setProperty("jolokiaPort", Integer.toString(preferences.jolokiaPort()));
        properties.setProperty("jolokiaPath", preferences.jolokiaPath());
        properties.setProperty("addressSearchMbean", preferences.addressSearchMbean());
        properties.setProperty("jolokiaVirtualService", Boolean.toString(preferences.jolokiaVirtualService()));

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
