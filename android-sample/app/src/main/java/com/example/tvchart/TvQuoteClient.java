package com.example.tvchart;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Live prices over TradingView's websocket, using OkHttp.
 *
 * <p>Sequence (identical to the reference client in this repository):
 * open socket -> send {@code set_auth_token} -> {@code quote_create_session} ->
 * {@code quote_set_fields} -> {@code quote_add_symbols} -> answer every
 * {@code ~h~<id>} heartbeat -> read {@code qsd} packets.
 *
 * <p>All callbacks arrive on OkHttp's background threads: marshal to the UI
 * thread before touching views.
 */
public final class TvQuoteClient {

    /** Receives connection and quote events on a background thread. */
    public interface Listener {
        void onConnected();

        void onQuote(String symbol, TvProtocol.Quote quote);

        void onStatus(String status);

        void onFailure(Throwable error);
    }

    private static final long RECONNECT_DELAY_SECONDS = 5;

    private final OkHttpClient client;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<String> symbols = new ArrayList<>();
    private final Listener listener;
    private final String sessionId = TvProtocol.createSessionId("qs");

    private WebSocket socket;
    private volatile boolean stopped;

    public TvQuoteClient(List<String> symbols, Listener listener) {
        this.symbols.addAll(symbols);
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                // TradingView drives its own keep-alive with ~h~ frames, so no ping needed.
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    /** Opens the socket. Safe to call again after {@link #stop()}. */
    public void start() {
        stopped = false;
        Request request = new Request.Builder()
                .url(TvProtocol.WEBSOCKET_URL)
                .header("Origin", TvProtocol.ORIGIN)
                .header("User-Agent", TvProtocol.USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .build();
        socket = client.newWebSocket(request, new SocketListener());
    }

    /** Closes the socket and releases the client. Idempotent. */
    public void stop() {
        stopped = true;
        WebSocket current = socket;
        if (current != null) {
            current.close(1000, "closing");
        }
        scheduler.shutdownNow();
        client.dispatcher().executorService().shutdown();
    }

    private void send(String frame) {
        WebSocket current = socket;
        if (current != null && !stopped) {
            current.send(frame);
        }
    }

    private void subscribeAll() {
        send(TvProtocol.encodePacket("quote_create_session", sessionId));
        List<Object> fields = new ArrayList<>();
        fields.add(sessionId);
        fields.addAll(TvProtocol.PRICE_FIELDS);
        send(TvProtocol.encodePacket("quote_set_fields", fields));
        for (String symbol : symbols) {
            send(TvProtocol.encodePacket("quote_add_symbols",
                    sessionId, TvProtocol.quoteSymbolKey(symbol, "regular")));
        }
    }

    private final class SocketListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            send(TvProtocol.encodePacket("set_auth_token", TvProtocol.ANONYMOUS_AUTH_TOKEN));
            subscribeAll();
            listener.onConnected();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            for (TvProtocol.Frame frame : TvProtocol.decodeFrames(text)) {
                switch (frame.type) {
                    case HEARTBEAT:
                        send(TvProtocol.encodeHeartbeat(frame.heartbeatId));
                        break;
                    case DATA:
                        if (TvProtocol.isGreeting(frame)) {
                            Object release = frame.data.get("release");
                            listener.onStatus("connected (" + release + ")");
                        }
                        break;
                    case PACKET:
                        handlePacket(frame);
                        break;
                    case INVALID:
                    default:
                        break;
                }
            }
        }

        private void handlePacket(TvProtocol.Frame frame) {
            if ("protocol_error".equals(frame.method)) {
                listener.onStatus("protocol error: " + frame.params);
                return;
            }
            // Every session packet carries its session id as the first parameter.
            Object owner = frame.params.isEmpty() ? null : frame.params.get(0);
            if (!sessionId.equals(owner)) {
                return;
            }
            if ("quote_completed".equals(frame.method)) {
                listener.onStatus("live");
                return;
            }
            TvProtocol.Quote quote = TvProtocol.parseQuote(frame.method, frame.params);
            if (quote != null) {
                listener.onQuote(symbolOf(quote.key), quote);
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable error, Response response) {
            if (stopped) {
                return;
            }
            listener.onFailure(error);
            scheduleReconnect();
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            if (stopped) {
                return;
            }
            listener.onStatus("closed: " + code + " " + reason);
            scheduleReconnect();
        }

        private void scheduleReconnect() {
            if (stopped || scheduler.isShutdown()) {
                return;
            }
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    if (!stopped) {
                        start();
                    }
                }
            }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Turns a quote key back into the plain symbol it was built from. */
    static String symbolOf(String key) {
        if (key == null || !key.startsWith("=")) {
            return key;
        }
        try {
            Object parsed = TvJson.parse(key.substring(1));
            java.util.Map<String, Object> object = TvJson.asObject(parsed);
            if (object != null) {
                String symbol = TvJson.asString(object.get("symbol"));
                if (symbol != null) {
                    return symbol;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through and return the raw key.
        }
        return key;
    }
}
