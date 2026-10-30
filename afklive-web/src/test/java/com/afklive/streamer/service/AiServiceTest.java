package com.afklive.streamer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private AiService aiService;

    @BeforeEach
    void setUp() {
        // A dummy key turns the Gemini path on; the RestTemplate is a mock.
        aiService = new AiService("dummy-key", restTemplate);
    }

    private void geminiAnswers(String text) {
        Map<String, Object> response = Map.of("candidates", List.of(
                Map.of("content", Map.of("parts", List.of(Map.of("text", text))))));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private String promptSent() {
        ArgumentCaptor<HttpEntity<Map<String, Object>>> body = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(anyString(), body.capture(), eq(Map.class));
        List<Map<String, Object>> contents = (List<Map<String, Object>>) body.getValue().getBody().get("contents");
        List<Map<String, String>> parts = (List<Map<String, String>>) contents.getFirst().get("parts");
        return parts.getFirst().get("text");
    }

    // ------------------------------------------------------------------ prompt builders

    @Test
    void theTitlePromptAsksForAnHonestSearchableTitleNotAViralOne() {
        String p = AiService.titlePrompt("lofi beats to study");
        assertThat(p).contains("\"lofi beats to study\"");
        assertThat(p).as("growth plan rule 6 and the GR-9 title checks")
                .contains("words people would type into YouTube search")
                .contains("never write \"LIVE\", \"live now\"")
                .contains("recorded earlier")
                .contains("under 100 characters")
                .contains("No clickbait");
        assertThat(p.toLowerCase()).doesNotContain("viral").doesNotContain("catchy");
    }

    @Test
    void theDescriptionAndTagPromptsStayHonestToo() {
        assertThat(AiService.descriptionPrompt("Rain sounds for sleep"))
                .contains("\"Rain sounds for sleep\"").contains("don't call it live").contains("no clickbait");
        assertThat(AiService.tagsPrompt("rain sounds"))
                .contains("\"rain sounds\"").contains("No unrelated popular words such as viral, trending");
    }

    @Test
    void quotesInTheTopicCannotBreakOutOfThePrompt() {
        assertThat(AiService.titlePrompt("say \"LIVE NOW\" please")).contains("\"say 'LIVE NOW' please\"");
    }

    @Test
    void generateTitleSendsTheHonestPrompt() {
        geminiAnswers("Lofi beats to study and relax");
        assertThat(aiService.generateTitle("lofi beats")).isEqualTo("Lofi beats to study and relax");
        assertThat(promptSent()).isEqualTo(AiService.titlePrompt("lofi beats"));
    }

    // ------------------------------------------------------------------ the answer is checked with GR-9's rules

    @Test
    void aMisleadingLiveFromTheModelIsRewrittenLikeTheStreamCheckFix() {
        geminiAnswers("\"🔴 LIVE NOW: Lofi beats to study\"");
        String t = aiService.generateTitle("lofi");
        assertThat(GoLiveCheck.misleadingLive(t)).isNull();
        assertThat(t).startsWith("Lofi beats to study").endsWith("replay");
    }

    @Test
    void titlesAreOneLineWithinYouTubesLimitAndSearchable() {
        assertThat(AiService.cleanTitle("Hanuman Chalisa morning aarti\nHere is why I chose this")).isEqualTo("Hanuman Chalisa morning aarti");
        assertThat(AiService.cleanTitle("Rain " + "sounds ".repeat(30))).hasSizeLessThanOrEqualTo(AiService.TITLE_MAX);
        assertThat(AiService.cleanTitle("Live stream now")).as("nothing anyone searches for").isNull();
        assertThat(AiService.cleanTitle("   ")).isNull();
        assertThat(AiService.cleanTitle(null)).isNull();
    }

    @Test
    void anUnusableAnswerFallsBackToTheCreatorsOwnWords() {
        geminiAnswers("LIVE now");
        assertThat(aiService.generateTitle("rain sounds for sleep")).isEqualTo("Rain sounds for sleep");
    }

    @Test
    void withoutTheModelTheTitleIsTheTopicNotAnInventedHook() {
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenThrow(new RestClientException("API Error"));
        String t = aiService.generateTitle("lofi beats to study");
        assertThat(t).isEqualTo("Lofi beats to study");
        assertThat(AiService.fallbackTitle("LIVE NOW lofi beats")).doesNotContain("LIVE");
        assertThat(AiService.fallbackTitle("")).isEqualTo("Untitled stream");
        assertThat(new AiService("", restTemplate).generateTitle("bhajan")).isEqualTo("Bhajan");
    }

    @Test
    void fallbackTagsDescribeTheTopicOnly() {
        String tags = AiService.fallbackTags("Lofi beats to study");
        assertThat(tags).isEqualTo("lofi beats to study,lofi,beats,study");
        assertThat(tags).doesNotContain("viral").doesNotContain("trending").doesNotContain("2024");
    }

    // ------------------------------------------------------------------ title writer (AI-1)

    @Test
    @SuppressWarnings("unchecked")
    void theWriterAsksTheConfiguredModelForJsonWithTheKeyInAHeader() {
        AiService configured = new AiService("secret-key", "gemini-2.5-flash-lite", restTemplate);
        geminiAnswers("{\"titles\":[\"Rain sounds\"]}");
        assertThat(configured.isAvailable()).isTrue();
        assertThat(configured.completeJson("prompt")).isEqualTo("{\"titles\":[\"Rain sounds\"]}");
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity<Map<String, Object>>> body = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(url.capture(), body.capture(), eq(Map.class));
        assertThat(url.getValue()).contains("/models/gemini-2.5-flash-lite:generateContent").doesNotContain("secret-key");
        assertThat(body.getValue().getHeaders().getFirst("x-goog-api-key")).isEqualTo("secret-key");
        assertThat((Map<String, Object>) body.getValue().getBody().get("generationConfig"))
                .containsEntry("responseMimeType", "application/json");
    }

    @Test
    void withoutAKeyTheWriterIsOffAndNothingCallsOut() {
        AiService off = new AiService("", "", restTemplate);
        assertThat(off.isAvailable()).isFalse();
        assertThat(off.completeJson("prompt")).isNull();
        assertThat(off.model()).isEqualTo("gemini-flash-lite-latest");
        org.mockito.Mockito.verifyNoInteractions(restTemplate);
    }

    @Test
    void aFailedCallIsNullNotAnError() {
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenThrow(new RestClientException("timeout"));
        assertThat(aiService.completeJson("prompt")).isNull();
    }

    // ------------------------------------------------------------------ providers (Anthropic first, then Gemini)

    @Test
    void anthropicWinsWhenItsKeyIsSetElseGeminiElseOff() {
        assertThat(AiService.select("sk-ant", "", "g-key", "", restTemplate)).isInstanceOf(AnthropicProvider.class);
        assertThat(AiService.select("sk-ant", "", "", "", restTemplate).model()).isEqualTo("claude-haiku-5-5");
        assertThat(AiService.select("sk-ant", " claude-x ", "", "", restTemplate).model()).isEqualTo("claude-x");
        assertThat(AiService.select("", "", "g-key", "", restTemplate)).isInstanceOf(GeminiProvider.class);
        assertThat(AiService.select(" ", "", "", "", restTemplate)).isNull();
        assertThat(AiService.select("ANTHROPIC_API_KEY", "", "GEMINI_API_KEY", "", restTemplate)).as("example-file placeholders").isNull();
        AiService off = new AiService((AiProvider) null, null);
        assertThat(off.isAvailable()).isFalse();
        assertThat(off.isConfigured()).isFalse();
        assertThat(off.providerName()).isNull();
    }

    private void anthropicAnswers(String text, int in, int out) {
        Map<String, Object> response = Map.of(
                "content", List.of(Map.of("type", "text", "text", text)),
                "stop_reason", "end_turn",
                "usage", Map.of("input_tokens", in, "output_tokens", out));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(response);
    }

    @Test
    @SuppressWarnings("unchecked")
    void claudeIsCalledOverPlainHttpWithTheKeyAndVersionInHeaders() {
        AiService claude = new AiService(new AnthropicProvider("sk-ant-secret", null, restTemplate), null);
        anthropicAnswers("{\"titles\":[\"Rain sounds for sleep\"]}", 120, 40);
        assertThat(claude.completeJson("the prompt")).isEqualTo("{\"titles\":[\"Rain sounds for sleep\"]}");
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity<Map<String, Object>>> body = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(url.capture(), body.capture(), eq(Map.class));
        assertThat(url.getValue()).isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(body.getValue().getHeaders().getFirst("x-api-key")).isEqualTo("sk-ant-secret");
        assertThat(body.getValue().getHeaders().getFirst("anthropic-version")).isEqualTo("2023-06-01");
        Map<String, Object> b = body.getValue().getBody();
        assertThat(b).containsEntry("model", "claude-haiku-5-5").containsEntry("max_tokens", 2048)
                .containsEntry("system", AnthropicProvider.JSON_SYSTEM);
        assertThat((List<Map<String, Object>>) b.get("messages")).containsExactly(Map.of("role", "user", "content", "the prompt"));
        assertThat(b.toString()).doesNotContain("sk-ant-secret");
    }

    @Test
    void everyCallsTokensAreCountedAgainstTheBudget() {
        AiBudget budget = org.mockito.Mockito.mock(AiBudget.class);
        when(budget.allows()).thenReturn(true);
        AiService claude = new AiService(new AnthropicProvider("k", "claude-haiku-5-5", restTemplate), budget);
        anthropicAnswers("Rain sounds for sleep", 300, 25);
        assertThat(claude.generateTitle("rain")).isEqualTo("Rain sounds for sleep");
        verify(budget).record("anthropic", "claude-haiku-5-5", 300, 25);
    }

    @Test
    void geminiUsageIsCountedToo() {
        AiBudget budget = org.mockito.Mockito.mock(AiBudget.class);
        when(budget.allows()).thenReturn(true);
        AiService g = new AiService(new GeminiProvider("k", null, restTemplate), budget);
        Map<String, Object> response = Map.of(
                "candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", "{}"))))),
                "usageMetadata", Map.of("promptTokenCount", 200, "candidatesTokenCount", 50, "thoughtsTokenCount", 10));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(response);
        assertThat(g.completeJson("p")).isEqualTo("{}");
        verify(budget).record("gemini", "gemini-flash-lite-latest", 200, 60);
    }

    @Test
    void onceTheMonthsBudgetIsUsedUpNothingCallsOut() {
        AiBudget budget = org.mockito.Mockito.mock(AiBudget.class);
        when(budget.allows()).thenReturn(false);
        AiService claude = new AiService(new AnthropicProvider("k", null, restTemplate), budget);
        assertThat(claude.isAvailable()).isFalse();
        assertThat(claude.isConfigured()).isTrue();
        assertThat(claude.completeJson("p")).isNull();
        assertThat(claude.generateTitle("lofi beats")).as("falls back to the creator's words").isEqualTo("Lofi beats");
        org.mockito.Mockito.verifyNoInteractions(restTemplate);
    }

    @Test
    void rateLimitsNoCreditAndTimeoutsAreNullNotErrors() {
        AiService claude = new AiService(new AnthropicProvider("k", null, restTemplate), null);
        for (int code : new int[] {400, 402, 429, 500, 529}) {
            org.mockito.Mockito.reset(restTemplate);
            org.springframework.web.client.HttpStatusCodeException e = code >= 500
                    ? org.springframework.web.client.HttpServerErrorException.create(org.springframework.http.HttpStatusCode.valueOf(code),
                            "x", new org.springframework.http.HttpHeaders(), new byte[0], null)
                    : org.springframework.web.client.HttpClientErrorException.create(org.springframework.http.HttpStatusCode.valueOf(code),
                            "x", new org.springframework.http.HttpHeaders(),
                            "{\"error\":{\"message\":\"Your credit balance is too low\"}}".getBytes(), null);
            when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenThrow(e);
            assertThat(claude.completeJson("p")).as("HTTP " + code).isNull();
        }
        org.mockito.Mockito.reset(restTemplate);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"));
        assertThat(claude.completeJson("p")).isNull();
    }

    @Test
    void aRefusalIsBilledButGivesNoText() {
        AiBudget budget = org.mockito.Mockito.mock(AiBudget.class);
        when(budget.allows()).thenReturn(true);
        AiService claude = new AiService(new AnthropicProvider("k", null, restTemplate), budget);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(Map.of(
                "content", List.of(), "stop_reason", "refusal", "usage", Map.of("input_tokens", 90, "output_tokens", 0)));
        assertThat(claude.completeJson("p")).isNull();
        verify(budget).record("anthropic", "claude-haiku-5-5", 90, 0);
    }
}
