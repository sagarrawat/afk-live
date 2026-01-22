package com.afklive.streamer.endpoint;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.model.ScheduledVideo;
import com.afklive.streamer.repository.ScheduledVideoRepository;
import com.afklive.streamer.service.AiService;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.UserService;
import com.afklive.streamer.util.AppConstants;
import com.afklive.streamer.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ConvertController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConvertController.class);

    private final com.afklive.streamer.service.VideoConversionService conversionService;

    // Add UserDir resolver if needed, but currently service just takes path?
    // Actually VideoConversionService takes userDir path but only uses it for temp if needed?
    // The previous code in VideoConversionService ignored userDir arg mostly and used temp dirs.
    // So we can pass null or dummy.

    @PostMapping("/convert/shorts")
    public ResponseEntity<?> convertToShort(@RequestParam String fileName, java.security.Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);

        conversionService.convertToShort(null, username, fileName);
        return ResponseEntity.ok(ApiResponse.success("Conversion started", null));
    }

    @PostMapping("/convert/optimize")
    public ResponseEntity<?> optimizeVideo(
            @RequestParam String fileName,
            @RequestParam(defaultValue = "landscape") String mode,
            @RequestParam(defaultValue = "1080") int height,
            java.security.Principal principal
    ) {
        if (principal == null) return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        String username = SecurityUtils.getEmail(principal);

        conversionService.optimizeVideo(null, username, fileName, mode, height);
        return ResponseEntity.ok(ApiResponse.success("Optimization started", null));
    }
}
