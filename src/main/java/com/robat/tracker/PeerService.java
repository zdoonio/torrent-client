package com.robat.tracker;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.robat.p2p.PeerConnection;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PeerService {

    private static final Logger logger = LoggerFactory.getLogger(PeerService.class);

    // Publiczne trackery do fallbacku
    private static final List<String> PUBLIC_TRACKERS = Arrays.asList(
            "http://tracker.openbittorrent.com:80/announce",
            "http://tracker.opentrackr.org:1337/announce",
            "https://bt2.leechpc.cc:6969/announce",
            "http://p4p.arenabg.com:1514/announce",
            "udp://open.stealth.si:80/announce"
    );

    private final ConcurrentHashMap<String, List<PeerFetcher.Peer>> cache =
            new ConcurrentHashMap<>();

    public List<PeerFetcher.Peer> getPeers(String torrentPath) {
        // Prosty „cache‑by‑file” – przy każdym wywołaniu odświeża dane.
        return cache.computeIfAbsent(torrentPath, this::loadFromTracker);
    }

    private List<PeerFetcher.Peer> loadFromTracker(String torrentPath) {
        try {
            System.out.println("Fetching peers for " + torrentPath);
            return PeerFetcher.getPeers(torrentPath);   // z poprzedniego kodu
        } catch (Exception e) {
            throw new RuntimeException(
                    "Nie udało się pobrać peerów dla " + torrentPath, e);
        }
    }

    public void testPeers(String torrentPath) throws Exception {
        List<PeerFetcher.Peer> peers = getPeers(torrentPath);

        System.out.println("Testing " + peers.size() + " peers...");

        int workingCount = 0;
        for (PeerFetcher.Peer peer : peers) {
            PeerConnection conn = new PeerConnection(peer.getIp(), peer.getPort());
            try {
                if (conn.connect()) {
                    byte[] response = conn.receiveExact(68);
                    System.out.println("✅ " + peer + " - Handshake OK");
                    workingCount++;
                    conn.close();
                } else {
                    System.err.println("❌ " + peer + " - Connection failed");
                }
            } catch (Exception e) {
                System.err.println("❌ " + peer + " - Error: " + e.getMessage());
                try { conn.close(); } catch (IOException ignored) {}
            }
        }

        System.out.println("\nWorking peers: " + workingCount + "/" + peers.size());
    }


    public void clearCache() {
        cache.clear();
    }
}
