package com.robat.p2p;

import java.io.*;
import java.net.*;

public class PeerConnection {

    private final String ip;
    private final int port;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    public PeerConnection(String ip, int port) {
        this.ip = ip;
        this.port = port;
    }

    public void connect() throws IOException {
        socket = new Socket(ip, port);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());
    }

    public byte[] receiveExact(int length) throws IOException {
        byte[] buffer = new byte[length];
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, total, length - total);
            if (read == -1) throw new EOFException("Connection closed");
            total += read;
        }
        return buffer;
    }

    public void send(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    public void close() throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}

