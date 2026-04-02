package com.robat.tracker;

import com.robat.bittorrent.BEncoderDecoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * Pobiera listę peerów z trackera na podstawie pliku .torrent.
 */
public final class PeerFetcher {

    private PeerFetcher() { /* utility */ }

    /**
     * Zwraca listę (ip, port) dla danego torrentu.
     *
     * @param torrentPath ścieżka do pliku .torrent
     * @return lista par (IP, port)
     * @throws IOException          przy błędach I/O lub HTTP‑ie
     * @throws IllegalArgumentException jeśli torrent jest niepoprawny
     */
    public static List<Peer> getPeers(String torrentPath) throws IOException {
        // 1️⃣ Wczytanie i zdekodowanie torrentu
        byte[] torrentData = Files.readAllBytes(Path.of(torrentPath));
        Object decodedRoot = BEncoderDecoder.bdecode(torrentData);
        if (!(decodedRoot instanceof Map))
            throw new IllegalArgumentException("Torrent root is not a dictionary");

        @SuppressWarnings("unchecked")
        Map<String, Object> torrentMap = (Map<String, Object>) decodedRoot;

        // 2️⃣ Pobranie kluczy potrzebnych do zapytania
        String announceUrl = getString(torrentMap, "announce");
        Object infoObj = torrentMap.get("info");
        if (!(infoObj instanceof Map))
            throw new IllegalArgumentException("'info' key missing or not a dict");

        @SuppressWarnings("unchecked")
        Map<String, Object> infoDict = (Map<String, Object>) infoObj;

        // 3️⃣ Obliczanie info_hash
        byte[] infoBencoded = BEncoderDecoder.bencode(infoDict);
        byte[] infoHash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            infoHash = md.digest(infoBencoded);
        } catch (Exception e) { // nie powinno się zdarzyć
            throw new RuntimeException(e);
        }

        // 4️⃣ Obliczanie wartości „left”
        long left;
        if (infoDict.containsKey("length")) {
            left = getLong(infoDict, "length");
        } else if (infoDict.containsKey("files")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files =
                    (List<Map<String, Object>>) infoDict.get("files");
            left = files.stream().mapToLong(f -> getLong(f, "length")).sum();
        } else {
            throw new IllegalArgumentException(
                    "info dict has neither 'length' nor 'files'");
        }

        // 5️⃣ Tworzenie parametrów zapytania
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("info_hash", infoHash);                // bytes
        params.put("peer_id", generatePeerId());          // bytes
        params.put("port", 6881);
        params.put("uploaded", 0L);
        params.put("downloaded", 0L);
        params.put("left", left);
        params.put("compact", 1);                         // chcemy compact
        params.put("event", "started");
        params.put("numwant", 50);

        String fullUrl = buildAnnounceUrl(announceUrl, params);

        System.out.println("\nFull announce URL: " + fullUrl);

        // 6️⃣ Wysyłanie żądania HTTP GET
        HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
        conn.setRequestProperty("User-Agent", "Java-BitTorrent-Client/1.0");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int status = conn.getResponseCode();
        if (status != 200)
            throw new IOException("Tracker returned HTTP " + status);

        byte[] responseData;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) != -1) {
                bos.write(buf, 0, read);
            }
            responseData = bos.toByteArray();
        }

        // 7️⃣ Dekodowanie odpowiedzi
        Object trackerDecoded = BEncoderDecoder.bdecode(responseData);
        if (!(trackerDecoded instanceof Map))
            throw new IllegalArgumentException("Tracker response is not a dictionary");

        @SuppressWarnings("unchecked")
        Map<String, Object> trackerMap = (Map<String, Object>) trackerDecoded;

        // 8️⃣ Obsługa ewentualnych błędów
        if (trackerMap.containsKey("failure reason")) {
            String reason =
                    new String((byte[]) trackerMap.get("failure reason"),
                            StandardCharsets.UTF_8);
            throw new IOException("Tracker failure: " + reason);
        }

        // 9️⃣ Pobranie peerów
        Object peersObj = trackerMap.get("peers");
        if (peersObj == null)
            throw new IllegalArgumentException("'peers' key missing in tracker response");

        return parsePeers(peersObj);
    }

    /* ------------------------------------------------------------------ */
    /* --------------------- pomocnicze metody poniżej ------------------- */
    /* ------------------------------------------------------------------ */

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (!(val instanceof byte[]))
            throw new IllegalArgumentException("Expected bytes for key: " + key);
        return new String((byte[]) val, StandardCharsets.UTF_8);
    }

    private static long getLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (!(val instanceof Integer))
            throw new IllegalArgumentException("Expected int for key: " + key);
        return (Integer) val;
    }

    /** Generuje 20‑bajtowy peer_id typu "-JA0001-XXXX..." */
    private static byte[] generatePeerId() {
        String prefix = "-JA0001-";
        byte[] random = new byte[20 - prefix.length()];
        new Random().nextBytes(random);
        ByteBuffer buf = ByteBuffer.allocate(20);
        buf.put(prefix.getBytes(StandardCharsets.US_ASCII));
        buf.put(random);
        return buf.array();
    }

    /** Buduje URL z parametrami (bytes są kodowane w %XX) */
    private static String buildAnnounceUrl(String base, Map<String, Object> params)
            throws IOException {

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();

            if (val instanceof byte[]) {
                String encoded =
                        URLEncoder.encode(new String((byte[]) val,
                                StandardCharsets.ISO_8859_1), "UTF-8");
                parts.add(key + "=" + encoded);
            } else {
                parts.add(key + "=" + URLEncoder.encode(val.toString(), "UTF-8"));
            }
        }

        String query = String.join("&", parts);

        return base.contains("?") ? base + "&" + query : base + "?" + query;
    }

    /** Rozkodowuje format compact oraz non‑compact */
    private static List<Peer> parsePeers(Object peersObj) {
        if (peersObj instanceof byte[]) {
            byte[] data = (byte[]) peersObj;
            if (data.length % 6 != 0)
                throw new IllegalArgumentException("Invalid compact peers length");

            List<Peer> list = new ArrayList<>(data.length / 6);
            for (int i = 0; i < data.length; i += 6) {
                String ip =
                        String.format("%d.%d.%d.%d",
                                data[i] & 0xFF,
                                data[i + 1] & 0xFF,
                                data[i + 2] & 0xFF,
                                data[i + 3] & 0xFF);
                int port = ((data[i + 4] & 0xFF) << 8) | (data[i + 5] & 0xFF);
                list.add(new Peer(ip, port));
            }
            return list;
        } else if (peersObj instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> peersList =
                    (List<Map<String, Object>>) peersObj;
            List<Peer> list = new ArrayList<>(peersList.size());
            for (Map<String, Object> p : peersList) {
                String ip = new String((byte[]) p.get("ip"), StandardCharsets.UTF_8);
                int port = (Integer) p.get("port");
                list.add(new Peer(ip, port));
            }
            return list;
        } else {
            throw new IllegalArgumentException(
                    "Unknown peers format: " + peersObj.getClass());
        }
    }

    /** Prosta struktura peer – tylko IP i port */
    public static final class Peer {
        public final String ip;
        public final int port;

        public Peer(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }

        @Override
        public String toString() { return ip + ":" + port; }
    }

    /* ------------------------------------------------------------------ */
    /* ------------------------------ demo -------------------------------- */
    /* ------------------------------------------------------------------ */

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: java PeerFetcher <torrent-file>");
            return;
        }
        try {
            List<Peer> peers = getPeers(args[0]);
            System.out.printf("\nFound %d peers:\n", peers.size());
            for (int i = 0; i < Math.min(10, peers.size()); i++) {
                System.out.println("  " + peers.get(i));
            }
            if (peers.size() > 10)
                System.out.println("  ... and " + (peers.size() - 10) + " more");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

