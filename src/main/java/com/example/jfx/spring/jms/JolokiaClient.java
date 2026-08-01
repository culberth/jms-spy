package com.example.jfx.spring.jms;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    // The MBean pattern matching Artemis's per-address MBeans. Note what this deliberately does
    // NOT do: it doesn't filter on routing type, and it doesn't descend into the per-address
    // "subcomponent=queues" MBeans. Artemis only registers a queue MBean under a multicast address
    // once something actually subscribes to it, so filtering on queue MBeans returns nothing for a
    // topic that exists and is actively being published to but has no live subscriber - which is
    // exactly the case this dropdown needs to cover, since the whole point is to pick a topic in
    // order to subscribe to it. Address MBeans exist as soon as the address does, so they're the
    // right level to enumerate; the multicast filtering happens client-side off each address's
    // RoutingTypes attribute instead (see #listTopicAddresses).
    static final String DEFAULT_ADDRESS_SEARCH_MBEAN =
            "org.apache.activemq.artemis:broker=*,component=addresses,address=*";

    // The routing type Artemis reports for a topic. Unlike the routing-type key inside an ObjectName
    // (which is lowercase and quoted), the RoutingTypes *attribute* value is uppercase.
    private static final String MULTICAST_ROUTING_TYPE = "MULTICAST";
    private static final Pattern ADDRESS_ATTRIBUTE = Pattern.compile("address=\"([^\"]+)\"");
    // Jolokia reports "no MBean matched the pattern" as a 404 in its JSON body while still
    // answering with HTTP 200, so this is a normal "broker has no addresses yet", not an error.
    private static final int JOLOKIA_NOT_FOUND = 404;
    private static final int JOLOKIA_OK = 200;

    private final HttpClient httpClient = HttpClient.newHttpClient();

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

    /**
     * Returns the names of the broker's multicast (topic) addresses, which are the only ones this
     * app can subscribe to. Issues a single Jolokia "read" against the address MBean pattern,
     * pulling each address's RoutingTypes/Internal attributes in one round trip, then keeps the
     * multicast, non-internal ones.
     * <p>
     * This is a POST rather than a GET against Jolokia's /read/ URL form because an Artemis
     * ObjectName embeds double quotes around its address value; those aren't legal URI path
     * characters, so a GET has to percent-encode them and the broker's HTTP layer still rejects
     * some forms outright ("400 Illegal Path Character"). A JSON request body sidesteps the
     * encoding question entirely.
     */
    List<String> listTopicAddresses(String jolokiaUrl, String username, String password, String addressMbeanPattern)
            throws IOException, InterruptedException
    {
        var readRequest = new JsonObject();
        readRequest.addProperty("type", "read");
        readRequest.addProperty("mbean", addressMbeanPattern);
        var attributes = new JsonArray();
        attributes.add("RoutingTypes");
        attributes.add("Internal");
        readRequest.add("attribute", attributes);

        var requestBuilder = HttpRequest.newBuilder(URI.create(jolokiaUrl.replaceAll("/+$", "")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(readRequest.toString(), StandardCharsets.UTF_8));
        if (StringUtils.hasText(username))
        {
            var credentials = Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            requestBuilder.header("Authorization", "Basic " + credentials);
        }

        var response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != JOLOKIA_OK)
        {
            // A wrong username/password shows up here as a 403 from the broker's own HTTP layer,
            // before Jolokia ever answers.
            throw new IOException("Jolokia read returned HTTP " + response.statusCode());
        }

        var root = JsonParser.parseString(response.body()).getAsJsonObject();
        var jolokiaStatus = root.has("status") ? root.get("status").getAsInt() : JOLOKIA_OK;
        if (jolokiaStatus == JOLOKIA_NOT_FOUND)
        {
            return List.of();
        }
        if (jolokiaStatus != JOLOKIA_OK)
        {
            throw new IOException("Jolokia read failed with status " + jolokiaStatus
                    + (root.has("error") ? ": " + root.get("error").getAsString() : ""));
        }

        var value = root.get("value");
        if (value == null || !value.isJsonObject())
        {
            return List.of();
        }

        // A pattern read answers with a map keyed by each matching MBean's canonical name, even
        // when only one matched - so the address name is recovered from the key, the same way the
        // old search-based lookup recovered it from each returned name.
        var addresses = new TreeSet<String>();
        for (var entry : value.getAsJsonObject().entrySet())
        {
            if (!entry.getValue().isJsonObject() || !isSubscribableTopic(entry.getValue().getAsJsonObject()))
            {
                continue;
            }
            var matcher = ADDRESS_ATTRIBUTE.matcher(entry.getKey());
            if (matcher.find())
            {
                addresses.add(matcher.group(1));
            }
        }
        return new ArrayList<>(addresses);
    }

    /**
     * An address is worth offering if it's multicast (this app has no queue support, so an anycast
     * address wouldn't work here) and isn't one of the broker's own internal bookkeeping addresses.
     */
    private boolean isSubscribableTopic(JsonObject addressAttributes)
    {
        var internal = addressAttributes.get("Internal");
        if (internal != null && internal.isJsonPrimitive() && internal.getAsBoolean())
        {
            return false;
        }
        var routingTypes = addressAttributes.get("RoutingTypes");
        if (routingTypes == null || !routingTypes.isJsonArray())
        {
            return false;
        }
        for (var routingType : routingTypes.getAsJsonArray())
        {
            if (MULTICAST_ROUTING_TYPE.equalsIgnoreCase(routingType.getAsString()))
            {
                return true;
            }
        }
        return false;
    }
}
