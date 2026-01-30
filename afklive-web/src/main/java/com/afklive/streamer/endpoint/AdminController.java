package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.User;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.repository.StreamJobRepository;
import com.afklive.streamer.service.StreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final StreamJobRepository streamJobRepo;
    private final StreamService streamService;

    @GetMapping
    public String adminDashboard(Model model) {
        List<User> users = userRepository.findAll();
        List<StreamJob> activeStreams = streamJobRepo.findAllByIsLiveTrue();

        long totalStorage = users.stream().mapToLong(User::getUsedStorageBytes).sum();

        model.addAttribute("users", users);
        model.addAttribute("activeStreamList", activeStreams);
        model.addAttribute("stats", Map.of(
            "totalUsers", users.size(),
            "activeStreams", activeStreams.size(),
            "formattedStorage", (totalStorage / 1024 / 1024) + " MB"
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
}
