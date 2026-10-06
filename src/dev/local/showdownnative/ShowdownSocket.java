package dev.local.showdownnative;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Minimal RFC 6455 client for Showdown's documented raw WebSocket endpoint. */
final class ShowdownSocket {
    interface Listener {
        void onOpen();
        void onMessage(String message);
        void onClosed(String reason);
    }

    private static final String HOST = "sim3.psim.us";
    private static final String PATH = "/showdown/websocket";
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private volatile SSLSocket socket;
    private volatile boolean stopped;
    private OutputStream output;

    ShowdownSocket(Listener listener) { this.listener = listener; }

    void connect() {
        new Thread(this::run, "Showdown WebSocket").start();
    }

    private void run() {
        String reason = "连接已断开";
        try {
            SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket(HOST, 443);
            socket = s;
            SSLParameters parameters = s.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(parameters);
            s.setSoTimeout(30000);
            s.startHandshake();
            InputStream in = s.getInputStream();
            output = s.getOutputStream();
            byte[] nonce = new byte[16];
            random.nextBytes(nonce);
            String key = Base64.getEncoder().encodeToString(nonce);
            String request = "GET " + PATH + " HTTP/1.1\r\nHost: " + HOST + "\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n"
                    + "Origin: https://play.pokemonshowdown.com\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            String headers = readHeaders(in);
            if (!headers.startsWith("HTTP/1.1 101 ") && !headers.startsWith("HTTP/1.1 101\r"))
                throw new IOException("WebSocket 握手失败: " + headers.split("\r\n", 2)[0]);
            String accept = "";
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-accept:"))
                    accept = line.substring(line.indexOf(':') + 1).trim();
            }
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
            if (!Base64.getEncoder().encodeToString(digest).equals(accept))
                throw new IOException("WebSocket 服务端验证失败");
            s.setSoTimeout(45000);
            listener.onOpen();
            ByteArrayOutputStream fragments = new ByteArrayOutputStream();
            while (!stopped) {
                Frame frame;
                try { frame = readFrame(in); }
                catch (SocketTimeoutException timeout) { sendFrame(9, new byte[0]); continue; }
                if (frame.opcode == 8) break;
                if (frame.opcode == 9) { sendFrame(10, frame.data); continue; }
                if (frame.opcode == 10) continue;
                if (frame.opcode != 0 && frame.opcode != 1) throw new IOException("不支持的 WebSocket 帧");
                fragments.write(frame.data);
                if (fragments.size() > 2_000_000) throw new IOException("消息过大");
                if (frame.fin) {
                    listener.onMessage(new String(fragments.toByteArray(), StandardCharsets.UTF_8));
                    fragments.reset();
                }
            }
        } catch (Exception e) {
            reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            close();
            listener.onClosed(reason);
        }
    }

    synchronized void send(String message) throws IOException {
        sendFrame(1, message.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void sendFrame(int opcode, byte[] data) throws IOException {
        if (output == null || stopped) throw new IOException("尚未连接");
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        output.write(0x80 | opcode);
        if (data.length < 126) output.write(0x80 | data.length);
        else if (data.length < 65536) {
            output.write(0x80 | 126);
            output.write((data.length >>> 8) & 255);
            output.write(data.length & 255);
        } else {
            output.write(0x80 | 127);
            output.write(new byte[]{0,0,0,0,(byte)(data.length >>> 24),(byte)(data.length >>> 16),(byte)(data.length >>> 8),(byte)data.length});
        }
        output.write(mask);
        for (int i = 0; i < data.length; i++) output.write(data[i] ^ mask[i & 3]);
        output.flush();
    }

    void close() {
        stopped = true;
        SSLSocket s = socket;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1, a = -1, b = -1;
        while (bytes.size() < 16_384) {
            int c = in.read();
            if (c < 0) throw new EOFException("握手提前结束");
            bytes.write(c);
            if (previous == '\r' && a == '\n' && b == '\r' && c == '\n')
                return bytes.toString("US-ASCII");
            previous = a; a = b; b = c;
        }
        throw new IOException("握手响应过大");
    }

    private static Frame readFrame(InputStream in) throws IOException {
        int first = in.read(), second = in.read();
        if (first < 0 || second < 0) throw new EOFException("服务器关闭连接");
        if ((second & 0x80) != 0) throw new IOException("服务端发送了带掩码的帧");
        long length = second & 127;
        if (length == 126) length = (readByte(in) << 8) | readByte(in);
        else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) length = (length << 8) | readByte(in);
        }
        if (length > 2_000_000) throw new IOException("WebSocket 帧过大");
        byte[] data = new byte[(int) length];
        int position = 0;
        while (position < data.length) {
            int count = in.read(data, position, data.length - position);
            if (count < 0) throw new EOFException("WebSocket 帧截断");
            position += count;
        }
        return new Frame((first & 0x80) != 0, first & 15, data);
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new EOFException("WebSocket 帧截断");
        return value;
    }

    private static final class Frame {
        final boolean fin;
        final int opcode;
        final byte[] data;
        Frame(boolean fin, int opcode, byte[] data) { this.fin = fin; this.opcode = opcode; this.data = data; }
    }
}
