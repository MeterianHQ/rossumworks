package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class ViewerLocationTest {

    @Test
    void aRemoteAddressIsNotThisMachine() throws UnknownHostException {
        assertThat(ViewerLocation.isThisMachine(exchangeFrom("192.168.1.10", new Headers()))).isFalse();
    }

    @Test
    void anIpv4LoopbackAddressIsThisMachine() throws UnknownHostException {
        assertThat(ViewerLocation.isThisMachine(exchangeFrom("127.0.0.1", new Headers()))).isTrue();
    }

    @Test
    void anIpv6LoopbackAddressIsThisMachine() throws UnknownHostException {
        assertThat(ViewerLocation.isThisMachine(exchangeFrom("::1", new Headers()))).isTrue();
    }

    @Test
    void aForwardedForHeaderDoesNotMakeARemoteAddressThisMachine() throws UnknownHostException {
        Headers headers = new Headers();
        headers.add("X-Forwarded-For", "127.0.0.1");

        assertThat(ViewerLocation.isThisMachine(exchangeFrom("192.168.1.10", headers))).isFalse();
    }

    private static HttpExchange exchangeFrom(String address, Headers requestHeaders) throws UnknownHostException {
        return new StubExchange(new InetSocketAddress(InetAddress.getByName(address), 50000), requestHeaders);
    }

    private static final class StubExchange extends HttpExchange {
        private final InetSocketAddress remoteAddress;
        private final Headers requestHeaders;

        StubExchange(InetSocketAddress remoteAddress, Headers requestHeaders) {
            this.remoteAddress = remoteAddress;
            this.requestHeaders = requestHeaders;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            throw new UnsupportedOperationException();
        }

        @Override
        public URI getRequestURI() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getRequestMethod() {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpContext getHttpContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream getRequestBody() {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutputStream getResponseBody() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sendResponseHeaders(int responseCode, long responseLength) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getResponseCode() {
            throw new UnsupportedOperationException();
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getProtocol() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getAttribute(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAttribute(String name, Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setStreams(InputStream inputStream, OutputStream outputStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpPrincipal getPrincipal() {
            throw new UnsupportedOperationException();
        }
    }
}
