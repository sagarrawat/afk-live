package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.TemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/** GR-8: niche starter templates. */
@RestController
public class TemplateController {

    private final TemplateService templates;

    public TemplateController(TemplateService templates) {
        this.templates = templates;
    }

    public record ApplyRequest(String template, String language, String schedulePreset) {
    }

    @GetMapping("/api/templates")
    public List<TemplateService.TemplateView> list() {
        return templates.list();
    }

    @PostMapping("/api/streams/{id}/template")
    public TemplateService.Applied apply(@PathVariable Long id, @RequestBody ApplyRequest req, Principal principal) {
        return templates.apply(principal.getName(), id, req.template(), req.language(), req.schedulePreset());
    }
}
