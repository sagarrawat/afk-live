package com.afklive.streamer.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class AiService {

    private final Random random = new Random();

    public String generateTitle(String context) {
        String[] templates = {
            "Amazing Video about " + context,
            "Why " + context + " is a Game Changer",
            "The Ultimate Guide to " + context,
            "Unboxing " + context + " (2024 Edition)",
            context + ": Explained in 5 Minutes"
        };
        return templates[random.nextInt(templates.length)];
    }

    public String generateDescription(String title) {
        return "In this video, we dive deep into " + title + ". \n\n" +
               "Make sure to like and subscribe for more content about this topic! \n" +
               "Follow us on social media for updates.";
    }

    public String generateTags(String context) {
        String base = context.toLowerCase().replaceAll("\\s+", ",");
        return base + ",viral,trending,2024,guide,tutorial,review";
    }
}
