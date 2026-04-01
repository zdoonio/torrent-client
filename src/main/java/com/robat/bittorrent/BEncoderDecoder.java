package com.robat.bittorrent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class BEncoderDecoder {

    // Funkcja do parsowania liczb (bencode integer 'i...e')
    private static int parseInt(byte[] data, int i) throws IllegalArgumentException {
        if (data[i] != 105) { // ord('i') == 105
            throw new IllegalArgumentException("Expected 'i' for integer");
        }
        int j = findChar(data, i + 1, 101); // Find 'e' (ord('e') == 101)
        String valStr = new String(Arrays.copyOfRange(data, i + 1, j), StandardCharsets.UTF_8);
        return Integer.parseInt(valStr);
    }

    // Funkcja do parsowania stringów (bencode string 'length:content')
    private static byte[] parseString(byte[] data, int i) throws IllegalArgumentException {
        if (!isDigit(data[i])) {
            throw new IllegalArgumentException("Expected length prefix for string");
        }
        int j = findChar(data, i + 1, 58); // Find ':' (ord(':') == 58)
        String lenStr = new String(Arrays.copyOfRange(data, i, j), StandardCharsets.UTF_8);
        int length = Integer.parseInt(lenStr);

        return Arrays.copyOfRange(data, j + 1, j + 1 + length); // Extract actual data
    }

    // Funkcja do parsowania list (bencode list 'l...e')
    private static List<Object> parseList(byte[] data, int i) throws IllegalArgumentException {
        if (data[i] != 108) { // ord('l') == 108
            throw new IllegalArgumentException("Expected 'l' for list");
        }
        ArrayList<Object> arr = new ArrayList<>();
        while (i < data.length && data[i] != 101) { // ord('e') == 101
            Object val = parseAny(data, i);
            arr.add(val);
        }
        return arr;
    }

    // Funkcja do parsowania słownika (bencode dict 'd...e')
    private static Map<Byte[], Object> parseDict(byte[] data, int i) throws IllegalArgumentException {
        if (data[i] != 100) { // ord('d') == 100
            throw new IllegalArgumentException("Expected 'd' for dict");
        }
        HashMap<Byte[], Object> d = new LinkedHashMap<>(); // Use LinkedHashMap to maintain order consistency
        while (i < data.length && data[i] != 101) { // ord('e') == 101
            byte[] key = parseString(data, i);
            int nextIndex = i + parseStringLength(data, i + parseStringLength(data, i)) - 1;

            Object val = parseAny(data, i);
            d.put(key, val);
        }
        return d;
    }

    // Wsparcie dynamicznego wykrywania typu danych w parsowaniu (bencode any)
    private static Object parseAny(byte[] data, int i) throws IllegalArgumentException {
        byte b = data[i];
        if (b == 105) { // 'i' - integer
            return parseInt(data, i);
        } else if (b == 108) { // 'l' - list
            return parseList(data, i);
        } else if (b == 100) { // 'd' - dict
            return parseDict(data, i);
        } else if (isDigit(b)) { // Number followed by ':' indicates string
            return parseString(data, i);
        } else {
            throw new IllegalArgumentException("Invalid bencode type at index " + i + ": " + (char)b);
        }
    }

    // Funkcja pomocnicza do szukania znaku w tablicy bajtów
    private static int findChar(byte[] data, int startIndex, int target) {
        for (int i = startIndex; i < data.length; i++) {
            if (data[i] == target) return i;
        }
        throw new IllegalArgumentException("Expected character not found");
    }

    // Pomocnicza do sprawdzania czy bajt jest cyfrą ASCII
    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    // Funkcja pomocnicza: obliczanie długości stringu z nagłówka (długość + ':' - 1)
    private static int parseStringLength(int i, byte[] data) {
        StringBuilder sb = new StringBuilder();
        while (i < data.length && isDigit(data[i])) {
            sb.append(data[i]);
            i++;
        }
        return Integer.parseInt(sb.toString());
    }

    // Funkcja do bencowania danych (zwraca bajty)
    public static byte[] bencode(Object data) throws IllegalArgumentException {
        if (data instanceof Integer) {
            StringBuilder sb = new StringBuilder();
            sb.append((char)105); // 'i'
            sb.append(data.toString());
            sb.append((char)101); // 'e'
            return (sb.toString().getBytes(StandardCharsets.UTF_8));
        } else if (data instanceof byte[]) {
            byte[] bytes = (byte[]) data;
            StringBuilder sb = new StringBuilder();
            sb.append(String.valueOf(bytes.length).getBytes(StandardCharsets.UTF_8)[0]);
            for (byte b : bytes) sb.append(b);
            return (sb.toString().getBytes(StandardCharsets.UTF_8));
        } else if (data instanceof String) {
            byte[] encodedBytes = ((String) data).getBytes(StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            sb.append(String.valueOf(encodedBytes.length).getBytes(StandardCharsets.UTF_8)[0]);
            for (byte b : encodedBytes) sb.append(b);
            return (sb.toString().getBytes(StandardCharsets.UTF_8));
        } else if (data instanceof List) {
            List<Object> list = (List<Object>) data;
            byte[] result = new byte[0]; // Placeholder initialization, will be built dynamically
            return bencodeToBytes(list);
        } else if (data instanceof Map) {
            Map<Byte[], Object> dict = (Map<Byte[], Object>) data;
            List<byte[]> sortedKeys = new ArrayList<>(dict.keySet());
            Collections.sort(sortedKeys); // Sort keys to ensure consistency with bitTorrent spec
            return bencodeToBytes(new LinkedHashMap<>(){{ put(key, value); }}); // Will restructure if required
        } else {
            throw new IllegalArgumentException("Unsupported type for bencoding: " + data.getClass().getName());
        }
    }

    // Funkcja do odbencowania danych (parsuje bajty)
    public static Object bdecode(byte[] data) throws IllegalArgumentException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Empty input");
        }

        int index = parseAny(data, 0);
        if (index < data.length) {
            throw new IllegalArgumentException("Extra data after parsing at index " + index);
        }
        return parseResult(data, index);
    }

    // Uproszczona implementacja dla bencowania i odbencowania - zwraca bajty bezpośrednio.
    public static byte[] encode(Object value) throws IllegalArgumentException {
        if (value instanceof Integer) {
            StringBuilder sb = new StringBuilder();
            sb.append((char)105);
            sb.append(((Integer) value).toString());
            sb.append((char)101);
            return (sb.toString().getBytes(StandardCharsets.UTF_8));
        } else if (value instanceof String) {
            byte[] bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            sb.append(String.valueOf(bytes.length).getBytes(StandardCharsets.UTF_8)[0]);
            for (byte b : bytes) sb.append(b);
            return (sb.toString().getBytes(StandardCharsets.UTF_8));
        } else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            StringBuilder sb = new StringBuilder();
            sb.append(String.valueOf(bytes.length).getBytes(StandardCharsets.UTF_8)[0]);
            for (byte b : bytes) sb.append(b);
            return (sb.toString().getBytes(StandardCharsets.UTF_8));
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            byte[] result = new byte[0]; // Placeholder initialization, will be built dynamically
            for (Object item : list) {
                result = concatenate(result, encode(item));
            }
            return ((char[])result.concat(new byte[]{(char)101}));
        } else if (value instanceof Map) {

            byte[] result = new byte[0]; // Placeholder initialization, will be built dynamically
            HashMap<Byte[], Object> map = new LinkedHashMap<>();
            for (byte[] key : map.keySet()) {
                result = concatenate(result, encode(key));
                result = concatenate(result, encode(map.get(key)));
            }
            return ((char[])result.concat(new byte[]{(char)101}));
        } else {
            throw new IllegalArgumentException("Unsupported type for bencoding: " + value.getClass().getName());
        }
    }

    // Funkcja pomocnicza do konkatu dwóch tablic bajtów
    private static byte[] concatenate(byte[] arr1, byte[] arr2) throws IllegalArgumentException {
        byte[] result = new byte[arr1.length + arr2.length];
        System.arraycopy(arr1, 0, result, 0, arr1.length);
        System.arraycopy(arr2, 0, result, arr1.length, arr2.length);
        return result;
    }

    // Funkcja do zapisu torrent z pliku i wyodrębnienia danych info (Bencodowane)
    public static void readTorrent() throws Exception {
        byte[] torrentData = Files.readAllBytes(Paths.get("test.torrent"));
        Object decoded = bdecode(torrentData);
        Map<Byte[], Object> infoDict = (Map<Byte[], Object>) decodeMap(decoded, 0)[1];
        System.out.println(infoDict.get(new byte[]{Byte.parseByte("info")}));
    }

    public static void main(String[] args) {
        try {
            // Przykładowe testowanie bencowania i odbencowania
            byte[] bencoded = encode("hello");
            Object decoded = bdecode(bencoded);
            System.out.println("Odblonowana wartość: " + decoded);

            // Przeczytaj plik torrent i wyświetl dane info
            readTorrent();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}

