package com.robat.p2p;

import com.robat.bittorrent.BEncoderDecoder;
import com.robat.bittorrent.SHA1Hasher;
import com.robat.tracker.PeerFetcher;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class TorrentDownloader {

    private final String torrentPath;
    private final List<PeerFetcher.Peer> peers;
    private final String peerId = "-PY0001-" + "0".repeat(12);

    public TorrentDownloader(String torrentPath, List<PeerFetcher.Peer> peers) {
        this.torrentPath = torrentPath;
        this.peers = peers;
    }

    public void downloadToFile(String outputPath) throws Exception {
        // Wczytaj metadane z pliku .torrent
        byte[] torrentBytes = Files.readAllBytes(Paths.get(torrentPath));
        Object decodedObj = BEncoderDecoder.bdecode(torrentBytes);

        if (!(decodedObj instanceof Map)) {
            throw new IllegalArgumentException("Torrent root is not a dictionary");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> decoded = (Map<String, Object>) decodedObj;

        Map<String, Object> info = (Map<String, Object>) decoded.get("info");
        byte[] infoHash = SHA1Hasher.calculateInfoHash(torrentPath);

        int pieceLength = (Integer) info.get("piece length");
        byte[] pieces = (byte[]) info.get("pieces");

        int numPieces = pieces.length / 20;

        // Lista do przechowywania pobranych części
        Map<Integer, byte[]> downloadedPieces = new HashMap<>();

        for (PeerFetcher.Peer peerInfo : peers) {
            PeerConnection peerConn = new PeerConnection(peerInfo.getIp(), peerInfo.getPort());
            try {
                peerConn.connect();
                if (!BitTorrentHandshake.performHandshake(peerConn, infoHash, peerId)) {
                    continue;
                }

                // Wysyłamy interesowanie
                BitTorrentProtocol.sendInterested(peerConn);

                // Pobieramy dane (uproszczony przykład)
                for (int i = 0; i < numPieces && downloadedPieces.size() < numPieces; i++) {
                    if (downloadedPieces.containsKey(i)) continue;

                    int pieceSize = (i == numPieces - 1) ?
                            ((Integer) info.get("length")) - (i * pieceLength) : pieceLength;

                    byte[] pieceData = downloadPiece(peerConn, i, pieceSize);
                    if (pieceData != null) {
                        downloadedPieces.put(i, pieceData);
                        System.out.println("Downloaded piece " + i);
                    }
                }

                peerConn.close();
            } catch (Exception e) {
                System.err.println("Error with peer " + peerInfo.getIp() + ":" + peerInfo.getPort());
                peerConn.close();
            }
        }

        // Zapisz plik
        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            for (int i = 0; i < numPieces; i++) {
                byte[] piece = downloadedPieces.get(i);
                if (piece != null) {
                    fos.write(piece);
                }
            }
        }

        System.out.println("Download complete.");
    }

    private byte[] downloadPiece(PeerConnection peer, int pieceIndex, int length) throws IOException {
        // Wysyłamy request
        BitTorrentProtocol.sendRequest(peer, pieceIndex, 0, Math.min(16384, length));

        // Odbieramy dane
        int msgLength = BitTorrentProtocol.readMessageLength(peer);
        int msgId = BitTorrentProtocol.readMessageId(peer);

        if (msgId == 7) { // Piece message
            byte[] payload = peer.receiveExact(msgLength - 1);
            return Arrays.copyOfRange(payload, 8, payload.length); // Skopiuj blok danych
        }

        return null;
    }
}
