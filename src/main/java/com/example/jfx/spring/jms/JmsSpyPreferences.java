package com.example.jfx.spring.jms;

import java.net.URI;

public record JmsSpyPreferences(String brokerHost, int brokerPort, String username, String subscribeDestination,
        DestinationType subscribeDestinationType, boolean appendMode, boolean darkMode, String publishDestination,
        DestinationType publishDestinationType, int jolokiaPort, String jolokiaPath, String addressSearchMbean,
        boolean jolokiaVirtualService)
{

    static final int DEFAULT_BROKER_PORT = 61616;

    static JmsSpyPreferences defaults()
    {
        return new JmsSpyPreferences(defaultBrokerHost(), defaultBrokerPort(), defaultUsername(), "",
                DestinationType.QUEUE, true, false, "", DestinationType.QUEUE, JolokiaClient.DEFAULT_JOLOKIA_PORT,
                JolokiaClient.DEFAULT_JOLOKIA_PATH, JolokiaClient.DEFAULT_ADDRESS_SEARCH_MBEAN, false);
    }

    /**
     * The cluster's backends take their Artemis connection from this same env var
     * (see docker-compose.yml / the Helm charts), so reuse it here as a sensible default -
     * parsing the host/port back out of its "tcp://host:port" form since the UI now edits
     * them as separate fields.
     */
    static String defaultBrokerHost()
    {
        return parseBrokerHost(envOrBlank("SPRING_ARTEMIS_BROKER_URL"));
    }

    static int defaultBrokerPort()
    {
        return parseBrokerPort(envOrBlank("SPRING_ARTEMIS_BROKER_URL"));
    }

    static String defaultUsername()
    {
        return envOrBlank("SPRING_ARTEMIS_USER");
    }

    static String defaultPassword()
    {
        return envOrBlank("SPRING_ARTEMIS_PASSWORD");
    }

    /**
     * Also used by UserPreferencesStore to migrate a pre-host/port-split config file's legacy
     * single "brokerUrl" key.
     */
    static String parseBrokerHost(String brokerUrl)
    {
        try
        {
            var host = URI.create(brokerUrl).getHost();
            return host != null ? host : "";
        }
        catch (IllegalArgumentException ex)
        {
            return "";
        }
    }

    static int parseBrokerPort(String brokerUrl)
    {
        try
        {
            var port = URI.create(brokerUrl).getPort();
            if (port > 0)
            {
                return port;
            }
        }
        catch (IllegalArgumentException ex)
        {
            // fall through to default
        }
        return DEFAULT_BROKER_PORT;
    }

    private static String envOrBlank(String name)
    {
        var value = System.getenv(name);
        return value != null ? value : "";
    }
}
