/*
 *  Copyright 2018 Shane Mc Cormack <shanemcc@gmail.com>.
 *  See LICENSE for licensing details.
 */
package uk.org.dataforce.libs.signalr;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.launchdarkly.eventsource.EventHandler;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.ReadyState;
import com.launchdarkly.eventsource.UnsuccessfulResponseException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Headers;
import org.apache.http.HttpResponse;
import org.apache.http.client.CookieStore;
import org.apache.http.client.fluent.Executor;
import org.apache.http.client.fluent.Form;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.fluent.Response;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.util.EntityUtils;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class that connects to SignalR.
 * Useful: https://blog.3d-logic.com/2015/03/29/signalr-on-the-wire-an-informal-description-of-the-signalr-protocol/
 *
 * @author Shane Mc Cormack <shanemcc@gmail.com>
 */
public class SignalRClient implements EventHandler {
    /** Our last messageId, used for sending. */
    private static final AtomicInteger messageID = new AtomicInteger(0);
    /** SignalR connection id. */
    private SignalRConnectionInfo lastConnectionInfo = null;
    /** Our source of events. */
    private EventSource eventSource;
    /** ObjectMapper for JSON to POJO. */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** Have we had the "initialized" message from signalr yet? */
    private boolean initialized = false;

    /** Lock for onMessage to ensure we do one batch at a time. */
    private final Semaphore messageLock = new Semaphore(1);

    /** Keepalive Timer. */
    private volatile Timer keepaliveTimer;
    /** keepalive timer semaphore. */
    private final Semaphore keepaliveTimerSem = new Semaphore(1);
    /** Last message time, used by keepaliveTimer. */
    private volatile Long lastMessageTime = System.currentTimeMillis();

    /** Host to connect to. */
    private String host = "";
    /** Should we use SSL? */
    private boolean isSSL = true;
    /** Path to signalr endpoint. */
    private String path = "/signalr";
    /** Negotiate protocol version. */
    private String negotiateVersion = null;
    /** Hubs that we use. */
    private List<String> signalrHubs = Arrays.asList(new String[]{});

    /** HTTP Context to send requests in (keeps hold of cookies etc). */
    private final Executor httpContext;
    /** HTTP Cookie Store */
    private final CookieStore cookieStore;

    /** Background tasks. */
    private final ExecutorService backgroundSender = Executors.newFixedThreadPool(1);

    /** Our handler. */
    private final SignalRHandler handler;

    /** Failed open counter. */
    private final AtomicInteger failedOpenCounter = new AtomicInteger(0);

    /** Have we aborted? */
    private final AtomicBoolean hasAborted = new AtomicBoolean(false);

    public SignalRClient(final SignalRHandler handler, final CookieStore cookieStore, final Executor httpContext) {
        this.handler = handler;

        this.cookieStore = cookieStore == null ? new BasicCookieStore() : cookieStore;
        this.httpContext = httpContext == null ? Executor.newInstance() : httpContext;
        this.httpContext.use(this.cookieStore);
    }

    /**
     * What hostname to connect to.
     *
     * @param host host to connect to
     */
    public void setHost(final String host) {
        this.host = host;
    }

    /**
     * Set the path to signalr.
     *
     * @param path Path to signalr
     */
    public void setPath(final String path) {
        this.path = path;
    }

    /**
     * Should we use SSL?
     *
     * @param isSSL Use ssl or not.
     */
    public void setSSL(final boolean isSSL) {
        this.isSSL = isSSL;
    }

    /**
     * Are we using SSL?
     *
     * @return True if we are using SSL.
     */
    public boolean isSSL() {
        return this.isSSL;
    }

    /**
     * Set the signalr negotiation version
     *
     * @param version Version to negotiate
     */
    public void setClientProtocol(final String version) {
        this.negotiateVersion = version;
    }

    /**
     * Set the hubs this instance supports
     *
     * @param hubs Hubs to connect to.
     */
    public void setHubs(final List<String> hubs) {
        this.signalrHubs = hubs;
    }

    public SignalRConnectionInfo getLastConnectionInfo() {
        return lastConnectionInfo;
    }

    /**
     * Get our HTTP Context.
     *
     * @return HTTP Executor.
     */
    protected final Executor getHttpContext() {
        return httpContext;
    }

    /**
     * Get our HTTP Context Cookie Store
     *
     * @return Cookie Store
     */
    protected final CookieStore getCookieStore() {
        return cookieStore;
    }

    /**
     * Get a new connection id from signalr.
     *
     * @return New ConnectionID from signalr.
     * @throws IOException
     * @throws java.net.URISyntaxException
     */
    protected SignalRConnectionInfo getNewConnectionInfo() throws IOException, URISyntaxException {
        final URIBuilder uri = new URIBuilder((isSSL() ? "https" : "http") + "://" + host + "/" + path + "/negotiate");
        uri.addParameter("_", Long.toString(System.currentTimeMillis()));

        if (negotiateVersion != null && !negotiateVersion.isEmpty()) {
            uri.addParameter("clientProtocol", negotiateVersion);
        }

        final Response response = getHttpContext().execute(Request.Get(uri.build()));

        final InputStream contentStream = response.returnContent().asStream();
        @SuppressWarnings("unchecked")
        final Map<String, Object> content = objectMapper.readValue(contentStream, Map.class);

        final String connectionId = (String) content.get("ConnectionId");
        final String connectionToken = (String) content.get("ConnectionToken");
        final String protocolVersion = (String) content.get("ProtocolVersion");

        return new SignalRConnectionInfo(connectionId, connectionToken, protocolVersion);
    }

    public boolean hasAborted() {
        return hasAborted.get();
    }

    public int getFailedOpenCount() {
        return failedOpenCounter.get();
    }

    /**
     * Are we currently connected?
     *
     * @return True if connected.
     */
    public boolean isConnected() {
        return (eventSource != null);
    }

    public void disconnect() {
        if (eventSource == null) {
            return;
        }

        doLog(Level.INFO, "Disconnecting");
        eventSource.close();
        eventSource = null;

        killTimer();
    }

    /**
     * Build a signalr uri of the given type.
     *
     * @param type Type of URI to build. ("connect", "send" etc)
     * @param connectionData Map of connection data to pass in URI if needed.
     * @return Build URI
     * @throws JsonProcessingException
     * @throws URISyntaxException
     */
    private URI getURI(final String type, final List<Map<String, Object>> connectionData) throws JsonProcessingException, URISyntaxException {
        final URIBuilder builder = new URIBuilder();

        builder.setScheme(isSSL() ? "https" : "http").setHost(host).setPath(path + "/" + type);
        builder.setParameter("transport", "serverSentEvents");

        if (!Strings.isNullOrEmpty(lastConnectionInfo.getConnectionToken())) {
            builder.setParameter("connectionToken", lastConnectionInfo.getConnectionToken());
        } else if (!Strings.isNullOrEmpty(lastConnectionInfo.getConnectionID())) {
            builder.setParameter("connectionId", lastConnectionInfo.getConnectionID());
        }

        if (!Strings.isNullOrEmpty(lastConnectionInfo.getClientProtocol())) {
            builder.setParameter("clientProtocol", lastConnectionInfo.getClientProtocol());
        }

        if (type.equalsIgnoreCase("connect")) {
            builder.setParameter("tid", Integer.toString((int) Math.floor(Math.random() * 11)));
        }

        if (connectionData != null) {
            builder.setParameter("connectionData", objectMapper.writeValueAsString(connectionData));
        }

        return builder.build();
    }

    public void waitForReady() {
        // Keep trying until we get the right outcome...
        while (true) {

            // We need to wait until the socket is open before we can actually
            // do anything
            doLog(Level.FINEST, "Waiting for connection...");
            while (eventSource != null && eventSource.getState() == ReadyState.CONNECTING) {
                try {
                    Thread.sleep(100);
                } catch (final InterruptedException ex) {
                    // Do nothing
                }
            }
            doLog(Level.FINEST, "Connected.");

            // If the socket is open, wait until we have the initialized message.
            if (eventSource != null && eventSource.getState() == ReadyState.OPEN) {
                doLog(Level.FINEST, "Waiting for initialized...");
                while (!canSend() && eventSource.getState() == ReadyState.OPEN) {
                    try {
                        Thread.sleep(100);
                    } catch (final InterruptedException ex) {
                        // Do nothing
                    }
                }
                doLog(Level.FINEST, "Ready.");
                break;
            } else {
                doLog(Level.FINEST, "Waiting failed. Expected: " + ReadyState.OPEN + " - Got: " + (eventSource != null ? eventSource.getState() : "NO EVENTSOURCE"));
            }

            if (eventSource == null || hasAborted.get()) {
                break;
            }
        }
    }

    private EventSource getNewEventSource(final URI uri) {
        final EventSource.Builder builder = new EventSource.Builder(this, uri);

        builder.reconnectTimeMs(2000);

        final List<String> cookieBits = new LinkedList<>();
        getCookieStore().getCookies().forEach(cookie -> cookieBits.add(String.format("%s=%s", cookie.getName(), cookie.getValue())));

        final Headers.Builder headers = new Headers.Builder();
        headers.add("Cookie", Joiner.on("; ").join(cookieBits));
        builder.headers(headers.build());

        return builder.build();
    }

    public void connect() throws URISyntaxException, IOException {
        if (lastConnectionInfo == null) {
            lastConnectionInfo = getNewConnectionInfo();
        }

        final List<Map<String, Object>> connectHubs = new LinkedList<>();

        for (final String hub : signalrHubs) {
            final Map<String, Object> map = new HashMap<>();
            map.put("name", hub);
            connectHubs.add(map);
        }

        final URI uri = getURI("connect", connectHubs);

        doLog(Level.FINER, "Connecting to: " + uri.toString());

        initialized = false;

        hasAborted.set(false);
        failedOpenCounter.set(0);

        eventSource = getNewEventSource(uri);
        eventSource.start();
    }

    public void sendStart() throws URISyntaxException, IOException {
        final List<Map<String, Object>> connectHubs = new LinkedList<>();

        for (final String hub : signalrHubs) {
            final Map<String, Object> map = new HashMap<>();
            map.put("name", hub);
            connectHubs.add(map);
        }

        doLog(Level.FINEST, "Send Start");
        final URI uri = getURI("start", connectHubs);
        doLog(Level.FINEST, "    To: " + uri.toString());

        final Response response = getHttpContext().execute(Request.Get(uri.toString()));

        final HttpResponse httpResponse = response.returnResponse();
        int statusCode = httpResponse.getStatusLine().getStatusCode();
        if (statusCode != 200 && statusCode != 302) {
            throw new IOException("Failed to send start message: HTTP " + statusCode);
        }

        final String returnData = EntityUtils.toString(httpResponse.getEntity());
        doLog(Level.FINEST, "    Result: " + returnData);
    }

    /**
     * Reconnect the eventSource.
     */
    public void reconnect() {
        final URI uri = eventSource.getUri();
        eventSource.close();
        eventSource = null;

        // doLog(Level.FINER, "Re-connecting to: " + uri.toString());

        initialized = false;
        eventSource = getNewEventSource(uri);
        eventSource.start();
    }

    public boolean canSend() {
        return lastConnectionInfo != null && eventSource != null && initialized && eventSource.getState() == ReadyState.OPEN;
    }

    public CompletableFuture<String> sendBackground(final String hub, final String method, final List<Object> args, final Map<String, String> state) {
        final CompletableFuture<String> result = new CompletableFuture<>();

        backgroundSender.submit(() -> {
            try {
                result.complete(send(hub, method, args, state));
            } catch (final Exception e) {
                result.completeExceptionally(e);
            }
        });

        return result;
    }

    public String send(final String hub, final String method, final List<Object> args, final Map<String, String> state) throws JsonProcessingException, URISyntaxException, IOException {
        if (!canSend()) {
            throw new IOException("Socket not ready for sending.");
        }

        messageLock.acquireUninterruptibly();
        final Map<String, Object> connectionData = new HashMap<>();

        switch (lastConnectionInfo.getClientProtocol()) {
            case "1.0":
            case "1.1":
                connectionData.put("hub", hub);
                connectionData.put("method", method);
                connectionData.put("args", args);
                if (state != null) {
                    connectionData.put("state", state);
                }
                connectionData.put("id", messageID.getAndIncrement());
                break;
            default:
                connectionData.put("H", hub);
                connectionData.put("M", method);
                connectionData.put("A", args);
                if (state != null) {
                    connectionData.put("S", state);
                }
                connectionData.put("I", messageID.getAndIncrement());
                break;
        }

        final List<Map<String, Object>> connectHubs = new LinkedList<>();

        for (final String connHub : signalrHubs) {
            final Map<String, Object> map = new HashMap<>();
            map.put("name", connHub);
            connectHubs.add(map);
        }

        final URI uri = getURI("send", connectHubs);
        final String data = objectMapper.writeValueAsString(connectionData);

        doLog(Level.FINER, "Sending to: " + uri.toString());
        doLog(Level.FINEST, "      Data: " + data);

        final Response response = getHttpContext().execute(Request.Post(uri.toString()).bodyForm(Form.form().add("data", data).build()));

        final HttpResponse httpResponse = response.returnResponse();
        int statusCode = httpResponse.getStatusLine().getStatusCode();
        if (statusCode != 200 && statusCode != 302) {
            throw new IOException("Failed to send message: HTTP " + statusCode);
        }

        final String returnData = EntityUtils.toString(httpResponse.getEntity());
        doLog(Level.FINEST, "    Result: " + returnData);
        messageLock.release();
        return returnData;
    }

    /**
     * Log a debug line to CLI at the specified level.
     *
     * @param level Level to log at.
     * @param logString String.format() string to log.
     * @param args Format arguments.
     */
    private void doLog(final Level level, final String logString, final Object... args) {
        Logger.getLogger("uk.org.dataforce.libs.singlar").log(level, "[signalR::{0}] {1}", new Object[]{lastConnectionInfo == null ? "NULL" : lastConnectionInfo.getConnectionID(), args.length == 0 ? logString : String.format(logString, args)});
    }

    /**
     * Called when the EventSource is open.
     *
     * @throws Exception
     */
    @Override
    public void onOpen() throws Exception {
        doLog(Level.FINER, "onOpen");
        killTimer();

        keepaliveTimerSem.acquireUninterruptibly();

        try {
            final long keepaliveTimeout = 120 * 1000;

            keepaliveTimer = new Timer("Keepalive Timer - " + lastConnectionInfo.getConnectionID());
            keepaliveTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (SignalRClient.this.keepaliveTimer == keepaliveTimer) {
                        if (System.currentTimeMillis() - lastMessageTime > keepaliveTimeout) {
                            doLog(Level.INFO, "SignalR timed out. Reconnecting.");
                            reconnect();
                        }
                    } else {
                        this.cancel();
                    }
                }
            }, keepaliveTimeout, keepaliveTimeout);
        } finally {
            keepaliveTimerSem.release();
        }
    }

    /**
     * Called when the EventSource is closed.
     *
     * @throws Exception
     */
    @Override
    public void onClosed() throws Exception {
        doLog(Level.FINER, "onClosed");
        killTimer();
        if (failedOpenCounter.incrementAndGet() > 10) {
            doLog(Level.WARNING, "Failed to connect to signalr 10 times, aborting.");
            eventSource.close();
            eventSource = null;
            hasAborted.set(true);
            handler.connectionAborted(this);
        } else {
            // Don't send connectionClosed event unless we are shutdown,
            // as we will reconnect and handle it silently.
            if (eventSource.getState() == ReadyState.SHUTDOWN) {
                handler.connectionClosed(this);
            }
        }
    }

    private void killTimer() {
        keepaliveTimerSem.acquireUninterruptibly();
        try {
            if (keepaliveTimer != null) {
                keepaliveTimer.cancel();
                keepaliveTimer = null;
            }
        } finally {
            keepaliveTimerSem.release();
        }
    }

    /**
     * Called when the EventSource has a message for us.
     *
     * @param string Nothing of use.
     * @param me Message from the EventSource.
     * @throws Exception
     */
    @Override
    public void onMessage(final String string, final MessageEvent me) throws Exception {
        messageLock.acquireUninterruptibly();
        lastMessageTime = System.currentTimeMillis();

        boolean handled = false;
        doLog(Level.FINER, "onMessage:");
        doLog(Level.FINER, "\t LAST EID: %s", me.getLastEventId());
        doLog(Level.FINEST, "\t     DATA: %s", me.getData());
        doLog(Level.FINEST, "\t   STRING: %s", string);

        if (me.getData().equalsIgnoreCase("initialized")) {
            failedOpenCounter.set(0);
            hasAborted.set(false);

            initialized = true;
            handled = true;

            if (!Strings.isNullOrEmpty(lastConnectionInfo.getClientProtocol())) {
                switch (lastConnectionInfo.getClientProtocol()) {
                    case "1.0":
                    case "1.1":
                    case "1.2":
                    case "1.3":
                        break;
                    default:
                        sendStart();
                }
            }
        } else {
            try {
                final JsonNode root = objectMapper.readValue(me.getData(), ObjectNode.class);

                if (root != null) {
                    final JsonNode messages = root.has("Messages") ? root.get("Messages") : root.get("M");

                    if (messages != null) {
                        final ObjectMapper messageMapper = new ObjectMapper();
                        messageMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

                        final List<SignalRMessage> signalrMessages = new LinkedList<>();

                        if (messages instanceof ArrayNode) {
                            for (final JsonNode message : messages) {
                                final SignalRMessage signalrmessage = messageMapper.convertValue(message, SignalRMessage.class);

                                if (handler instanceof SignalRMultiHandler) {
                                    signalrMessages.add(signalrmessage);
                                } else {
                                    try {
                                        handler.handle(this, signalrmessage);
                                    } catch (final Throwable t) {
                                        onError(t);
                                    }
                                }
                            }

                            if (handler instanceof SignalRMultiHandler) {
                                try {
                                    ((SignalRMultiHandler)handler).multihandle(this, signalrMessages);
                                } catch (final Throwable t) {
                                    onError(t);
                                }
                            }

                            handled = true;
                        }
                    } else {
                        handler.keepalive(this);
                    }

                    final JsonNode lastMessageID = root.has("MessageId") ? root.get("MessageId") : root.get("C");
                    if (lastMessageID != null) {
                        final JsonNode groupData = root.path("TransportData").path("Groups");
                        final JsonNode groupToken = root.has("G") ? root.get("G") : null;

                        if (groupToken != null) {
                            updateEventSourceURI(lastMessageID.asText(), null, groupToken.asText());
                        } else if (groupData != null) {
                            final List<String> groups = objectMapper.convertValue(groupData, new TypeReference<List<String>>() {});

                            updateEventSourceURI(lastMessageID.asText(), groups, null);
                        }
                    }
                }
            } catch (final Throwable t) {
                onError(t);
            }
        }

        if (!handled) {
            doLog(Level.FINER, "\t    UDATA: %s", me.getData());
        }

        messageLock.release();
    }

    /**
     * Ensure we reconnect back into the right groups.
     *
     * @param messageID Last message ID
     * @param groups Last known groups.
     */
    private void updateEventSourceURI(final String messageID, final List<String> groups, final String groupsToken) {
        if (eventSource == null || (messageID == null && groups == null && groupsToken == null)) { return; }

        final URIBuilder builder = new URIBuilder(eventSource.getUri());
        builder.setPath(path + "/reconnect");

        if (messageID != null) {
            builder.setParameter("messageID", messageID);
        }

        if (groupsToken != null) {
            builder.setParameter("groupsToken", groupsToken);
        } else if (groups != null) {
            try {
                builder.setParameter("groups", objectMapper.writeValueAsString(groups));
            } catch (final JsonProcessingException ex) {
                // Do Nothing.
            }
        }

        try {
            // doLog(Level.FINEST, "Setting reconnect URI: " + builder.build());
            eventSource.setUri(builder.build());
        } catch (final URISyntaxException ex) {
            // Do nothing, should never happen.
        }
    }

    /**
     * Called if a comment is send in the event stream.
     *
     * @param comment The comment.
     * @throws Exception
     */
    @Override
    public void onComment(final String comment) throws Exception {
        doLog(Level.FINER, "onComment:");
        doLog(Level.FINER, "\t COMMENT: %s", comment);
    }

    /**
     * Called if there is an error with any of the EventSource callbacks.
     *
     * @param error Throwable that caused the error.
     */
    @Override
    public void onError(final Throwable error) {
        doLog(Level.WARNING, "onError:");
        doLog(Level.WARNING, "\t    TYPE: %s", error.getClass().toGenericString());
        doLog(Level.WARNING, "\t MESSAGE: %s", error.getMessage());
        for (final StackTraceElement ste : error.getStackTrace()) {
            doLog(Level.WARNING, "\t   TRACE: %s", ste.toString());
        }

        if (error instanceof UnsuccessfulResponseException) {
            // Our authentication token probably timed out, exit.
            disconnect();

            handler.connectionClosed(this);
        }
    }
}
