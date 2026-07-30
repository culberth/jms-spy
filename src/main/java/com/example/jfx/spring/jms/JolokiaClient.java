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
 * Looks up the addresses (queue/topic names) known to an Artemis broker via its Jolokia HTTP
 * management API, so the UI can offer them as autocomplete suggestions for destination fields.
 */
@Component
class JolokiaClient
{

    private static final int DEFAULT_JOLOKIA_PORT = 8161;
    private static final String DEFAULT_JOLOKIA_PATH = "/console/jolokia";

    // Matches every address MBean regardless of broker name or routing type (anycast/multicast),
    // so both queue and topic addresses are returned.
    private static final String ADDRESS_SEARCH_MBEAN = "org.apache.activemq.artemis:broker=*,component=addresses,address=*";
    private static final Pattern ADDRESS_ATTRIBUTE = Pattern.compile("address=\"([^\"]+)\"");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Jolokia is Artemis's HTTP management API, normally exposed on a different port/path than
     * the JMS broker URL - there's no way to derive it precisely, so this assumes the broker's
     * default console port and Jolokia path, reusing only the hostname from the broker URL.
     */
    String deriveJolokiaUrl(String brokerUrl)
    {
        var host = "localhost";
        try
        {
            var parsedHost = URI.create(brokerUrl).getHost();
            if (parsedHost != null)
            {
                host = parsedHost;
            }
        }
        catch (IllegalArgumentException ex)
        {
            // brokerUrl wasn't a parseable URI - fall back to localhost
        }
        return "http://" + host + ":" + DEFAULT_JOLOKIA_PORT + DEFAULT_JOLOKIA_PATH;
    }

    List<String> searchAddresses(String jolokiaUrl, String username, String password)
            throws IOException, InterruptedException
    {
        var searchUrl = jolokiaUrl.replaceAll("/+$", "") + "/search/" + ADDRESS_SEARCH_MBEAN;
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
