package com.afklive.streamer.service;

import java.util.ArrayList;
import java.util.List;

/**
 * FO-5 fair use, as plain arithmetic (no Spring, no database, so every case is unit-tested): where an account is
 * against its monthly streaming data allowance, and what each plan can cost us at worst and typically.
 * <p>
 * The steps, never stopping a stream: at {@link #WARN}% a heads-up; at {@link #CAP}% every stream keeps only its
 * first destination for the rest of the month (the others pause, without the first reconnecting); at
 * {@link #REDUCE}% new runs and restarts start at 720p 30 fps. Running streams keep their quality: changing it
 * means a new encoder and a reconnect, which fair use must never cause.
 */
public final class FairUseModel {

    public static final int WARN = 80;
    public static final int CAP = 100;
    public static final int REDUCE = 110;
    /** The steps that notify, in order. */
    public static final int[] STEPS = {WARN, CAP, REDUCE};
    /** Gate A ("worst-case plan cost ≤ 60% of net price"): the share of net price a plan may cost at worst. */
    public static final double MAX_COST_SHARE = 0.60;
    /** The audio every run sends next to its video (AAC 128 kbps). */
    public static final int AUDIO_KBPS = 128;
    /** Container and protocol overhead on top of the audio and video bitrates (MPEG-TS / FLV / TCP), an estimate. */
    public static final double OVERHEAD = 1.04;
    public static final double FULL_MONTH_HOURS = CostModel.FULL_MONTH_HOURS;
    /** The quality new runs start at past {@link #REDUCE}%. */
    public static final int REDUCED_HEIGHT = 720;
    public static final int REDUCED_FPS = 30;

    private FairUseModel() {
    }

    /** Where an account is this month. */
    public enum Step {
        /** Under 80%, or no allowance. */
        NONE,
        /** 80% or more: a heads-up only. */
        WARNED,
        /** 100% or more: streams keep their first destination only. */
        EXTRAS_PAUSED,
        /** 110% or more: also, new runs start at 720p 30 fps. */
        REDUCED
    }

    /** {@code allowanceGb} null: no allowance, never limited. */
    public static Step step(double usedGb, Double allowanceGb) {
        if (allowanceGb == null) {
            return Step.NONE;
        }
        double pct = percent(usedGb, allowanceGb);
        return pct >= REDUCE ? Step.REDUCED : pct >= CAP ? Step.EXTRAS_PAUSED : pct >= WARN ? Step.WARNED : Step.NONE;
    }

    /** Used as a percentage of the allowance; an allowance of 0 counts as used up. */
    public static double percent(double usedGb, double allowanceGb) {
        return allowanceGb > 0 ? Math.max(0, usedGb) / allowanceGb * 100 : Double.POSITIVE_INFINITY;
    }

    /** The notifying steps {@code usedGb} has reached (80, 100, 110), lowest first. */
    public static List<Integer> reached(double usedGb, Double allowanceGb) {
        List<Integer> out = new ArrayList<>();
        if (allowanceGb == null) {
            return out;
        }
        double pct = percent(usedGb, allowanceGb);
        for (int s : STEPS) {
            if (pct >= s) {
                out.add(s);
            }
        }
        return out;
    }

    /** GB (GiB) one destination sends in an hour at this video bitrate, with audio and overhead. */
    public static double gbPerHour(int videoKbps) {
        return (videoKbps + AUDIO_KBPS) * 1000.0 / 8 * 3600 * OVERHEAD / CostModel.GIB;
    }

    // ------------------------------------------------------------------ worst case and typical case per plan

    /**
     * A plan as sold. {@code videoKbps}: its bitrate at its top quality (FO-4 setting or the built-in ladder);
     * {@code monthlyHours} 0 = unlimited; {@code trafficGb} null = no allowance; {@code priceMinor} GST-inclusive monthly
     * price in the cost currency's minor unit, null when not sold in it.
     */
    public record PlanLimits(String plan, String name, int streams, int destinations, int height, int fps, int videoKbps,
                             int monthlyHours, Integer trafficGb, Long priceMinor) {
    }

    /**
     * The settings behind the numbers. {@code pricePerGb} and {@code serverPerStreamHour} in the cost currency, null
     * when unknown; {@code gatewayFeePercent} is taken off the price before GST.
     */
    public record Inputs(String currency, Double pricePerGb, Double serverPerStreamHour, String serverSource,
                         double gatewayFeePercent) {
    }

    /** What an account on the plan streamed this month on average (projected to month end), from real runs. */
    public record Typical(long accounts, double streamHours, double gb) {
    }

    /**
     * One plan at full use and typically. GB and stream-hours a month; money in the cost currency (null when an input
     * is unknown). {@code fullUseGb}: every stream 24/7 (or the plan's hours) to every destination at the top quality;
     * {@code worstGb}: the same with fair use applied (past the allowance, one destination per stream). Running
     * streams keep their quality past 110%, so the worst case doesn't count the 720p step. {@code costShare}: worst
     * cost over net price; {@code meetsRule}: at most {@link #MAX_COST_SHARE}; null for a free plan.
     */
    public record PlanCase(String plan, String name, int streams, int destinations, String quality, int videoKbps,
                           double streamHours, Integer trafficGb, double fullUseGb, double worstGb, Double netPrice,
                           Double trafficCost, Double serverCost, Double worstCost, Double worstMargin, Double costShare,
                           Boolean meetsRule, Long typicalAccounts, Double typicalGb, Double typicalHours, Double typicalCost,
                           Double typicalMargin, Double typicalShare) {
    }

    /** The plan's price before GST and after the payment gateway's fee; null when not sold in this currency. */
    public static Double netPrice(Long priceMinor, double gatewayFeePercent) {
        if (priceMinor == null) {
            return null;
        }
        return priceMinor / 100.0 / CostModel.GST * (1 - Math.max(0, Math.min(100, gatewayFeePercent)) / 100);
    }

    /** Stream-hours a month at full use: every stream all month, or the plan's monthly hours when it has a limit. */
    public static double fullUseHours(int streams, int monthlyHours) {
        double all = FULL_MONTH_HOURS * Math.max(1, streams);
        return monthlyHours > 0 ? Math.min(monthlyHours, all) : all;
    }

    /**
     * GB a month at full use with fair use applied: all destinations until the allowance is used, then one per
     * stream for the rest of the stream-hours. Without an allowance, or under it, the full figure.
     */
    public static double worstGb(double streamHours, int destinations, double gbPerDestHour, Integer allowanceGb) {
        int d = Math.max(1, destinations);
        double full = streamHours * d * gbPerDestHour;
        if (allowanceGb == null || full <= allowanceGb || d == 1 || gbPerDestHour <= 0) {
            return full;
        }
        double hoursToCap = Math.max(0, allowanceGb) / (d * gbPerDestHour);
        return Math.max(0, allowanceGb) + Math.max(0, streamHours - hoursToCap) * gbPerDestHour;
    }

    public static PlanCase planCase(PlanLimits p, Inputs in, Typical typical) {
        double perHour = gbPerHour(p.videoKbps());
        double hours = fullUseHours(p.streams(), p.monthlyHours());
        double full = hours * Math.max(1, p.destinations()) * perHour;
        double worst = worstGb(hours, p.destinations(), perHour, p.trafficGb());
        Double net = netPrice(p.priceMinor(), in.gatewayFeePercent());
        Double traffic = in.pricePerGb() == null ? null : worst * in.pricePerGb();
        Double server = in.serverPerStreamHour() == null ? null : hours * in.serverPerStreamHour();
        Double cost = sum(traffic, server);
        Double margin = net == null || cost == null ? null : net - cost;
        Double share = net == null || cost == null || net <= 0 ? null : cost / net;
        Boolean meets = share == null ? null : share <= MAX_COST_SHARE + 1e-9;

        Long tAccounts = null;
        Double tGb = null;
        Double tHours = null;
        Double tCost = null;
        Double tMargin = null;
        Double tShare = null;
        if (typical != null && typical.accounts() > 0) {
            tAccounts = typical.accounts();
            tGb = typical.gb();
            tHours = typical.streamHours();
            tCost = sum(in.pricePerGb() == null ? null : typical.gb() * in.pricePerGb(),
                    in.serverPerStreamHour() == null ? null : typical.streamHours() * in.serverPerStreamHour());
            tMargin = net == null || tCost == null ? null : net - tCost;
            tShare = net == null || tCost == null || net <= 0 ? null : tCost / net;
        }
        return new PlanCase(p.plan(), p.name(), p.streams(), p.destinations(), CostModel.quality(p.height(), p.fps()),
                p.videoKbps(), hours, p.trafficGb(), full, worst, net, traffic, server, cost, margin, share, meets,
                tAccounts, tGb, tHours, tCost, tMargin, tShare);
    }

    /**
     * The largest allowance (GB) that keeps the plan's worst case within {@link #MAX_COST_SHARE} of its net price at
     * these inputs; null when unknown or the server cost alone breaks the rule. A suggestion for admins, never applied.
     */
    public static Integer allowanceFor(PlanLimits p, Inputs in) {
        Double net = netPrice(p.priceMinor(), in.gatewayFeePercent());
        if (net == null || net <= 0 || in.pricePerGb() == null || in.pricePerGb() <= 0) {
            return null;
        }
        double hours = fullUseHours(p.streams(), p.monthlyHours());
        double server = in.serverPerStreamHour() == null ? 0 : hours * in.serverPerStreamHour();
        double budgetGb = (net * MAX_COST_SHARE - server) / in.pricePerGb();
        if (budgetGb <= 0) {
            return null;
        }
        // After the allowance A, one destination per stream goes on: worst = A·(1 − 1/D) + hours·r. Solve for A.
        double r = gbPerHour(p.videoKbps());
        int d = Math.max(1, p.destinations());
        double floor = hours * r; // one destination all month: fair use can't go below it
        if (budgetGb < floor) {
            return null;
        }
        if (d == 1) {
            return (int) Math.floor(budgetGb);
        }
        double a = (budgetGb - floor) / (1 - 1.0 / d);
        return (int) Math.floor(Math.min(a, hours * d * r));
    }

    private static Double sum(Double a, Double b) {
        return a == null && b == null ? null : (a == null ? 0 : a) + (b == null ? 0 : b);
    }
}
