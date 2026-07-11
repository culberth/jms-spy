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
                properties.getProperty("destination", ""),
                parseDestinationType(properties.getProperty("destinationType")),
                Boolean.parseBoolean(properties.getProperty("appendMode", "true")),
                Boolean.parseBoolean(properties.getProperty("darkMode", "false")));
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
        properties.setProperty("destination", preferences.destination());
        properties.setProperty("destinationType", preferences.destinationType().name());
        properties.setProperty("appendMode", Boolean.toString(preferences.appendMode()));
        properties.setProperty("darkMode", Boolean.toString(preferences.darkMode()));

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
