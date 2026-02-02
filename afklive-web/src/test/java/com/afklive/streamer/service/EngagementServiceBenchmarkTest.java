package com.afklive.streamer.service;

import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.EngagementActivityRepository;
import com.afklive.streamer.repository.ProcessedCommentRepository;
import com.afklive.streamer.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
public class EngagementServiceBenchmarkTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProcessedCommentRepository processedRepository;

    @Mock
    private EngagementActivityRepository activityRepository;

    @Mock
    private YouTubeService youTubeService;

    @Mock
    private AiService aiService;

    @InjectMocks
    private EngagementService engagementService;

    @Test
    public void benchmarkProcessEngagement() {
        // Setup 10,000 users, only 100 enabled
        int totalUsers = 10000;
        int enabledUsersCount = 100;
        List<User> enabledUsers = new ArrayList<>(enabledUsersCount);

        for (int i = 0; i < enabledUsersCount; i++) {
            User user = new User("user" + i);
            user.setAutoReplyEnabled(true);
            enabledUsers.add(user);
        }

        // Mock repository to return only enabled users (pagination simulation)
        // We simulate that the first page returns all 100 enabled users and no next page.
        // We use Sort.by("username") in the implementation, so the mock needs to return compatible Slice.
        Slice<User> slice = new SliceImpl<>(enabledUsers, PageRequest.of(0, 100, Sort.by("username")), false);

        when(userRepository.findByAutoReplyEnabledTrue(any(Pageable.class))).thenReturn(slice);

        lenient().when(youTubeService.isConnected(anyString())).thenReturn(false);

        long startTime = System.nanoTime();

        engagementService.processEngagement();

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        System.out.println("Benchmark Optimized (findBy...): " + durationMs + " ms for " + totalUsers + " simulated users (fetching " + enabledUsersCount + ").");
    }
}
