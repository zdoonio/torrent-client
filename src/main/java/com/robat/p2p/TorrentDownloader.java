package com.robat.p2p;

import com.robat.bittorrent.BEncoderDecoder;
import com.robat.bittorrent.SHA1Hasher;
import com.robat.tracker.PeerFetcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
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

    public void validateTorrent(String torrentPath) throws Exception {
        byte[] torrentBytes = Files.readAllBytes(Paths.get(torrentPath));
        Object decodedObj = BEncoderDecoder.bdecode(torrentBytes);

        if (!(decodedObj instanceof Map)) {
            throw new IllegalArgumentException("Invalid torrent format");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> torrentMap = (Map<String, Object>) decodedObj;

        // Sprawdź kluczowe pola
        String announce = getString(torrentMap, "announce");
        if (announce == null || announce.isEmpty()) {
            System.out.println("⚠️ No tracker URL found in torrent");
        } else {
            System.out.println("Tracker: " + announce);
        }

        Map<String, Object> info = (Map<String, Object>) torrentMap.get("info");
        if (info == null) {
            throw new IllegalArgumentException("'info' key missing");
        }

        long pieceLength = getLongValue(info, "piece length");
        byte[] pieces = (byte[]) info.get("pieces");
        //long totalLength = getLongValue(info, "length");

        System.out.println("\nTorrent validation:");
        System.out.println("  Info Hash: " + SHA1Hasher.bytesToHex(SHA1Hasher.calculateInfoHash(torrentPath)));
        System.out.println("  Pieces: " + (pieces.length / 20));
        System.out.println("  Piece length: " + pieceLength);
        //System.out.println("  Total size: " + totalLength + " bytes (" +
        //        String.format("%.1f GB", totalLength / (1024.0 * 1024.0) / 1024.0) + ")");
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
        long pieceLength = getLongValue(info, "piece length");
        byte[] pieces = (byte[]) info.get("pieces");
        int numPieces = pieces.length / 20;

        System.out.println("Torrent info - Pieces: " + numPieces + ", Piece length: " + pieceLength);

        // Lista do przechowywania pobranych części
        Map<Integer, byte[]> downloadedPieces = new HashMap<>();
        int successfulConnections = 0;
        int connectionAttempts = 0;
        int maxConnectionAttempts = peers.size() * 3; // Spróbuj więcej razy

        for (int attempt = 0; attempt < maxConnectionAttempts && downloadedPieces.size() < numPieces; attempt++) {
            if (attempt >= peers.size()) {
                System.out.println("All initial peers exhausted, retrying...");
            }

            PeerFetcher.Peer peerInfo = peers.get(attempt % peers.size());
            System.out.println("Trying peer: " + peerInfo.getIp() + ":" + peerInfo.getPort() +
                    " (attempt " + attempt + "/" + maxConnectionAttempts + ")");

            connectionAttempts++;
            PeerConnection peerConn = new PeerConnection(peerInfo.getIp(), peerInfo.getPort());

            try {
                peerConn.testPeerConnection();
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

                // Pobieramy dane - zwracajmy się po sukcesie lub braku kawałków
                int piecesDownloadedFromThisPeer = 0;
                for (int i = 0; i < numPieces && downloadedPieces.size() < numPieces; i++) {
                    if (downloadedPieces.containsKey(i)) continue;

                    long pieceSizeLong = getPieceLength(info, i, numPieces, pieceLength);
                    int pieceSize = (int) pieceSizeLong;

                    byte[] pieceData = downloadPiece(peerConn, i, pieceSize);
                    if (pieceData != null) {
                        downloadedPieces.put(i, pieceData);
                        System.out.println("Downloaded piece " + i + " from " + peerInfo.getIp() +
                                " (" + (downloadedPieces.size()) + "/" + numPieces + ")");
                        piecesDownloadedFromThisPeer++;
                    }
                }

                if (piecesDownloadedFromThisPeer == 0) {
                    System.out.println("No new pieces from this peer, trying next...");
                }

                peerConn.close();

            } catch (Exception e) {
                System.err.println("Error with peer " + peerInfo.getIp() + ":" + peerInfo.getPort() +
                        " - " + e.getMessage());
                try {
                    peerConn.close();
                } catch (Exception closeEx) {
                    System.err.println("Error closing connection: " + closeEx.getMessage());
                }
            }

            // Pauza między próbami
            if (attempt < maxConnectionAttempts - 1) {
                try { Thread.sleep(500); } catch (InterruptedException ie) {}
            }
        }

        System.out.println("\n=== DOWNLOAD SUMMARY ===");
        System.out.println("Successful connections: " + successfulConnections + "/" + connectionAttempts);
        System.out.println("Downloaded pieces: " + downloadedPieces.size() + "/" + numPieces);
        System.out.println("========================\n");

        // Zapisz plik tylko jeśli kompletny
        if (downloadedPieces.size() == numPieces) {
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                for (int i = 0; i < numPieces; i++) {
                    byte[] piece = downloadedPieces.get(i);
                    if (piece != null) {
                        fos.write(piece);
                    }
                }
            }
            System.out.println("✅ Download complete!");
        } else {
            System.err.println("❌ Download incomplete: " +
                    downloadedPieces.size() + "/" + numPieces + " pieces");
        }
    }

    // Pomocnicze metody
    private long getLongValue(Map<String, Object> info, String key) {
        Object value = info.get(key);
        if (value instanceof Integer) return ((Integer) value).longValue();
        if (value instanceof Long) return (Long) value;
        throw new IllegalArgumentException("Invalid " + key + " type: " +
                (value != null ? value.getClass() : "null"));
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (!(val instanceof byte[]))
            throw new IllegalArgumentException("Expected bytes for key: " + key);
        return new String((byte[]) val, StandardCharsets.UTF_8);
    }

    private long getPieceLength(Map<String, Object> info, int index, int numPieces, long pieceLength) {
        if (index == numPieces - 1) {
            return getLongValue(info, "length") - (index * pieceLength);
        }
        return pieceLength;
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
