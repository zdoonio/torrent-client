package com.robat.p2p;

import java.io.*;
import java.util.Arrays;

public class BitTorrentHandshake {

    public static boolean performHandshake(PeerConnection peer, byte[] infoHash, String peerId) throws IOException {
        // Prepare handshake message
        byte[] pstr = "BitTorrent protocol".getBytes();
        byte[] reserved = new byte[8]; // all zeros
        byte[] handshake = new byte[1 + pstr.length + reserved.length + infoHash.length + peerId.getBytes().length];
        int offset = 0;

        handshake[offset++] = (byte) pstr.length;
        System.arraycopy(pstr, 0, handshake, offset, pstr.length);
        offset += pstr.length;
        System.arraycopy(reserved, 0, handshake, offset, reserved.length);
        offset += reserved.length;
        System.arraycopy(infoHash, 0, handshake, offset, infoHash.length);
        offset += infoHash.length;
        System.arraycopy(peerId.getBytes(), 0, handshake, offset, peerId.getBytes().length);

        peer.send(handshake);

        // Receive response
        byte[] response = peer.receiveExact(68);

        // Check pstrlen and pstr
        if (response[0] != pstr.length || !Arrays.equals(Arrays.copyOfRange(response, 1, 20), pstr)) {
            return false;
        }

        // Check info_hash
        if (!Arrays.equals(Arrays.copyOfRange(response, 28, 48), infoHash)) {
            return false;
        }

        return true;
    }
}

