package com.example.tvchart;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * JVM unit tests for the protocol layer: run with `./gradlew test` (no emulator).
 *
 * The expected frame strings below are the byte-for-byte output of the reference
 * implementation in this repository (`src/protocol/framing.ts`), so the Java port
 * is pinned to what the TradingView server actually accepts.
 */
public class TvProtocolTest {

    @Test
    public void encodesPacketsWithUtf16Lengths() {
        assertEquals("~m~36~m~{\"m\":\"set_auth_token\",\"p\":[\"token\"]}",
                TvProtocol.encodePacket("set_auth_token", "token"));
        assertEquals("~m~54~m~{\"m\":\"set_auth_token\",\"p\":[\"unauthorized_user_token\"]}",
                TvProtocol.encodePacket("set_auth_token", TvProtocol.ANONYMOUS_AUTH_TOKEN));
        assertEquals("~m~5~m~~h~42", TvProtocol.encodeHeartbeat(42));
        // Two code points, two UTF-16 code units: the length is not a byte count.
        assertEquals("~m~2~m~é€", TvProtocol.encodeFrame("é€"));
        assertEquals("~m~3~m~日本語", TvProtocol.encodeFrame("日本語"));
    }

    @Test
    public void buildsQuoteMessagesTheServerAccepts() {
        assertEquals("={\"session\":\"regular\",\"symbol\":\"BINANCE:BTCUSDT\"}",
                TvProtocol.quoteSymbolKey("BINANCE:BTCUSDT", "regular"));

        assertEquals("~m~44~m~{\"m\":\"quote_create_session\",\"p\":[\"qs_TEST\"]}",
                TvProtocol.encodePacket("quote_create_session", "qs_TEST"));

        String add = TvProtocol.encodePacket("quote_add_symbols",
                "qs_TEST", TvProtocol.quoteSymbolKey("BINANCE:BTCUSDT", "regular"));
        assertEquals("~m~101~m~{\"m\":\"quote_add_symbols\",\"p\":[\"qs_TEST\","
                + "\"={\\\"session\\\":\\\"regular\\\",\\\"symbol\\\":\\\"BINANCE:BTCUSDT\\\"}\"]}", add);

        String fields = TvProtocol.encodePacket("quote_set_fields",
                Arrays.<Object>asList("qs_TEST", "lp", "ch", "chp", "volume"));
        assertEquals("~m~65~m~{\"m\":\"quote_set_fields\",\"p\":[\"qs_TEST\",\"lp\",\"ch\",\"chp\",\"volume\"]}",
                fields);
    }

    @Test
    public void decodesMultipleFramesHeartbeatsAndTheGreeting() {
        String message = TvProtocol.encodeFrame("{\"session_id\":\"abc\",\"protocol\":\"json\"}")
                + TvProtocol.encodeFrame("~h~7")
                + TvProtocol.encodePacket("qsd", "qs_1", quoteValues());

        List<TvProtocol.Frame> frames = TvProtocol.decodeFrames(message);
        assertEquals(3, frames.size());

        assertEquals(TvProtocol.Frame.Type.DATA, frames.get(0).type);
        assertTrue(TvProtocol.isGreeting(frames.get(0)));
        assertEquals("abc", frames.get(0).data.get("session_id"));

        assertEquals(TvProtocol.Frame.Type.HEARTBEAT, frames.get(1).type);
        assertEquals(7, frames.get(1).heartbeatId);

        assertEquals(TvProtocol.Frame.Type.PACKET, frames.get(2).type);
        assertEquals("qsd", frames.get(2).method);
        assertEquals("qs_1", frames.get(2).params.get(0));
    }

    @Test
    public void keepsPayloadsContainingFrameMarkersIntact() {
        String tricky = "{\"m\":\"qsd\",\"p\":[\"qs_1\","
                + "{\"description\":\"weird ~m~12~m~ text\",\"unicode\":\"日本語\"}]}";
        List<TvProtocol.Frame> frames = TvProtocol.decodeFrames(TvProtocol.encodeFrame(tricky));
        assertEquals(1, frames.size());
        assertEquals(TvProtocol.Frame.Type.PACKET, frames.get(0).type);
        assertEquals("weird ~m~12~m~ text",
                TvJson.asString(TvJson.asObject(frames.get(0).params.get(1)).get("description")));
    }

    @Test
    public void fallsBackToSplittingMalformedInput() {
        List<TvProtocol.Frame> frames = TvProtocol.decodeFrames("garbage~m~4~m~~h~1");
        assertEquals(2, frames.size());
        assertEquals(TvProtocol.Frame.Type.INVALID, frames.get(0).type);
        assertEquals(TvProtocol.Frame.Type.HEARTBEAT, frames.get(1).type);
        assertEquals(1, frames.get(1).heartbeatId);
    }

    @Test
    public void reportsInvalidJsonInsteadOfThrowing() {
        List<TvProtocol.Frame> frames = TvProtocol.decodeFrames("~m~5~m~{nope");
        assertEquals(1, frames.size());
        assertEquals(TvProtocol.Frame.Type.INVALID, frames.get(0).type);
    }

    @Test
    public void parsesAQuoteUpdate() {
        TvProtocol.Frame frame = TvProtocol.decodeFrames(
                TvProtocol.encodePacket("qsd", "qs_1", quoteValues())).get(0);
        TvProtocol.Quote quote = TvProtocol.parseQuote(frame.method, frame.params);

        assertNotNull(quote);
        assertEquals("={\"session\":\"regular\",\"symbol\":\"BINANCE:BTCUSDT\"}", quote.key);
        assertEquals(Double.valueOf(118234.5), quote.lastPrice);
        assertEquals(Double.valueOf(1234.5), quote.change);
        assertEquals(Double.valueOf(1.06), quote.changePercent);
        assertNull(quote.error);
        assertEquals("BINANCE:BTCUSDT", TvQuoteClient.symbolOf(quote.key));
    }

    @Test
    public void parsesAQuoteError() {
        TvProtocol.Frame frame = TvProtocol.decodeFrames(TvProtocol.encodePacket("qsd", "qs_1",
                TvJson.parse("{\"n\":\"=x\",\"s\":\"error\",\"errmsg\":\"unknown_symbol\"}"))).get(0);
        TvProtocol.Quote quote = TvProtocol.parseQuote(frame.method, frame.params);

        assertNotNull(quote);
        assertTrue(quote.isError());
        assertEquals("unknown_symbol", quote.error);
    }

    @Test
    public void roundTripsJsonThroughTheWriter() {
        String json = "{\"m\":\"qsd\",\"p\":[\"qs_1\",{\"n\":\"=x\",\"v\":{\"lp\":1,\"t\":1790979659908}}]}";
        assertEquals(json, TvJson.write(TvJson.parse(json)));
    }

    private static Object quoteValues() {
        return TvJson.parse("{\"n\":\"={\\\"session\\\":\\\"regular\\\","
                + "\\\"symbol\\\":\\\"BINANCE:BTCUSDT\\\"}\",\"s\":\"ok\","
                + "\"v\":{\"lp\":118234.5,\"ch\":1234.5,\"chp\":1.06,\"volume\":23456}}");
    }
}
