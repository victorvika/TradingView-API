package com.example.tvchart;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TradingView websocket protocol: framing, message building and quote parsing.
 *
 * <p>Mirrors the reference implementation in this repository
 * ({@code src/protocol/framing.ts}, {@code src/client/client.ts},
 * {@code src/quote/quote-session.ts}):
 * <ul>
 *   <li>every message holds one or more {@code ~m~<length>~m~<payload>} frames,
 *       where the length counts <b>UTF-16 code units</b> - exactly what
 *       {@link String#length()} returns, so the port is exact for non-ASCII payloads;</li>
 *   <li>a heartbeat frame is {@code ~h~<id>} and must be echoed back verbatim;</li>
 *   <li>the server sends a greeting object (with {@code session_id}) right after
 *       the socket opens, then {@code {m, p}} packets;</li>
 *   <li>anonymous access authenticates with {@code set_auth_token} and the literal
 *       token {@code unauthorized_user_token}.</li>
 * </ul>
 * This class has no Android or OkHttp dependency so it can be unit tested on a JVM.
 */
public final class TvProtocol {

    /** Chart/quote websocket. {@code data} is the free server; widgets use {@code widgetdata}. */
    public static final String WEBSOCKET_URL =
            "wss://data.tradingview.com/socket.io/websocket?from=chart&type=chart";

    /** The server checks this header; the reference client sends the same value. */
    public static final String ORIGIN = "https://www.tradingview.com";

    /** Same User-Agent the reference client uses. */
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** Anonymous auth token (delayed / limited data, no account needed). */
    public static final String ANONYMOUS_AUTH_TOKEN = "unauthorized_user_token";

    /** Enough fields for a price badge. The full list lives in {@code src/quote/fields.ts}. */
    public static final List<String> PRICE_FIELDS = Collections.unmodifiableList(Arrays.asList(
            "lp", "lp_time", "ch", "chp", "volume",
            "open_price", "high_price", "low_price", "prev_close_price",
            "currency_code", "exchange", "short_name", "description"));

    private static final String HEADER = "~m~";
    private static final String HEARTBEAT = "~h~";
    private static final Pattern SPLIT = Pattern.compile("~m~\\d+~m~");
    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TvProtocol() {
    }

    /* ------------------------------------------------------------------ types */

    /** One decoded frame of a websocket message. */
    public static final class Frame {
        public enum Type { HEARTBEAT, PACKET, DATA, INVALID }

        public final Type type;
        public final int heartbeatId;
        /** Packet method (`m`), null unless {@link Type#PACKET}. */
        public final String method;
        /** Packet parameters (`p`), never null for {@link Type#PACKET}. */
        public final List<Object> params;
        /** Parsed JSON for {@link Type#DATA} (e.g. the server greeting). */
        public final Map<String, Object> data;
        /** Raw payload when it could not be parsed. */
        public final String raw;

        private Frame(Type type, int heartbeatId, String method, List<Object> params,
                      Map<String, Object> data, String raw) {
            this.type = type;
            this.heartbeatId = heartbeatId;
            this.method = method;
            this.params = params;
            this.data = data;
            this.raw = raw;
        }

        static Frame heartbeat(int id) {
            return new Frame(Type.HEARTBEAT, id, null, null, null, null);
        }

        static Frame packet(String method, List<Object> params) {
            return new Frame(Type.PACKET, 0, method, params, null, null);
        }

        static Frame data(Map<String, Object> data) {
            return new Frame(Type.DATA, 0, null, null, data, null);
        }

        static Frame invalid(String raw) {
            return new Frame(Type.INVALID, 0, null, null, null, raw);
        }
    }

    /** One quote update from a {@code qsd} packet. */
    public static final class Quote {
        /** Symbol key, e.g. {@code ={"session":"regular","symbol":"BINANCE:BTCUSDT"}}. */
        public final String key;
        public final Double lastPrice;
        public final Double change;
        public final Double changePercent;
        public final Double volume;
        /** Server error message when the symbol could not be resolved. */
        public final String error;

        Quote(String key, Double lastPrice, Double change, Double changePercent,
              Double volume, String error) {
            this.key = key;
            this.lastPrice = lastPrice;
            this.change = change;
            this.changePercent = changePercent;
            this.volume = volume;
            this.error = error;
        }

        public boolean isError() {
            return error != null;
        }
    }

    /* --------------------------------------------------------------- encoding */

    /** Wraps one payload into a frame. */
    public static String encodeFrame(String payload) {
        // String.length() counts UTF-16 code units, which is what the server expects.
        return HEADER + payload.length() + HEADER + payload;
    }

    /** Encodes {@code {"m": method, "p": params}} as one frame. */
    public static String encodePacket(String method, Object... params) {
        return encodePacket(method, Arrays.asList(params));
    }

    /** Encodes {@code {"m": method, "p": params}} as one frame. */
    public static String encodePacket(String method, List<Object> params) {
        Map<String, Object> packet = new LinkedHashMap<>();
        packet.put("m", method);
        packet.put("p", params);
        return encodeFrame(TvJson.write(packet));
    }

    /** Encodes the reply to a server heartbeat. */
    public static String encodeHeartbeat(int id) {
        return encodeFrame(HEARTBEAT + id);
    }

    /* --------------------------------------------------------------- decoding */

    /** Decodes every frame of a websocket message. Never throws on bad payloads. */
    public static List<Frame> decodeFrames(String input) {
        List<String> payloads = new ArrayList<>();
        int position = 0;

        while (position < input.length()) {
            if (!input.startsWith(HEADER, position)) {
                payloads.addAll(splitFrames(input.substring(position)));
                break;
            }
            int lengthStart = position + HEADER.length();
            int lengthEnd = input.indexOf(HEADER, lengthStart);
            if (lengthEnd < 0) {
                payloads.addAll(splitFrames(input.substring(position)));
                break;
            }
            int length;
            try {
                length = Integer.parseInt(input.substring(lengthStart, lengthEnd).trim());
            } catch (NumberFormatException error) {
                payloads.addAll(splitFrames(input.substring(position)));
                break;
            }
            if (length < 0) {
                payloads.addAll(splitFrames(input.substring(position)));
                break;
            }
            int start = lengthEnd + HEADER.length();
            int end = Math.min(start + length, input.length());
            payloads.add(input.substring(start, end));
            position = end;
        }

        List<Frame> frames = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            frames.add(decodePayload(payload));
        }
        return frames;
    }

    /** Lenient fallback for malformed input: split on frame headers. */
    private static List<String> splitFrames(String input) {
        List<String> parts = new ArrayList<>();
        Matcher matcher = SPLIT.matcher(input);
        int last = 0;
        while (matcher.find()) {
            String part = input.substring(last, matcher.start());
            if (!part.isEmpty()) {
                parts.add(part);
            }
            last = matcher.end();
        }
        String tail = input.substring(last);
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        return parts;
    }

    private static Frame decodePayload(String raw) {
        if (raw.startsWith(HEARTBEAT)) {
            try {
                return Frame.heartbeat(Integer.parseInt(raw.substring(HEARTBEAT.length()).trim()));
            } catch (NumberFormatException error) {
                return Frame.invalid(raw);
            }
        }
        Object parsed;
        try {
            parsed = TvJson.parse(raw);
        } catch (IllegalArgumentException error) {
            return Frame.invalid(raw);
        }
        Map<String, Object> object = TvJson.asObject(parsed);
        if (object == null) {
            return Frame.invalid(raw);
        }
        String method = TvJson.asString(object.get("m"));
        if (method != null) {
            List<Object> params = TvJson.asArray(object.get("p"));
            return Frame.packet(method, params == null ? new ArrayList<>() : params);
        }
        return Frame.data(object);
    }

    /* -------------------------------------------------------- message helpers */

    /** Random session id such as {@code qs_Ab12Cd34Ef56}. */
    public static String createSessionId(String prefix) {
        StringBuilder id = new StringBuilder(prefix).append('_');
        for (int i = 0; i < 12; i++) {
            id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return id.toString();
    }

    /**
     * Symbol key used by the quote session. The field order matters: the server
     * compares the string, and the reference builds it as
     * {@code "=" + JSON.stringify({ session, symbol })}.
     */
    public static String quoteSymbolKey(String symbol, String session) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("session", session);
        key.put("symbol", symbol);
        return "=" + TvJson.write(key);
    }

    /** Interprets a {@code qsd} or {@code symbol_error} packet, or returns null. */
    public static Quote parseQuote(String method, List<Object> params) {
        if (params == null || params.size() < 2) {
            return null;
        }
        Map<String, Object> payload = TvJson.asObject(params.get(1));
        if (payload == null) {
            return null;
        }
        if ("qsd".equals(method)) {
            String key = TvJson.asString(payload.get("n"));
            String status = TvJson.asString(payload.get("s"));
            if (key == null || !"ok".equals(status)) {
                if (key != null && "error".equals(status)) {
                    String message = TvJson.asString(payload.get("errmsg"));
                    return new Quote(key, null, null, null, null, message == null ? "unknown" : message);
                }
                return null;
            }
            Map<String, Object> values = TvJson.asObject(payload.get("v"));
            if (values == null) {
                return null;
            }
            return new Quote(key,
                    TvJson.asDouble(values.get("lp")),
                    TvJson.asDouble(values.get("ch")),
                    TvJson.asDouble(values.get("chp")),
                    TvJson.asDouble(values.get("volume")),
                    null);
        }
        if ("symbol_error".equals(method)) {
            String message = params.size() > 2 ? TvJson.asString(params.get(2)) : null;
            return new Quote(null, null, null, null, null, message == null ? "symbol error" : message);
        }
        return null;
    }

    /** True when the frame is the server greeting (it carries a {@code session_id}). */
    public static boolean isGreeting(Frame frame) {
        return frame.type == Frame.Type.DATA && frame.data != null && frame.data.containsKey("session_id");
    }
}
