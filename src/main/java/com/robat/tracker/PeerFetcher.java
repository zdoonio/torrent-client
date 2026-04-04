package com.robat.tracker;

import com.robat.bittorrent.BEncoderDecoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pobiera listę peerów z trackera na podstawie pliku .torrent.
 */
public final class PeerFetcher {

    private PeerFetcher() { /* utility */ }

    public static List<Peer> getPeers(String torrentPath) throws IOException {
        /* ---------- 1️⃣ – wczytanie torrentu i obliczenie info_hash, left … ---------- */
        byte[] torrentData = Files.readAllBytes(Path.of(torrentPath));
        Object decodedRoot = BEncoderDecoder.bdecode(torrentData);
        if (!(decodedRoot instanceof Map))
            throw new IllegalArgumentException("Torrent root is not a dictionary");
        @SuppressWarnings("unchecked")
        Map<String, Object> torrentMap = (Map<String, Object>) decodedRoot;

        List<String> announceUrls = getStringList(torrentMap, "announce-list");

        List<Peer> peers = new ArrayList<>();

        for (String announceUrl : announceUrls) {
            /* ---------- 2️⃣ – sprawdzamy protokół ---------- */
            URI uri;
            try {
                uri = new URI(announceUrl);
            } catch (URISyntaxException e) {
                throw new IOException("Invalid announce URL: " + announceUrl, e);
            }
            String scheme = uri.getScheme();   // http / https / udp

            /* ---------- 3️⃣ – obliczamy info_hash i left tak samo jak wcześniej ---------- */
            Object infoObj = torrentMap.get("info");
            if (!(infoObj instanceof Map))
                throw new IllegalArgumentException("'info' key missing or not a dict");
            @SuppressWarnings("unchecked")
            Map<String, Object> infoDict = (Map<String, Object>) infoObj;

            byte[] infoBencoded = BEncoderDecoder.bencode(infoDict);
            MessageDigest md;
            try {
                md = MessageDigest.getInstance("SHA-1");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            byte[] infoHash = md.digest(infoBencoded);

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
            try {
                peers.addAll(fetchPeersWithRetry(announceUrl, torrentMap, infoHash, left));
            } catch (Exception e) {
                System.err.println("Failed with tracker " + announceUrl + ": " + e.getMessage());
                continue; // Try next tracker
            }
        }
        return peers;
    }

    private static List<Peer> fetchPeersWithRetry(String announceUrl,
                                                  Map<String, Object> torrentMap,
                                                  byte[] infoHash,
                                                  long left) throws IOException {
        URI uri;
        try {
            uri = new URI(announceUrl);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid announce URL: " + announceUrl, e);
        }
        String scheme = uri.getScheme();

        /* ---------- 4️⃣ – w zależności od protokołu wywołujemy odpowiedni kod ---------- */
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            // --- HTTP/HTTPS path (już istnieje)
            return fetchPeersHTTP(announceUrl, infoHash, left);
        } else if ("udp".equalsIgnoreCase(scheme)) {
            // --- UDP tracker
            byte[] peerId = generatePeerId();   // 20‑bajtowy peer_id
            return fetchPeersUDP(announceUrl, infoHash, peerId, left);
        } else {
            throw new IOException("Unsupported announce protocol: " + scheme);
        }
    }

    /* ------------------------------------------------------------------ */
    /* ---------- HTTP/HTTPS (twoja oryginalna logika) ----------------- */
    private static List<Peer> fetchPeersHTTP(String base,
                                             byte[] infoHash,
                                             long left) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("info_hash", infoHash);
        params.put("peer_id", generatePeerId());
        params.put("port", 6881);
        params.put("uploaded", 0L);
        params.put("downloaded", 0L);
        params.put("left", left);
        params.put("compact", 1);
        params.put("event", "started");
        params.put("numwant", 50);

        String fullUrl = buildAnnounceUrl(base, params);
        System.out.println("\nFull announce URL: " + fullUrl);

        HttpURLConnection conn =
                (HttpURLConnection) new URL(fullUrl).openConnection();
        conn.setRequestProperty("User-Agent",
                "Java-BitTorrent-Client/1.0");
        conn.setConnectTimeout(250);
        conn.setReadTimeout(250);

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

        Object trackerDecoded = BEncoderDecoder.bdecode(responseData);
        if (!(trackerDecoded instanceof Map))
            throw new IllegalArgumentException(
                    "Tracker response is not a dictionary");
        @SuppressWarnings("unchecked")
        Map<String, Object> trackerMap =
                (Map<String, Object>) trackerDecoded;

        if (trackerMap.containsKey("failure reason")) {
            String reason = new String((byte[]) trackerMap.get("failure reason"),
                    StandardCharsets.UTF_8);
            throw new IOException("Tracker failure: " + reason);
        }

        Object peersObj = trackerMap.get("peers");
        if (peersObj == null)
            throw new IllegalArgumentException("'peers' key missing in tracker response");

        return parsePeers(peersObj);
    }

    /* ------------------------------------------------------------------ */
    /* ---------- UDP tracker ------------------------------------------- */
    private static List<Peer> fetchPeersUDP(String announceUrl,
                                            byte[] infoHash,
                                            byte[] peerId,
                                            long left) throws IOException {
        // split: udp://host:port/announce
        URI uri;
        try {
            uri = new URI(announceUrl);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid UDP announce URL", e);
        }
        String host = uri.getHost();
        int port = uri.getPort();   // 1337

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(250);

            /* ---- 1️⃣ connect request ------------------------------------ */
            byte[] req = new byte[16];
            ByteBuffer bb = ByteBuffer.wrap(req);
            bb.putInt(0);                 // action: connect
            int transId = new Random().nextInt();
            bb.putInt(transId);

            socket.send(new DatagramPacket(req, req.length,
                    InetAddress.getByName(host), port));

            /* ---- 2️⃣ connect response ----------------------------------- */
            byte[] resp = new byte[16];
            DatagramPacket packet = new DatagramPacket(resp, resp.length);
            socket.receive(packet);

            ByteBuffer br = ByteBuffer.wrap(resp);
            int action = br.getInt();
            int rTransId = br.getInt();
            long connId = br.getLong();

            if (action != 0 || rTransId != transId)
                throw new IOException("Bad connect response");

            /* ---- 3️⃣ announce request ----------------------------------- */
            ByteBuffer annReq = ByteBuffer.allocate(98);
            annReq.putLong(connId);       // connection_id
            annReq.putInt(1);             // action: announce
            int aTransId = new Random().nextInt();
            annReq.putInt(aTransId);
            annReq.put(infoHash);         // 20 bytes
            annReq.put(peerId);           // 20 bytes
            annReq.putLong(left);          // left
            annReq.putLong(0L);           // downloaded
            annReq.putLong(0L);           // uploaded
            annReq.putInt(0);             // event: none (0)
            annReq.putShort((short) 6881);  // IP address (0 – let tracker fill)
            annReq.putInt(0);             // key
            annReq.putInt(-1);            // num_want

            socket.send(new DatagramPacket(annReq.array(), annReq.capacity(),
                    InetAddress.getByName(host), port));

            /* ---- 4️⃣ announce response ---------------------------------- */
            byte[] annResp = new byte[1024];
            packet = new DatagramPacket(annResp, annResp.length);
            socket.receive(packet);

            ByteBuffer ar = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
            int aAction = ar.getInt();
            int aTransIdRec = ar.getInt();

            if (aAction != 1 || aTransIdRec != aTransId)
                throw new IOException("Bad announce response");

            int interval = ar.getInt();   // ignore
            int leechers = ar.getInt();   // ignore
            int seeders = ar.getInt();    // ignore

            /* ---- 5️⃣ parse peers --------------------------------------- */
            List<Peer> peers = new ArrayList<>();
            while (ar.remaining() >= 6) {
                long ipLong = Integer.toUnsignedLong(ar.getInt());
                String ip = String.format("%d.%d.%d.%d",
                        (int) (ipLong >> 24 & 0xFF),
                        (int) (ipLong >> 16 & 0xFF),
                        (int) (ipLong >> 8 & 0xFF),
                        (int) (ipLong & 0xFF));
                int portNum = ar.getShort() & 0xFFFF;
                peers.add(new Peer(ip, portNum));
            }
            return peers;
        } catch (SocketTimeoutException e) {
            throw new IOException("UDP tracker timed out", e);
        }
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

    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object val = map.get(key);

        // Sprawdzamy czy wartość jest ArrayList
        if (!(val instanceof ArrayList)) {
            throw new IllegalArgumentException("Expected ArrayList for key: " + key);
        }

        @SuppressWarnings("unchecked")
        ArrayList<Object> arrayList = (ArrayList<Object>) val;

        // Flatten i konwersja wszystkich byte[] do String
        return arrayList.stream()
                .flatMap(item -> {
                    if (item instanceof List) {
                        // Jeśli element to lista, flattenujemy ją
                        @SuppressWarnings("unchecked")
                        List<Object> innerList = (List<Object>) item;
                        return innerList.stream()
                                .filter(innerItem -> innerItem instanceof byte[])
                                .map(innerItem -> new String((byte[]) innerItem, StandardCharsets.UTF_8));
                    } else if (item instanceof byte[]) {
                        // Jeśli element to pojedynczy byte[]
                        return Stream.of(new String((byte[]) item, StandardCharsets.UTF_8));
                    }
                    return Stream.empty();
                })
                .collect(Collectors.toList());
    }



    private static long getLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (!(val instanceof Long))
            throw new IllegalArgumentException("Expected int for key: " + key);
        return (Long) val;
    }

    /**
     * Generuje 20‑bajtowy peer_id typu "-JA0001-XXXX..."
     */
    private static byte[] generatePeerId() {
        String prefix = "-JA0001-";
        byte[] random = new byte[20 - prefix.length()];
        new Random().nextBytes(random);
        ByteBuffer buf = ByteBuffer.allocate(20);
        buf.put(prefix.getBytes(StandardCharsets.US_ASCII));
        buf.put(random);
        return buf.array();
    }

    /**
     * Buduje URL z parametrami (bytes są kodowane w %XX)
     */
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

    /**
     * Rozkodowuje format compact oraz non‑compact
     */
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

    /**
     * Prosta struktura peer – tylko IP i port
     */
    public static final class Peer {
        public final String ip;
        public final int port;

        public Peer(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }

        public String getIp() {
            return ip;
        }

        public int getPort() {
            return port;
        }

        @Override
        public String toString() {
            return ip + ":" + port;
        }
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

