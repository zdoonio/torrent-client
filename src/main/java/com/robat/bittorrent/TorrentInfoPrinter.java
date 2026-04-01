package com.robat.bittorrent;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class TorrentInfoPrinter {

    public static void main(String[] args) throws Exception {
        Map<String,Object> info = BEncoderDecoder.readTorrentInfo("test.torrent", true);
        printInfo(info);
    }

    private static void printInfo(Map<String,Object> info) {
        System.out.println("--- INFO SECTION ---");

        // 1. Pojedyncze bajty → String
        if (info.containsKey("name")) {
            byte[] nameBytes = (byte[]) info.get("name");
            System.out.println("name: " + new String(nameBytes, StandardCharsets.UTF_8));
        }

        // 2. listę plików (list of maps)
        if (info.containsKey("files")) {
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> files =
                    (List<Map<String,Object>>) info.get("files");
            System.out.println("files:");
            for (Map<String,Object> f : files) {
                long length = ((Number) f.get("length")).longValue();
                @SuppressWarnings("unchecked")
                List<byte[]> pathBytes = (List<byte[]>) f.get("path");

                // konwertujemy ścieżkę na String
                List<String> parts = new ArrayList<>();
                for (byte[] part : pathBytes)
                    parts.add(new String(part, StandardCharsets.UTF_8));

                System.out.println("  {length=" + length +
                        ", path=" + parts + "}");
            }
        }

        // 3. piece_length
        if (info.containsKey("piece length")) {
            int pieceLen = ((Number) info.get("piece length")).intValue();
            System.out.println("piece length: " + pieceLen);
        }

        // 4. pieces (hashy SHA‑1)
        if (info.containsKey("pieces")) {
            byte[] piecesBytes = (byte[]) info.get("pieces");
            System.out.print("pieces hashes:");
            for (int i = 0; i < piecesBytes.length; i += 20) {
                byte[] hash = Arrays.copyOfRange(piecesBytes, i, i + 20);
                System.out.print(" " + bytesToHex(hash));
            }
            System.out.println();
        }

        // 5. private flag
        if (info.containsKey("private")) {
            int priv = ((Number) info.get("private")).intValue();
            System.out.println("private: " + priv);
        }
    }

    /** Pomocnicza konwersja bajtów na heks */
    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
