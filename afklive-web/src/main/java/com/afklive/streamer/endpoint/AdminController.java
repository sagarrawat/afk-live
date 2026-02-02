package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.User;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.SupportTicket;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.SupportTicketRepository;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping
    public String adminDashboard(Model model) {
        List<User> users = userRepository.findAll();
        List<StreamJob> activeStreams = streamJobRepo.findAllByIsLiveTrue();
        List<SupportTicket> tickets = supportTicketRepository.findAllByOrderByCreatedAtDesc();

        long totalStorage = users.stream().mapToLong(User::getUsedStorageBytes).sum();

        model.addAttribute("users", users);
        model.addAttribute("activeStreamList", activeStreams);
        model.addAttribute("supportTickets", tickets);
        model.addAttribute("stats", Map.of(
            "totalUsers", users.size(),
            "activeStreams", activeStreams.size(),
            "formattedStorage", (totalStorage / 1024 / 1024) + " MB",
            "openTickets", tickets.stream().filter(t -> "OPEN".equals(t.getStatus())).count()
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

    @GetMapping("/support/attachment/{id}")
    @ResponseBody
    public ResponseEntity<?> downloadAttachment(@PathVariable Long id) {
        return supportTicketRepository.findById(id)
                .map(ticket -> {
                    if (ticket.getAttachmentKey() == null) {
                        return ResponseEntity.notFound().build();
                    }
                    Resource file = storageService.loadFileAsResource(ticket.getAttachmentKey());

                    ContentDisposition contentDisposition = ContentDisposition.builder("attachment")
                            .filename(ticket.getAttachmentName() != null ? ticket.getAttachmentName() : "attachment")
                            .build();

                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                            .body(file);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
