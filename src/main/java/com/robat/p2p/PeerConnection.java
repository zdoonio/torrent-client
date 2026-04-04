package com.robat.p2p;

import java.io.*;
import java.net.*;

public class PeerConnection {

    private final String ip;
    private final int port;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private static final int TIMEOUT = 10000; // 10 sekund

    public PeerConnection(String ip, int port) {
        this.ip = ip;
        this.port = port;
    }

    public boolean connect() throws IOException {
        try {
            System.out.println("Trying to connect to " + ip + ":" + port);

            // Ustaw timeout
            socket = new Socket();
            socket.connect(new InetSocketAddress(ip, port), TIMEOUT);
            socket.setSoTimeout(TIMEOUT);

            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());

            System.out.println("Connected successfully to " + ip + ":" + port);
            return true;

        } catch (IOException e) {
            System.err.println("Failed to connect to " + ip + ":" + port + " - " + e.getMessage());
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            return false;
        }
    }

    public byte[] receiveExact(int length) throws IOException {
        if (in == null) throw new IOException("Not connected");

        byte[] buffer = new byte[length];
        int total = 0;
        while (total < length) {
            try {
                int read = in.read(buffer, total, length - total);
                if (read == -1) throw new EOFException("Connection closed");
                total += read;
            } catch (IOException e) {
                System.err.println("Read error from " + ip + ":" + port + " - " + e.getMessage());
                throw e;
            }
        }
        return buffer;
    }

    public void send(byte[] data) throws IOException {
        if (out == null) throw new IOException("Not connected");
        out.write(data);
        out.flush();
    }

    public void close() throws IOException {
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing connection: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }
}
