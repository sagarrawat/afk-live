package com.afklive.streamer.service;

import com.afklive.streamer.service.TitleWriter.Draft;
import com.afklive.streamer.service.TitleWriterIndicTest.Case;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * AI-1 / GR-10: the 30 fixture streams ({@code ai-eval/inputs.json}) against the real model, for the owner to read.
 * Not part of any normal build ({@code @Tag("ai-eval")}, Maven profile {@code ai-eval}); run it with
 * {@code afklive-scripts/ai-eval.sh}. Uses {@code ANTHROPIC_API_KEY} (and {@code ANTHROPIC_MODEL}), else
 * {@code GEMINI_API_KEY}; skipped without either. Writes {@code afklive-web/target/ai-eval/report.md} with every
 * draft, the checks it failed and what the run cost. 30 calls with Claude Haiku cost about a cent.
 */
@Tag("ai-eval")
class TitleWriterLiveEval {

    @Test
    void writeTheReport() throws Exception {
        AiProvider provider = AiService.select(System.getenv("ANTHROPIC_API_KEY"), System.getenv("ANTHROPIC_MODEL"),
                System.getenv("GEMINI_API_KEY"), System.getenv("GEMINI_MODEL"), AiService.timed());
        Assumptions.assumeTrue(provider != null, "Set ANTHROPIC_API_KEY (or GEMINI_API_KEY) to run the AI eval");
        double inPrice = AiBudget.number("APP_AI_PRICE_INPUT_PER_MTOK", System.getenv("APP_AI_PRICE_INPUT_PER_MTOK"),
                AiBudget.DEFAULT_INPUT_PER_MILLION);
        double outPrice = AiBudget.number("APP_AI_PRICE_OUTPUT_PER_MTOK", System.getenv("APP_AI_PRICE_OUTPUT_PER_MTOK"),
                AiBudget.DEFAULT_OUTPUT_PER_MILLION);

        List<Case> cases = TitleWriterIndicTest.cases();
        StringBuilder r = new StringBuilder();
        r.append("# Title writer eval: ").append(provider.name()).append(", ").append(provider.model()).append("\n\n");
        long inTok = 0;
        long outTok = 0;
        int passed = 0;
        int failed = 0;
        StringBuilder body = new StringBuilder();
        for (Case c : cases) {
            long t0 = System.nanoTime();
            AiProvider.Reply reply = provider.complete(TitleWriter.prompt(c.input()), true);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            body.append("## ").append(c.id()).append(" (").append(c.input().language().english).append(", ").append(c.niche())
                    .append(c.input().phone() ? ", live from a phone" : ", 24/7 replay").append(")\n\n");
            body.append("Name: ").append(c.input().streamName()).append("  \nBrief: ").append(c.input().brief()).append("\n\n");
            if (reply == null) {
                body.append("**No answer** (error or timeout; see the log). Shown: the fallback.\n\n");
                failed++;
                continue;
            }
            inTok += reply.inputTokens();
            outTok += reply.outputTokens();
            Draft d = TitleWriter.parse(reply.text(), c.input());
            String problem = null;
            if (d == null) {
                problem = "the answer couldn't be used (the creator would get the fallback)";
            } else {
                try {
                    TitleWriterIndicTest.assertDraft(c, d);
                } catch (AssertionError e) {
                    problem = e.getMessage().strip().lines().findFirst().orElse("check failed");
                }
            }
            if (problem == null) {
                passed++;
            } else {
                failed++;
            }
            body.append(problem == null ? "Checks: ok" : "Checks: **" + problem + "**").append(String.format(Locale.ROOT,
                    " · %d ms · %d in / %d out tokens\n\n", ms, reply.inputTokens(), reply.outputTokens()));
            if (d != null) {
                body.append("Titles:\n");
                d.titles().forEach(t -> body.append("- ").append(t).append('\n'));
                body.append("\nDescription:\n\n> ").append(d.description().replace("\n", "\n> ")).append("\n\n");
                body.append("Tags: ").append(String.join(", ", d.tags())).append("\n\n");
            }
            body.append("<details><summary>Raw answer</summary>\n\n```\n").append(reply.text()).append("\n```\n</details>\n\n");
        }
        double cost = inTok * inPrice / 1e6 + outTok * outPrice / 1e6;
        r.append(String.format(Locale.ROOT, "%d of %d passed the checks. %d input + %d output tokens, about $%.4f "
                + "(at $%.2f / $%.2f per million).\n\n", passed, cases.size(), inTok, outTok, cost, inPrice, outPrice));
        r.append("Read each draft as a creator would: is it in the right language and script, honest (no \"live\" on a replay), ")
                .append("and something people would search for?\n\n");
        r.append(body);
        Path out = Path.of("target", "ai-eval", "report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, r.toString(), StandardCharsets.UTF_8);
        System.out.println("AI eval report: " + out.toAbsolutePath() + " (" + passed + "/" + cases.size() + " passed)");
    }
}
