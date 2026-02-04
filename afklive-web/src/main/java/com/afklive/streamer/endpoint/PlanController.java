package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;

    @GetMapping
    public ResponseEntity<List<PlanConfig>> getAllPlans() {
        return ResponseEntity.ok(planService.getAllPlans());
    }
}
