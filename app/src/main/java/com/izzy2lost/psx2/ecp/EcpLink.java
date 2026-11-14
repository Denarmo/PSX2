package com.izzy2lost.psx2.ecp;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The ECP channel on Android (SPEC section 4): a TCP connection to the frontend's loopback port,
 * length-prefixed UTF-8 JSON frames, and the auth frame first. Sending is thread-safe; incoming
 * messages arrive on the reader thread.
 */
public final class EcpLink implements Closeable {
    public static final int MAX_FRAME = 16 * 1024 * 1024;

    public interface Listener {
        void onMessage(JSONObject message);

        /** The channel closed; error is null for a clean end of stream. */
        void onClosed(Throwable error);
    }

    private final Socket mSocket = new Socket();
    private OutputStream mOut;
    private volatile boolean mClosed;

    public void connect(int port, String token, int timeoutMs) throws IOException {
        mSocket.setTcpNoDelay(true);
        mSocket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeoutMs);
        mOut = new BufferedOutputStream(mSocket.getOutputStream(), 64 * 1024);
        JSONObject auth = new JSONObject();
        try {
            auth.put("type", "auth");
            auth.put("token", token);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        send(auth);
    }

    public void start(Listener listener) throws IOException {
        final InputStream in = new BufferedInputStream(mSocket.getInputStream(), 64 * 1024);
        Thread reader = new Thread(() -> {
            Throwable error = null;
            try {
                DataInputStream data = new DataInputStream(in);
                byte[] header = new byte[4];
                while (!mClosed) {
                    data.readFully(header);
                    int length = (header[0] & 0xff) | (header[1] & 0xff) << 8
                            | (header[2] & 0xff) << 16 | (header[3] & 0xff) << 24;
                    if (length < 0 || length > MAX_FRAME) throw new IOException("frame of " + length + " bytes");
                    byte[] body = new byte[length];
                    data.readFully(body);
                    JSONObject message;
                    try {
                        message = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    } catch (JSONException e) {
                        throw new IOException("invalid JSON frame", e);
                    }
                    listener.onMessage(message);
                }
            } catch (EOFException eof) {
                // The frontend closed the channel.
            } catch (Throwable t) {
                if (!mClosed) error = t;
            }
            close();
            listener.onClosed(error);
        }, "EcpReader");
        reader.setDaemon(true);
        reader.start();
    }

    public boolean isOpen() {
        return !mClosed && mSocket.isConnected();
    }

    public synchronized void send(JSONObject message) throws IOException {
        if (mClosed || mOut == null) throw new IOException("channel closed");
        byte[] body = message.toString().getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_FRAME) throw new IOException("frame of " + body.length + " bytes");
        int n = body.length;
        mOut.write(new byte[] {(byte) n, (byte) (n >> 8), (byte) (n >> 16), (byte) (n >> 24)});
        mOut.write(body);
        mOut.flush();
    }

    @Override
    public void close() {
        mClosed = true;
        try {
            mSocket.close();
        } catch (IOException ignored) {
        }
    }
}
