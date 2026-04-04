package com.robat.tracker;

import com.robat.p2p.TorrentDownloader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * Endpoint: /peers?torrent=plik.torrent
 */
@RestController
@RequestMapping("/peers")
public class PeersController {

    private final PeerService peerService;

    public PeersController(PeerService peerService) {
        this.peerService = peerService;
    }

    @GetMapping
    public List<PeerFetcher.Peer> getPeers(@RequestParam("torrent") String torrentPath) {
        return peerService.getPeers(torrentPath);
    }

    @PostMapping("/download")
    public ResponseEntity<String> download(@RequestParam("torrent") String torrentPath,
                                           @RequestParam("output") String outputPath) {

        try {
            List<PeerFetcher.Peer> peers = peerService.getPeers(torrentPath);
            TorrentDownloader downloader = new TorrentDownloader(torrentPath, peers);
            downloader.validateTorrent(torrentPath);
            downloader.downloadToFile(outputPath);
            return ResponseEntity.ok("Download complete.");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Download failed: " + e.getMessage());
        }
    }

    @GetMapping("/test")
    public ResponseEntity<String> testPeers(@RequestParam("torrent") String torrentPath) {
        try {
            peerService.testPeers(torrentPath);
            return ResponseEntity.ok("Test complete. Check logs for results.");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/count")
    public Map<String, Object> getPeerCount(@RequestParam("torrent") String torrentPath) {
        List<PeerFetcher.Peer> peers = peerService.getPeers(torrentPath);
        return Map.of(
                "total_peers", peers.size(),
                "timestamp", System.currentTimeMillis()
        );
    }


}

