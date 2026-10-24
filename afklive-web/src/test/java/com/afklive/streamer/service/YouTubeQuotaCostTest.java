package com.afklive.streamer.service;

import com.afklive.streamer.aspect.YoutubeQuota;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The quota units recorded per YouTube call match YouTube's published costs. */
class YouTubeQuotaCostTest {

    private static YoutubeQuota quota(String method, Class<?>... params) throws NoSuchMethodException {
        return YouTubeService.class.getMethod(method, params).getAnnotation(YoutubeQuota.class);
    }

    @Test
    void postingALiveChatMessageCosts20Units() throws Exception {
        // https://developers.google.com/youtube/v3/live/docs/liveChatMessages/insert
        // "A call to this method has a quota cost of 20 units." (it was recorded as 50)
        YoutubeQuota q = quota("replyToLiveChat", String.class, String.class, String.class);
        assertThat(q.apiName()).isEqualTo("liveChatMessages.insert");
        assertThat(q.cost()).isEqualTo(20);
    }
}
