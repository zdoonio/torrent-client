package com.robat.tracker;

import org.springframework.web.bind.annotation.*;
import java.util.List;

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
}

