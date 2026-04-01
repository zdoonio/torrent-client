package com.robat;

import com.robat.bittorrent.PeerInfo;
import com.robat.bittorrent.TorrentMetadata;
import com.robat.p2p.TorrentPieceDownloader;
import com.robat.tracker.HttpTrackerClient;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MainApp {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("  java -jar bittorrent-java-console-1.0.0.jar <torrent.torrent> <output_file.bin>");
            return;
        }

        String torrentPath = args[0];
        String outputPath = args.length > 1 ? args[1] : "downloaded_file.bin";

        System.out.println("=== Java BitTorrent Console Client ===");
        System.out.println("Reading torrent: " + torrentPath);

        try {
         //   TorrentMetadata torrent = Bencode.readTorrent(torrentPath);

            // Get peers from tracker
//            List<PeerInfo> peers = HttpTrackerClient.getPeersFromTracker(
//                    torrentPath, torrent.getAnnounceUrl(), 6881, 50);

           // System.out.println("Found " + peers.size() + " peers.");

            // Download pieces concurrently (using Netty)
//            CompletableFuture<String> downloadFuture = TorrentPieceDownloader.downloadAsync(
//                    torrent,
//                    peers,
//                    outputPath,
//                    peerCount -> {
//                        for (int i = 0; i < peerCount; i++) {
//                            System.out.println("Connecting to peer " + i);
//                        }
//                        return CompletableFuture.completedFuture(null);
//                    });

//            downloadFuture.whenComplete((result, ex) -> {
//                if (ex == null) {
//                    System.out.println("Download complete! Result: " + result);
//                } else {
//                    System.out.println("Download failed: " + ex.getMessage());
//                }
//            });

        } catch (Exception e) {
            System.err.println("Error during download: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
