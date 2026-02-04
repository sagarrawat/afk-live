package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.User;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.SupportTicket;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.SupportTicketRepository;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.QuotaTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final StreamJobRepository streamJobRepo;
    private final StreamService streamService;
    private final SupportTicketRepository supportTicketRepository;
    private final FileStorageService storageService;
    private final QuotaTrackingService quotaTrackingService;

    @GetMapping
    public String adminDashboard(Model model) {
        List<User> users = userRepository.findAll();
        List<StreamJob> activeStreams = streamJobRepo.findAllByIsLiveTrue();
        List<SupportTicket> tickets = supportTicketRepository.findAllByOrderByCreatedAtDesc();

        long totalStorage = users.stream().mapToLong(User::getUsedStorageBytes).sum();
        String formattedStorage = String.format("%.2f GB", totalStorage / (1024.0 * 1024.0 * 1024.0));

        // Quota Stats
        int quotaUsedToday = quotaTrackingService.getDailyUsage();
        Map<String, Long> quotaBreakdown = quotaTrackingService.getDailyBreakdown();

        model.addAttribute("users", users);
        model.addAttribute("activeStreamList", activeStreams);
        model.addAttribute("supportTickets", tickets);
        model.addAttribute("quotaBreakdown", quotaBreakdown);
        model.addAttribute("stats", Map.of(
            "totalUsers", users.size(),
            "activeStreams", activeStreams.size(),
            "formattedStorage", formattedStorage,
            "openTickets", tickets.stream().filter(t -> "OPEN".equals(t.getStatus())).count(),
            "quotaUsedToday", quotaUsedToday
        ));

        return "admin";
    }

    @PostMapping("/streams/{id}/stop")
    public String forceStopStream(@PathVariable Long id) {
        // Find owner to stop correctly via service
        streamJobRepo.findById(id).ifPresent(job -> {
            streamService.stopStream(id, job.getUsername());
        });
        return "redirect:/admin";
    }

    @PostMapping("/users/{username}/blacklist")
    public String toggleUserBlacklist(@PathVariable String username, @RequestParam boolean enabled) {
        userRepository.findById(username).ifPresent(user -> {
            user.setEnabled(enabled);
            userRepository.save(user);
        });
        return "redirect:/admin";
    }

    @PostMapping("/support/{id}/status")
    public String updateTicketStatus(@PathVariable Long id, @RequestParam String status) {
        supportTicketRepository.findById(id).ifPresent(ticket -> {
            ticket.setStatus(status);
            supportTicketRepository.save(ticket);
        });
        return "redirect:/admin";
    }

    @GetMapping("/support/attachment/{id}")
    @ResponseBody
    public ResponseEntity<?> downloadAttachment(@PathVariable Long id) {
        return supportTicketRepository.findById(id)
                .map(ticket -> {
                    if (ticket.getAttachmentKey() == null) {
                        return ResponseEntity.notFound().build();
                    }
                    Resource file = storageService.loadFileAsResource(ticket.getAttachmentKey());

                    ContentDisposition contentDisposition = ContentDisposition.builder("inline")
                            .filename(ticket.getAttachmentName() != null ? ticket.getAttachmentName() : "attachment")
                            .build();

                    // Try to guess content type
                    String contentType = "application/octet-stream";
                    if (ticket.getAttachmentName() != null) {
                        String name = ticket.getAttachmentName().toLowerCase();
                        if (name.endsWith(".png")) contentType = "image/png";
                        else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) contentType = "image/jpeg";
                        else if (name.endsWith(".mp4")) contentType = "video/mp4";
                        else if (name.endsWith(".pdf")) contentType = "application/pdf";
                    }

                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                            .contentType(MediaType.parseMediaType(contentType))
                            .body(file);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
