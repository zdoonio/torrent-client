package com.robat.bittorrent;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Prosta implementacja bencodowania/odbencowania danych w stylu BitTorrent.
 *
 * <p>Kluczowe punkty:
 *   - Wszystkie klucze słowników przechowywane są jako String (UTF‑8).
 *   - Funkcje `bencode` i `bdecode` obsługują: int, byte[], String, List<Object>, Map<String,Object>.
 *   - Dla listy i mapy używamy rekursji.
 */
public class BEncoderDecoder {

    /* ---------- 1. Dekodowanie ---------- */

    /**
     * Dekoduje dane bencoded (byte[]) i zwraca obiekt Java.
     *
     * @param data bajty z pliku .torrent
     * @return dekodowany obiekt
     */
    public static Object bdecode(byte[] data) {
        if (data == null || data.length == 0)
            throw new IllegalArgumentException("Empty input");

        DecodeResult result = parseAny(data, 0);
        if (result.index != data.length)
            throw new IllegalArgumentException(
                    "Extra data after parsing at index " + result.index);

        return result.value;
    }

    /* ---------- 2. Kodowanie ---------- */

    /**
     * Koduje dowolny obiekt Java do formatu bencoded.
     *
     * @param obj obiekt do zakodowania
     * @return bajty z kodem bencoded
     */
    public static byte[] bencode(Object obj) {
        if (obj instanceof Integer)
            return encodeInt((Integer) obj);

        if (obj instanceof String)
            return encodeString(((String) obj).getBytes(StandardCharsets.UTF_8));

        if (obj instanceof byte[])
            return encodeString((byte[]) obj);

        if (obj instanceof List<?>)
            return encodeList((List<?>) obj);

        if (obj instanceof Map<?, ?>)
            return encodeDict((Map<String, Object>) obj);

        throw new IllegalArgumentException(
                "Unsupported type for bencoding: " + obj.getClass().getName());
    }

    /* ---------- 3. Pomocnicze metody kodowania ---------- */

    private static byte[] encodeInt(Integer i) {
        return ("i" + i + "e").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encodeString(byte[] data) {
        String len = Integer.toString(data.length);
        return (len + ":" + new String(data, StandardCharsets.ISO_8859_1))
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] encodeList(List<?> list) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('l');
        for (Object item : list)
            try { out.write(bencode(item)); } catch (IOException e) {}
        out.write('e');
        return out.toByteArray();
    }

    private static byte[] encodeDict(Map<String, Object> dict) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('d');

        // Keys must be sorted lexicographically
        List<String> keys = new ArrayList<>(dict.keySet());
        Collections.sort(keys);

        for (String key : keys) {
            try {
                out.write(encodeString(key.getBytes(StandardCharsets.UTF_8)));
                out.write(bencode(dict.get(key)));
            } catch (IOException e) {}
        }
        out.write('e');
        return out.toByteArray();
    }

    /* ---------- 4. Pomocnicze metody dekodowania ---------- */

    private static class DecodeResult {
        Object value;
        int index; // po zakończeniu parsowania

        DecodeResult(Object v, int i) { value = v; index = i; }
    }

    /** Rozpoczyna interpretację od indeksu 0 */
    private static DecodeResult parseAny(byte[] data, int idx) {
        byte first = data[idx];
        if (first == 'i')
            return parseInt(data, idx);
        if (first == 'l')
            return parseList(data, idx);
        if (first == 'd')
            return parseDict(data, idx);
        if (Character.isDigit(first))
            return parseString(data, idx);

        throw new IllegalArgumentException(
                "Invalid bencode type at index " + idx + ": " + (char) first);
    }

    private static DecodeResult parseInt(byte[] data, int idx) {
        // format: i<digits>e
        int end = indexOfByte(data, (byte) 'e', idx + 1);
        String num = new String(data, idx + 1, end - idx - 1, StandardCharsets.UTF_8);
        return new DecodeResult(Integer.parseInt(num), end + 1);
    }

    private static DecodeResult parseString(byte[] data, int idx) {
        // format: <len>:<bytes>
        int colon = indexOfByte(data, (byte) ':', idx);
        String lenStr = new String(data, idx, colon - idx, StandardCharsets.UTF_8);
        int length = Integer.parseInt(lenStr);

        int start = colon + 1;
        byte[] bytes = Arrays.copyOfRange(data, start, start + length);
        return new DecodeResult(bytes, start + length);
    }

    private static DecodeResult parseList(byte[] data, int idx) {
        // format: l<items>e
        List<Object> list = new ArrayList<>();
        int pos = idx + 1;   // po 'l'
        while (data[pos] != 'e') {
            DecodeResult item = parseAny(data, pos);
            list.add(item.value);
            pos = item.index;
        }
        return new DecodeResult(list, pos + 1); // po 'e'
    }

    private static DecodeResult parseDict(byte[] data, int idx) {
        Map<String, Object> map = new LinkedHashMap<>();
        int pos = idx + 1;   // po 'd'
        while (data[pos] != 'e') {
            // klucz
            DecodeResult keyRes = parseString(data, pos);
            String key = new String((byte[]) keyRes.value, StandardCharsets.UTF_8);

            // wartość
            DecodeResult valRes = parseAny(data, keyRes.index);

            map.put(key, valRes.value);
            pos = valRes.index;
        }
        return new DecodeResult(map, pos + 1); // po 'e'
    }

    private static int indexOfByte(byte[] data, byte target, int start) {
        for (int i = start; i < data.length; i++) {
            if (data[i] == target) return i;
        }
        throw new IllegalArgumentException("Expected byte " + (char) target + " not found");
    }

    /* ---------- 5. Czytanie pliku .torrent ---------- */

    /**
     * Wczytuje cały plik .torrent, dekoduje go i zwraca mapę z kluczem „info”.
     *
     * @return mapa odpowiadająca sekcji "info" w torrentcie
     */
    public static Map<String, Object> readTorrentInfo(String path, boolean isResources) throws IOException {
        byte[] data = isResources ? readTorrentFromResources(path) : Files.readAllBytes(Paths.get(path));
        Object root = bdecode(data);

        if (!(root instanceof Map))
            throw new IllegalArgumentException("Root element is not a dictionary");

        @SuppressWarnings("unchecked")
        Map<String, Object> torrentMap = (Map<String, Object>) root;

        Object infoObj = torrentMap.get("info");
        if (!(infoObj instanceof Map))
            throw new IllegalArgumentException("'info' entry is missing or not a dict");

        return (Map<String, Object>) infoObj;
    }

    public static byte[] readTorrentFromResources(String resourceName) throws IOException {
        try (InputStream in = BEncoderDecoder.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (in == null)
                throw new FileNotFoundException("Resource not found: " + resourceName);

            return in.readAllBytes();      // Java 9+
        }
    }


    /* ---------- 6. Przykładowe użycie ---------- */

    public static void main(String[] args) {
        try {
            // 1️⃣ Kodowanie/odkodowywanie prostego przykładu
            byte[] encoded = bencode("hello");
            System.out.println("Encoded: " + Arrays.toString(encoded));

            Object decoded = bdecode(encoded);
            if (decoded instanceof byte[]) {
                String s = new String((byte[]) decoded, StandardCharsets.UTF_8);
                System.out.println("Decoded string: " + s);
            }

            // 2️⃣ Czytanie pliku .torrent i wypisanie sekcji "info"
            Map<String, Object> info = readTorrentInfo("test.torrent", true);
            System.out.println("\n--- INFO SECTION ---");
            for (Map.Entry<String, Object> e : info.entrySet()) {
                System.out.printf("%s: %s%n", e.getKey(), e.getValue());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
