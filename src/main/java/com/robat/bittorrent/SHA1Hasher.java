package com.robat.bittorrent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Utility that calculates the BitTorrent “info_hash” (SHA‑1 of the bencoded
 * “info” dictionary) from a .torrent file.
 *
 * <p>It relies on {@link BEncoderDecoder} for decoding/encoding.
 */
public final class SHA1Hasher {

    private SHA1Hasher() { /* no instances */ }

    /**
     * Calculates the 20‑byte SHA‑1 hash of the bencoded “info” dictionary
     * inside a .torrent file.
     *
     * @param torrentPath path to the .torrent file
     * @return byte[20] – raw SHA‑1 digest (use {@code bytesToHex} if you need hex)
     * @throws IOException              if reading the file fails
     * @throws NoSuchAlgorithmException never thrown – SHA‑1 is guaranteed in JDK
     * @throws IllegalArgumentException if the torrent does not contain a valid “info” key
     */
    public static byte[] calculateInfoHash(Path torrentPath)
            throws IOException, NoSuchAlgorithmException {

        // 1️⃣ Read entire file
        byte[] torrentData = Files.readAllBytes(torrentPath);

        // 2️⃣ Decode the top‑level dictionary
        Object decodedRoot = BEncoderDecoder.bdecode(torrentData);
        if (!(decodedRoot instanceof Map)) {
            throw new IllegalArgumentException("Torrent root is not a dictionary");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> torrentMap = (Map<String, Object>) decodedRoot;

        // 3️⃣ Extract the “info” dictionary
        Object infoObj = torrentMap.get("info");
        if (!(infoObj instanceof Map)) {
            throw new IllegalArgumentException("'info' key missing or not a dict");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> infoDict = (Map<String, Object>) infoObj;

        // 4️⃣ Re‑encode the “info” dictionary exactly as BitTorrent demands
        byte[] bencodedInfo = BEncoderDecoder.bencode(infoDict);

        // 5️⃣ SHA‑1 digest
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        return md.digest(bencodedInfo);
    }

    /**
     * Convenience wrapper that accepts a {@code String} path.
     */
    public static byte[] calculateInfoHash(String torrentPath)
            throws IOException, NoSuchAlgorithmException {
        return calculateInfoHash(Path.of(torrentPath));
    }

    /* ----------------------------------------------------- */
    /* Helper: convert raw bytes to hex (optional)           */
    /* ----------------------------------------------------- */

    /**
     * Turns a byte array into an ASCII hex string.
     *
     * @param data 20‑byte digest
     * @return e.g. "3e7a5c1d..."
     */
    public static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /* ----------------------------------------------------- */
    /* Demo main – optional, can be removed in production   */
    /* ----------------------------------------------------- */

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: java SHA1Hasher <path-to-torrent>");
            return;
        }

        try {
            byte[] hash = calculateInfoHash(args[0]);
            System.out.printf("\nInfo Hash (hex): %s%n", bytesToHex(hash));
            System.out.printf("Info Hash (raw bytes): %s%n\n", new String(hash, "ISO-8859-1"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
