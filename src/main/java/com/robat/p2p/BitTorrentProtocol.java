package com.robat.p2p;

import java.io.*;
import java.util.*;

public class BitTorrentProtocol {

    public static void sendInterested(PeerConnection peer) throws IOException {
        byte[] msg = new byte[]{0, 0, 0, 1, 2}; // length=1, id=2 (interested)
        peer.send(msg);
    }

    public static void sendRequest(PeerConnection peer, int pieceIndex, int begin, int length) throws IOException {
        byte[] msg = new byte[13];
        msg[0] = (byte)(length >> 24 & 0xFF);
        msg[1] = (byte)(length >> 16 & 0xFF);
        msg[2] = (byte)(length >> 8 & 0xFF);
        msg[3] = (byte)(length & 0xFF);
        msg[4] = 6; // request ID
        msg[5] = (byte)(pieceIndex >> 24 & 0xFF);
        msg[6] = (byte)(pieceIndex >> 16 & 0xFF);
        msg[7] = (byte)(pieceIndex >> 8 & 0xFF);
        msg[8] = (byte)(pieceIndex & 0xFF);
        msg[9] = (byte)(begin >> 24 & 0xFF);
        msg[10] = (byte)(begin >> 16 & 0xFF);
        msg[11] = (byte)(begin >> 8 & 0xFF);
        msg[12] = (byte)(begin & 0xFF);

        peer.send(msg);
    }

    public static int readMessageLength(PeerConnection peer) throws IOException {
        byte[] lenBytes = peer.receiveExact(4);
        return ((lenBytes[0] & 0xFF) << 24) |
                ((lenBytes[1] & 0xFF) << 16) |
                ((lenBytes[2] & 0xFF) << 8) |
                (lenBytes[3] & 0xFF);
    }

    public static int readMessageId(PeerConnection peer) throws IOException {
        byte[] idBytes = peer.receiveExact(1);
        return idBytes[0] & 0xFF;
    }
}

