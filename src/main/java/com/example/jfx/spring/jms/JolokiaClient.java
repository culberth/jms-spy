package com.example.jfx.spring.jms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Looks up the topic names known to an Artemis broker via its Jolokia HTTP management API, so
 * the UI can offer them as autocomplete suggestions for destination fields.
 */
@Component
class JolokiaClient
{

    // Defaults for the user-editable Jolokia settings (Edit -> Settings) - also used by
    // JmsSpyPreferences.defaults() when no config file has been saved yet.
    static final int DEFAULT_JOLOKIA_PORT = 8161;
    static final String DEFAULT_JOLOKIA_PATH = "/console/jolokia";
    // Matches only multicast (topic) queue MBeans regardless of broker name, since this app only
    // ever deals in topics - an anycast (queue-only) address wouldn't work here anyway. Standard
    // Artemis MBean naming uses uppercase routing-type values; adjust in Edit -> Settings if a
    // particular broker reports it differently.
    static final String DEFAULT_ADDRESS_SEARCH_MBEAN =
            "org.apache.activemq.artemis:broker=*,component=addresses,address=*,subcomponent=queues,"
                    + "routing-type=MULTICAST,queue=*";

    private static final Pattern ADDRESS_ATTRIBUTE = Pattern.compile("address=\"([^\"]+)\"");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Jolokia is Artemis's HTTP management API, normally exposed on a different port/path than
     * the JMS broker's own port - there's no way to derive it precisely, so this combines the
     * given (user-configurable, defaulting to {@link #DEFAULT_JOLOKIA_PORT}/{@link #DEFAULT_JOLOKIA_PATH})
     * port and path with just the broker's hostname. When Artemis sits behind a virtual service
     * (e.g. a Kubernetes Service/ingress fronting it under a single DNS name), there's no separate
     * management port to reach directly - the port is omitted entirely and the host's default HTTP
     * port is used instead.
     */
    String deriveJolokiaUrl(String brokerHost, int port, String path, boolean virtualService)
    {
        var host = StringUtils.hasText(brokerHost) ? brokerHost : "localhost";
        return virtualService ? "http://" + host + path : "http://" + host + ":" + port + path;
    }

    List<String> searchAddresses(String jolokiaUrl, String username, String password, String addressSearchMbean)
            throws IOException, InterruptedException
    {
        var searchUrl = jolokiaUrl.replaceAll("/+$", "") + "/search/" + addressSearchMbean;
        var requestBuilder = HttpRequest.newBuilder(URI.create(searchUrl)).GET();
        if (StringUtils.hasText(username))
        {
            var credentials = Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            requestBuilder.header("Authorization", "Basic " + credentials);
        }
        var response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
        {
            throw new IOException("Jolokia search returned HTTP " + response.statusCode());
        }

        var addresses = new TreeSet<String>();
        for (JsonNode mbeanName : objectMapper.readTree(response.body()).path("value"))
        {
            var matcher = ADDRESS_ATTRIBUTE.matcher(mbeanName.asText());
            if (matcher.find())
            {
                addresses.add(matcher.group(1));
            }
        }
        return new ArrayList<>(addresses);
    }
}
