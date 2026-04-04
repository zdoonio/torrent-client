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
        System.out.println("Starting download from " + peers.size() + " peers");

        // Wczytaj metadane z pliku .torrent
        byte[] torrentBytes = Files.readAllBytes(Paths.get(torrentPath));
        Object decodedObj = BEncoderDecoder.bdecode(torrentBytes);

        if (!(decodedObj instanceof Map)) {
            throw new IllegalArgumentException("Torrent root is not a dictionary");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> decoded = (Map<String, Object>) decodedObj;

        Map<String, Object> info = (Map<String, Object>) decoded.get("info");
        if (info == null) {
            throw new IllegalArgumentException("'info' key missing from torrent");
        }

        byte[] infoHash = SHA1Hasher.calculateInfoHash(torrentPath);

        // Obsługa różnych typów danych
        Object pieceLengthObj = info.get("piece length");
        long pieceLength;
        if (pieceLengthObj instanceof Integer) {
            pieceLength = ((Integer) pieceLengthObj).longValue();
        } else if (pieceLengthObj instanceof Long) {
            pieceLength = (Long) pieceLengthObj;
        } else {
            throw new IllegalArgumentException("Invalid piece length type: " + pieceLengthObj.getClass());
        }

        byte[] pieces = (byte[]) info.get("pieces");
        int numPieces = pieces.length / 20;

        System.out.println("Torrent info - Pieces: " + numPieces + ", Piece length: " + pieceLength);

        // Lista do przechowywania pobranych części
        Map<Integer, byte[]> downloadedPieces = new HashMap<>();
        int successfulConnections = 0;

        for (PeerFetcher.Peer peerInfo : peers) {
            System.out.println("Trying peer: " + peerInfo.getIp() + ":" + peerInfo.getPort());

            PeerConnection peerConn = new PeerConnection(peerInfo.getIp(), peerInfo.getPort());
            try {
                if (!peerConn.connect()) {
                    System.err.println("Failed to connect to peer: " + peerInfo.getIp() + ":" + peerInfo.getPort());
                    continue;
                }

                successfulConnections++;

                if (!BitTorrentHandshake.performHandshake(peerConn, infoHash, peerId)) {
                    System.err.println("Handshake failed with peer: " + peerInfo.getIp() + ":" + peerInfo.getPort());
                    peerConn.close();
                    continue;
                }

                // Wysyłamy interesowanie
                BitTorrentProtocol.sendInterested(peerConn);

                // Pobieramy dane
                for (int i = 0; i < numPieces && downloadedPieces.size() < numPieces; i++) {
                    if (downloadedPieces.containsKey(i)) continue;

                    int pieceSize;
                    if (i == numPieces - 1) {
                        Object totalLengthObj = info.get("length");
                        long totalLength;
                        if (totalLengthObj instanceof Integer) {
                            totalLength = ((Integer) totalLengthObj).longValue();
                        } else if (totalLengthObj instanceof Long) {
                            totalLength = (Long) totalLengthObj;
                        } else {
                            throw new IllegalArgumentException("Invalid total length type");
                        }
                        pieceSize = (int) (totalLength - (i * pieceLength));
                    } else {
                        pieceSize = (int) pieceLength;
                    }

                    byte[] pieceData = downloadPiece(peerConn, i, pieceSize);
                    if (pieceData != null) {
                        downloadedPieces.put(i, pieceData);
                        System.out.println("Downloaded piece " + i + " from " + peerInfo.getIp());
                    }
                }

                peerConn.close();

            } catch (Exception e) {
                System.err.println("Error with peer " + peerInfo.getIp() + ":" + peerInfo.getPort() + " - " + e.getMessage());
                try {
                    peerConn.close();
                } catch (Exception closeEx) {
                    System.err.println("Error closing connection: " + closeEx.getMessage());
                }
            }
        }

        System.out.println("Successful connections: " + successfulConnections + "/" + peers.size());
        System.out.println("Downloaded pieces: " + downloadedPieces.size() + "/" + numPieces);

        // Zapisz plik
        if (downloadedPieces.size() == numPieces) {
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                for (int i = 0; i < numPieces; i++) {
                    byte[] piece = downloadedPieces.get(i);
                    if (piece != null) {
                        fos.write(piece);
                    }
                }
            }
            System.out.println("Download complete!");
        } else {
            System.err.println("Download incomplete: " + downloadedPieces.size() + "/" + numPieces + " pieces downloaded");
        }
    }

    private byte[] downloadPiece(PeerConnection peer, int pieceIndex, long length) throws IOException {
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
