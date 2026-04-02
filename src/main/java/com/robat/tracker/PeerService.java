package com.robat.tracker;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Usługa pobierająca i przechowująca listy peerów w pamięci.
 */
@Service
public class PeerService {

    // map[torrentFile] -> lista peerów
    private final Map<String, List<PeerFetcher.Peer>> cache = new ConcurrentHashMap<>();

    /**
     * Pobiera (lub odświeża) listę peerów dla podanego torrentu.
     *
     * @param torrentPath ścieżka do pliku .torrent
     * @return lista peerów
     */
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

    /**
     * Czyści pamięć (np. przy żądaniu ręcznym).
     */
    public void clearCache() {
        cache.clear();
    }
}
