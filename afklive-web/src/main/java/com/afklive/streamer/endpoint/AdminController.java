package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.User;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.SupportTicket;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.repository.SupportTicketRepository;
import com.afklive.streamer.repository.PaymentAuditRepository;
import com.afklive.streamer.service.StreamService;
import com.afklive.streamer.service.FileStorageService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

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
    private final PaymentAuditRepository paymentAuditRepository;

    @GetMapping
    public String adminDashboard(
            Model model,
            @RequestParam(defaultValue = "0") int userPage,
            @RequestParam(defaultValue = "10") int userSize,
            @RequestParam(required = false) String userSearch,
            @RequestParam(defaultValue = "0") int streamPage,
            @RequestParam(defaultValue = "10") int streamSize,
            @RequestParam(defaultValue = "0") int ticketPage,
            @RequestParam(defaultValue = "10") int ticketSize,
            @RequestParam(defaultValue = "overview") String tab
    ) {
        // Validate tab
        if (!List.of("overview", "streams", "users", "support").contains(tab)) {
            tab = "overview";
        }

        // Users
        Page<User> usersPage;
        if (userSearch != null && !userSearch.trim().isEmpty()) {
            usersPage = userRepository.findByUsernameContainingIgnoreCaseOrFullNameContainingIgnoreCase(
                    userSearch.trim(), userSearch.trim(), PageRequest.of(userPage, userSize, Sort.by("username")));
        } else {
            usersPage = userRepository.findAll(PageRequest.of(userPage, userSize, Sort.by("username")));
        }

        // Active Streams
        Page<StreamJob> streamsPage = streamJobRepo.findAllByIsLiveTrue(PageRequest.of(streamPage, streamSize));

        // Tickets
        Page<SupportTicket> ticketsPage = supportTicketRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(ticketPage, ticketSize));

        // Stats
        long totalStorage = userRepository.sumUsedStorageBytes();
        String formattedStorage = String.format("%.2f GB", totalStorage / (1024.0 * 1024.0 * 1024.0));

        Double totalUnpaid = userRepository.sumUnpaidBalance();
        Long completedPaymentsPaise = paymentAuditRepository.sumAmountByStatus("COMPLETED");
        Long initiatedPaymentsPaise = paymentAuditRepository.sumAmountByStatus("INITIATED");

        double completedPayments = completedPaymentsPaise != null ? completedPaymentsPaise / 100.0 : 0.0;
        double pendingPayments = initiatedPaymentsPaise != null ? initiatedPaymentsPaise / 100.0 : 0.0;

        model.addAttribute("users", usersPage); // Now a Page object
        model.addAttribute("activeStreamList", streamsPage); // Now a Page object
        model.addAttribute("supportTickets", ticketsPage); // Now a Page object
        model.addAttribute("userSearch", userSearch);
        model.addAttribute("activeTab", tab); // Pass tab to frontend

        model.addAttribute("stats", Map.of(
            "totalUsers", userRepository.count(),
            "activeStreams", streamJobRepo.countByIsLiveTrue(),
            "formattedStorage", formattedStorage,
            "openTickets", supportTicketRepository.countByStatus("OPEN"),
            "totalUnpaid", String.format("%.2f INR", totalUnpaid != null ? totalUnpaid : 0.0),
            "completedPayments", String.format("%.2f INR", completedPayments),
            "pendingPayments", String.format("%.2f INR", pendingPayments)
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

    @PostMapping("/users/{username}/limit")
    public String updateUserLimit(@PathVariable String username, @RequestParam double limit) {
        userRepository.findById(username).ifPresent(user -> {
            user.setCreditLimit(limit);
            userRepository.save(user);
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
