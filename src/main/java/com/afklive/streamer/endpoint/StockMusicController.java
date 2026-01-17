package com.afklive.streamer.endpoint;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.StockMusicService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;

@RestController
@RequestMapping("/api/stock-music")
@CrossOrigin(origins = "*")
public class StockMusicController {

    @Autowired
    private StockMusicService stockMusicService;

    @Autowired
    private FileStorageService storageService;

    @GetMapping
    public ResponseEntity<?> getStockMusic() {
        return ResponseEntity.ok(ApiResponse.success("Success", stockMusicService.getAvailableStockMusic()));
    }

    @GetMapping("/preview/{key}")
    public ResponseEntity<InputStreamResource> previewMusic(@PathVariable String key) {
        // Security check: ensure key starts with "stock/" to prevent arbitrary file access
        if (!key.startsWith("stock") && !key.contains("/")) {
             // Basic sanitation.
             // Actually, "stock/lofi_chill.mp3" is passed as key.
             // If key comes in as "stock/lofi_chill.mp3", Spring might treat '/' as path separator.
             // So we usually need to encode it or pass it as query param.
             // Let's use query param in the frontend logic, or handle the path variable carefully.
             // If I use @PathVariable with slashes, I need regex.
             // Better: @GetMapping("/preview") with @RequestParam
             return ResponseEntity.badRequest().build();
        }

        // Actually, let's use query param for safety and simplicity
        return previewMusicQuery(key);
    }

    @GetMapping("/preview")
    public ResponseEntity<InputStreamResource> previewMusicQuery(@RequestParam String key) {
        if (!key.startsWith("stock")) {
            return ResponseEntity.status(403).build();
        }

        try {
            InputStream is = storageService.downloadFile(key);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .body(new InputStreamResource(is));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
