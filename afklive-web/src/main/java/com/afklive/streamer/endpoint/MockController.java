package com.afklive.streamer.endpoint;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Random;

@RestController
@RequestMapping("/api/mock")
public class MockController {

    @GetMapping("/analytics")
    public ResponseEntity<?> getFakeAnalytics() {
        // Return fake data for a graph (last 7 days)
        return ResponseEntity.ok(Map.of(
            "labels", List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
            "views", randomList(7, 100, 5000),
            "clicks", randomList(7, 10, 500)
        ));
    }

    private List<Integer> randomList(int size, int min, int max) {
        Random r = new Random();
        return r.ints(size, min, max).boxed().toList();
    }
}
