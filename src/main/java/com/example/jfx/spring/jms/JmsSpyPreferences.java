package com.example.jfx.spring.jms;

public record JmsSpyPreferences(String brokerUrl, String username, String subscribeDestination,
        DestinationType subscribeDestinationType, boolean appendMode, boolean darkMode, String publishDestination,
        DestinationType publishDestinationType, int jolokiaPort, String jolokiaPath, String addressSearchMbean,
        boolean jolokiaVirtualService)
{

    static JmsSpyPreferences defaults()
    {
        return new JmsSpyPreferences(defaultBrokerUrl(), defaultUsername(), "", DestinationType.QUEUE, true, false,
                "", DestinationType.QUEUE, JolokiaClient.DEFAULT_JOLOKIA_PORT, JolokiaClient.DEFAULT_JOLOKIA_PATH,
                JolokiaClient.DEFAULT_ADDRESS_SEARCH_MBEAN, false);
    }

    /**
     * The cluster's backends take their Artemis connection from these same env vars
     * (see docker-compose.yml / the Helm charts), so reuse them here as sensible defaults.
     */
    static String defaultBrokerUrl()
    {
        return envOrBlank("SPRING_ARTEMIS_BROKER_URL");
    }

    static String defaultUsername()
    {
        return envOrBlank("SPRING_ARTEMIS_USER");
    }

    static String defaultPassword()
    {
        return envOrBlank("SPRING_ARTEMIS_PASSWORD");
    }

    private static String envOrBlank(String name)
    {
        var value = System.getenv(name);
        return value != null ? value : "";
    }
}
