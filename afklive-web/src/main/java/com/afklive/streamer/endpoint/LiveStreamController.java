package com.afklive.streamer.endpoint;

import com.afklive.streamer.dto.StreamDtos.*;
import com.afklive.streamer.model.LiveStream;
import com.afklive.streamer.model.StreamItem;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.service.LiveStreamService;
import com.afklive.streamer.service.StreamScheduleService;
import com.afklive.streamer.streaming.StudioSpec;
import com.afklive.streamer.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Saved streams: create and configure a broadcast, go live, stop, and control it while live.
 * Everything is scoped to the signed-in user; ids of other users' streams are "not found".
 */
@RestController
@RequestMapping("/api/streams")
@RequiredArgsConstructor
public class LiveStreamController {

    private final LiveStreamService service;
    private final StreamScheduleService scheduleService;
    private final com.afklive.streamer.service.IngestService ingest;

    private static String email(Principal principal) {
        if (principal == null) {
            throw new IllegalStateException("Sign in first.");
        }
        return SecurityUtils.getEmail(principal);
    }

    @GetMapping
    public List<StreamSummary> list(Principal principal) {
        return service.list(email(principal));
    }

    @GetMapping("/quality-options")
    public List<QualityOption> qualityOptions(Principal principal) {
        return service.qualityOptions(email(principal));
    }

    @PostMapping
    public ResponseEntity<StreamView> create(@RequestBody CreateStream req, Principal principal) {
        LiveStream s = service.create(email(principal), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.view(email(principal), s.getId()));
    }

    @GetMapping("/{id}")
    public StreamView get(@PathVariable Long id, Principal principal) {
        return service.view(email(principal), id);
    }

    @PatchMapping("/{id}")
    public StreamView update(@PathVariable Long id, @RequestBody UpdateStream req, Principal principal) {
        service.update(email(principal), id, req);
        return service.view(email(principal), id);
    }

    /** PL-6 / AU-3. */
    @PutMapping("/{id}/presentation")
    public StreamView presentation(@PathVariable Long id, @RequestBody com.afklive.streamer.dto.StreamDtos.Presentation req, Principal principal) {
        service.setPresentation(email(principal), id, req);
        return service.view(email(principal), id);
    }

    /** PL-6: one video's own fit. */
    @PutMapping("/{id}/playlist/{position}/fit")
    public StreamView itemFit(@PathVariable Long id, @PathVariable int position, @RequestBody Map<String, String> body, Principal principal) {
        service.setItemFit(email(principal), id, position, body.get("fit"));
        return service.view(email(principal), id);
    }

    /** RE-4. */
    @GetMapping("/{id}/uptime")
    public com.afklive.streamer.dto.StreamDtos.Uptime uptime(@PathVariable Long id, Principal principal) {
        return service.uptime(email(principal), id, java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC")));
    }

    /** ST-4. */
    @PostMapping("/{id}/duplicate")
    public ResponseEntity<StreamView> duplicate(@PathVariable Long id, Principal principal) {
        LiveStream s = service.duplicate(email(principal), id);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.view(email(principal), s.getId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        service.delete(email(principal), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/playlist")
    public StreamView setPlaylist(@PathVariable Long id, @RequestBody Map<String, List<Long>> body, Principal principal) {
        service.setItems(email(principal), id, StreamItem.Kind.VIDEO, body.get("mediaIds"));
        return service.view(email(principal), id);
    }

    @PutMapping("/{id}/music")
    public StreamView setMusic(@PathVariable Long id, @RequestBody Map<String, List<Long>> body, Principal principal) {
        service.setItems(email(principal), id, StreamItem.Kind.AUDIO, body.get("mediaIds"));
        return service.view(email(principal), id);
    }

    @PutMapping("/{id}/destinations")
    public StreamView setDestinations(@PathVariable Long id, @RequestBody List<TargetRequest> body, Principal principal) {
        service.setTargets(email(principal), id, body);
        return service.view(email(principal), id);
    }

    @PutMapping("/{id}/overlays")
    public List<StudioSpec.OverlaySetting> setOverlays(@PathVariable Long id, @RequestBody List<StudioSpec.OverlaySetting> body,
                                                       Principal principal) {
        return service.setOverlays(email(principal), id, body);
    }

    @PutMapping("/{id}/scenes")
    public List<StudioSpec.SceneSetting> setScenes(@PathVariable Long id, @RequestBody List<StudioSpec.SceneSetting> body,
                                                   Principal principal) {
        return service.setScenes(email(principal), id, body);
    }

    // ---- going live and stopping

    @GetMapping("/{id}/preflight")
    public Preflight preflight(@PathVariable Long id, Principal principal) {
        return service.preflight(email(principal), id);
    }

    @PostMapping("/{id}/live")
    public ResponseEntity<?> goLive(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                    Principal principal) {
        // GL-7: "anyway" goes live past a capacity warning (the creator chose to).
        Object scene = body == null ? null : body.get("scene");
        boolean anyway = body != null && Boolean.parseBoolean(String.valueOf(body.get("anyway")));
        try {
            StreamJob job = service.goLive(email(principal), id, scene == null ? null : String.valueOf(scene), anyway);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", job.getId(), "state", "STARTING"));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Another request created this stream's run first (the database allows one live run per
            // stream): this one gets that run, as a double tap should.
            return service.liveRun(email(principal), id)
                    .<ResponseEntity<?>>map(job -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", job.getId(), "state", "STARTING")))
                    .orElseThrow(() -> e);
        } catch (LiveStreamService.PreflightFailedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false, "message", e.getMessage(), "preflight", e.preflight()));
        }
    }

    @PostMapping("/{id}/stop")
    public LiveState stop(@PathVariable Long id, @RequestBody(required = false) StopRequest body, Principal principal) {
        return service.stop(email(principal), id, body);
    }

    @DeleteMapping("/{id}/stop-at")
    public ResponseEntity<Void> cancelStop(@PathVariable Long id, Principal principal) {
        service.cancelScheduledStop(email(principal), id);
        return ResponseEntity.noContent().build();
    }

    /** GL-3: take back "stop after this playlist pass". */
    @DeleteMapping("/{id}/finish-after-pass")
    public LiveState cancelFinishAfterPass(@PathVariable Long id, Principal principal) {
        return service.cancelFinishAfterPass(email(principal), id);
    }

    @GetMapping("/{id}/runs")
    public List<RunView> runs(@PathVariable Long id, Principal principal) {
        return service.runs(email(principal), id);
    }

    /** RE-9: destinations reached, reconnecting and why, not reaching, back again (recent runs, newest first). */
    @GetMapping("/{id}/timeline")
    public List<TimelineEvent> timeline(@PathVariable Long id, Principal principal) {
        return service.timeline(email(principal), id);
    }

    /** GL-1: tests each destination that hasn't passed a test in 24 hours with its current key. */
    @PostMapping("/{id}/destination-checks")
    public DestinationChecks startDestinationChecks(@PathVariable Long id, Principal principal) {
        return service.destinationChecks(email(principal), id, true);
    }

    /** GL-1: where those tests are (poll while one is TESTING). */
    @GetMapping("/{id}/destination-checks")
    public DestinationChecks destinationChecks(@PathVariable Long id, Principal principal) {
        return service.destinationChecks(email(principal), id, false);
    }

    // ---- live control

    @PostMapping("/{id}/scene")
    public ResponseEntity<Void> scene(@PathVariable Long id, @RequestBody Map<String, String> body, Principal principal) {
        service.switchScene(email(principal), id, body.get("scene"));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/overlays/{overlayId}/visible")
    public ResponseEntity<Void> overlayVisible(@PathVariable Long id, @PathVariable String overlayId,
                                               @RequestBody Map<String, Boolean> body, Principal principal) {
        service.setOverlayVisible(email(principal), id, overlayId, Boolean.TRUE.equals(body.get("visible")));
        return ResponseEntity.accepted().build();
    }

    /** GL-5. */
    @PostMapping("/{id}/reconnect")
    public ResponseEntity<Void> reconnect(@PathVariable Long id, Principal principal) {
        service.reconnect(email(principal), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/skip")
    public ResponseEntity<Void> skip(@PathVariable Long id, Principal principal) {
        service.skip(email(principal), id);
        return ResponseEntity.accepted().build();
    }

    // ---- phone (MS-1)

    /** Server, key, ready-made links and app guides for setting up the phone app. */
    @GetMapping("/{id}/ingest")
    public com.afklive.streamer.service.IngestService.Setup ingest(@PathVariable Long id, Principal principal) {
        LiveStream s = phoneStream(id, principal);
        return ingest.setup(ingest.ensureKey(s));
    }

    @PostMapping("/{id}/ingest/regenerate")
    public com.afklive.streamer.service.IngestService.Setup regenerate(@PathVariable Long id, Principal principal) {
        return ingest.setup(ingest.regenerate(phoneStream(id, principal)));
    }

    /** QR codes: {@code larix} (one-scan setup), {@code larix-srt}, {@code rtmp} (full URL with key) or {@code srt}. */
    @GetMapping(value = "/{id}/ingest/qr.png", produces = "image/png")
    public ResponseEntity<byte[]> ingestQr(@PathVariable Long id, @RequestParam(defaultValue = "larix") String type,
                                           Principal principal) {
        com.afklive.streamer.service.IngestService.Setup setup = ingest.setup(ingest.ensureKey(phoneStream(id, principal)));
        String content = switch (type) {
            case "larix" -> setup.larixLink();
            case "larix-srt" -> setup.larixSrtLink();
            case "rtmp" -> setup.rtmpUrl();
            case "srt" -> setup.srtUrl();
            default -> throw new IllegalArgumentException("QR type must be larix, larix-srt, rtmp or srt.");
        };
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store") // it contains the stream key
                .body(ingest.qrPng(content, 360));
    }

    private LiveStream phoneStream(Long id, Principal principal) {
        LiveStream s = service.get(email(principal), id);
        if (s.getSource() != LiveStream.Source.PHONE) {
            throw new IllegalArgumentException("This stream plays a playlist; phone setup is for phone streams.");
        }
        return s;
    }

    // ---- schedules

    @GetMapping("/{id}/schedules")
    public List<ScheduleView> schedules(@PathVariable Long id, Principal principal) {
        return scheduleService.list(email(principal), id);
    }

    @PostMapping("/{id}/schedules")
    public ResponseEntity<ScheduleView> addSchedule(@PathVariable Long id, @RequestBody ScheduleRequest req, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(scheduleService.create(email(principal), id, req));
    }

    @PutMapping("/{id}/schedules/{scheduleId}")
    public ScheduleView updateSchedule(@PathVariable Long id, @PathVariable Long scheduleId, @RequestBody ScheduleRequest req,
                                       Principal principal) {
        return scheduleService.update(email(principal), id, scheduleId, req);
    }

    @DeleteMapping("/{id}/schedules/{scheduleId}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable Long id, @PathVariable Long scheduleId, Principal principal) {
        scheduleService.delete(email(principal), id, scheduleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/schedules/{scheduleId}/retry")
    public ResponseEntity<?> retrySchedule(@PathVariable Long id, @PathVariable Long scheduleId, Principal principal) {
        try {
            StreamJob job = scheduleService.retry(email(principal), id, scheduleId);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", job.getId(), "state", "STARTING"));
        } catch (LiveStreamService.PreflightFailedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false, "message", e.getMessage(), "preflight", e.preflight()));
        }
    }
}
