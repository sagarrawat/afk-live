package com.afklive.streamer.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FO-3: what a stream-hour costs us, as plain arithmetic over numbers the app already records (no Spring, no
 * database, so every case is unit-tested). {@link CostMeter} loads the inputs.
 * <pre>
 * server cost per stream-hour  = monthly server bill / stream-hours this month (projected to month end)
 * traffic cost for the month   = max(0, GB sent − included GB) × price per GB (projected to month end)
 * traffic cost per stream-hour = traffic cost for the month / stream-hours (projected)
 * </pre>
 * The server bill is shared equally per stream-hour: CPU isn't measured per stream, only per server. Traffic
 * is measured per run (bytes each destination sent), so groups (plan, quality) differ by the GB they send:
 * a group's traffic cost is its share of the month's GB. The "marginal" traffic cost is what one more hour
 * costs once the included transfer is used up (GB per hour × price), the honest number for pricing a plan.
 */
public final class CostModel {

    /** Gigabytes are GiB, as DigitalOcean measures and bills transfer. */
    public static final double GIB = 1024d * 1024 * 1024;
    /**
     * Default included transfer: one DigitalOcean 4 vCPU / 8 GiB droplet (Basic or CPU-optimized) includes
     * 5,000 GiB a month; DigitalOcean pools it across the team's droplets.
     */
    public static final double DEFAULT_INCLUDED_GB = 5000;
    /** DigitalOcean's outbound transfer beyond the included amount: $0.01 per GiB. */
    public static final double DEFAULT_PRICE_USD = 0.01;
    /** Only to express the default price when costs are kept in rupees; set APP_TRANSFER_PRICE_PER_GB to override. */
    public static final double DEFAULT_INR_PER_USD = 85;
    /** Plan prices are GST-inclusive; margins compare cost with the price before 18% GST. */
    public static final double GST = 1.18;
    /** 24/7 for 30 days: what an unlimited plan means at full use. */
    public static final double FULL_MONTH_HOURS = 720;

    private CostModel() {
    }

    /**
     * The settings behind every number, shown next to them. {@code includedDefault}/{@code priceDefault}: the
     * value is the built-in DigitalOcean default, not set by the operator. {@code pricePerGb} is in
     * {@code currency}; null when there's no default for that currency and none is set.
     */
    public record Inputs(Double monthlyServerCost, String currency, double includedGb, boolean includedDefault,
                         Double pricePerGb, boolean priceDefault) {
    }

    /** One run's part inside the month: its plan and quality, seconds streamed and bytes sent in the month. */
    public record Slice(String plan, int height, int fps, double seconds, double bytes) {
    }

    /** A plan as priced: monthly price in the cost currency's minor unit (GST-inclusive; null when not sold in it). */
    public record Plan(String plan, String name, Long priceMinor, long accounts, int monthlyHours) {
    }

    /** Cost per stream-hour of a group of runs (one plan or one quality), from this month so far. */
    public record Group(String key, double hours, double gb, Double gbPerHour, Double serverPerHour, Double trafficPerHour,
                        Double marginalTrafficPerHour, Double totalPerHour) {
    }

    /**
     * A plan's price against its cost. Per account: the plan's projected hours this month spread over its
     * accounts. Full use: every included hour (24/7 when unlimited) at the plan's cost per hour.
     */
    public record Margin(String plan, String name, long accounts, Double netPrice, double hoursPerAccount, Double costPerAccount,
                         Double marginPerAccount, Integer marginPercent, double fullUseHours, Double fullUseCost, Double fullUseMargin) {
    }

    /**
     * The month so far and projected to its end. {@code usedPercent}: GB sent so far as a share of the included
     * transfer (null when none is included). Money fields are null when their input is unknown.
     */
    public record Totals(double hours, double gb, double projectedHours, double projectedGb, Double usedPercent,
                         Double projectedPercent, Double serverPerHour, Double trafficCostMonth, Double trafficPerHour,
                         Double marginalTrafficPerHour, Double totalPerHour, Double projectedCostMonth) {
    }

    public record Report(Inputs inputs, String month, double monthElapsed, Totals totals, List<Group> byPlan,
                         List<Group> byQuality, List<Margin> margins) {
    }

    /** "720p 30 fps": heights round up to the nearest standard size, more than 30 fps counts as 60. */
    public static String quality(int height, int fps) {
        int h = height <= 0 ? 720 : height <= 720 ? 720 : height <= 1080 ? 1080 : height <= 1440 ? 1440 : 2160;
        return h + "p " + (fps > 30 ? 60 : 30) + " fps";
    }

    /**
     * @param fraction how much of the month has passed, 0..1 (1 for a finished month); projections divide by it
     */
    public static Report compute(Inputs in, String month, double fraction, List<Slice> slices, List<Plan> plans) {
        double f = Double.isFinite(fraction) ? Math.max(1.0 / (31 * 24), Math.min(1, fraction)) : 1;
        double seconds = 0;
        double bytes = 0;
        Map<String, double[]> byPlan = new LinkedHashMap<>();
        Map<String, double[]> byQuality = new LinkedHashMap<>();
        for (Slice s : slices) {
            double sec = Math.max(0, s.seconds());
            double b = Math.max(0, s.bytes());
            seconds += sec;
            bytes += b;
            add(byPlan, s.plan() == null ? "FREE" : s.plan(), sec, b);
            add(byQuality, quality(s.height(), s.fps()), sec, b);
        }
        double hours = seconds / 3600;
        double gb = bytes / GIB;
        double projectedHours = hours / f;
        double projectedGb = gb / f;
        Double usedPercent = in.includedGb() > 0 ? gb / in.includedGb() * 100 : null;
        Double projectedPercent = in.includedGb() > 0 ? projectedGb / in.includedGb() * 100 : null;
        Double serverPerHour = in.monthlyServerCost() == null || projectedHours <= 0 ? null : in.monthlyServerCost() / projectedHours;
        Double trafficCostMonth = in.pricePerGb() == null ? null : Math.max(0, projectedGb - Math.max(0, in.includedGb())) * in.pricePerGb();
        Double trafficPerHour = trafficCostMonth == null || projectedHours <= 0 ? null : trafficCostMonth / projectedHours;
        Double gbPerHour = hours > 0 ? gb / hours : null;
        Double marginal = in.pricePerGb() == null || gbPerHour == null ? null : gbPerHour * in.pricePerGb();
        Double totalPerHour = sum(serverPerHour, trafficPerHour);
        Double projectedCost = sum(in.monthlyServerCost(), trafficCostMonth);
        Totals totals = new Totals(hours, gb, projectedHours, projectedGb, usedPercent, projectedPercent, serverPerHour,
                trafficCostMonth, trafficPerHour, marginal, totalPerHour, projectedCost);

        // A group pays the month's traffic cost in proportion to the GB it sent.
        Double costPerGb = trafficCostMonth == null ? null : projectedGb > 0 ? trafficCostMonth / projectedGb : 0.0;
        List<Group> plansOut = groups(byPlan, serverPerHour, costPerGb, in.pricePerGb());
        List<Group> qualities = groups(byQuality, serverPerHour, costPerGb, in.pricePerGb());
        qualities.sort(Comparator.comparingInt((Group g) -> Integer.parseInt(g.key().substring(0, g.key().indexOf('p'))))
                .thenComparing(Group::key));
        // Plans in the order they're sold; groups for plans no longer configured go last.
        List<String> order = plans.stream().map(Plan::plan).toList();
        plansOut.sort(Comparator.comparingInt((Group g) -> order.contains(g.key()) ? order.indexOf(g.key()) : order.size())
                .thenComparing(Group::key));

        List<Margin> margins = new ArrayList<>();
        for (Plan p : plans) {
            Group g = plansOut.stream().filter(x -> x.key().equals(p.plan())).findFirst().orElse(null);
            double planHours = g == null ? 0 : g.hours() / f;
            double perAccount = p.accounts() > 0 ? planHours / p.accounts() : 0;
            // The plan's own rate when it streamed this month, otherwise the platform's.
            Double rate = g != null && g.totalPerHour() != null ? g.totalPerHour() : totalPerHour;
            Double net = p.priceMinor() == null ? null : p.priceMinor() / 100.0 / GST;
            Double cost = rate == null ? null : perAccount * rate;
            Double margin = net == null || cost == null ? null : net - cost;
            Integer pct = margin == null || net == null || net <= 0 ? null : (int) Math.round(margin / net * 100);
            double fullHours = p.monthlyHours() > 0 ? p.monthlyHours() : FULL_MONTH_HOURS;
            Double fullCost = rate == null ? null : fullHours * rate;
            Double fullMargin = net == null || fullCost == null ? null : net - fullCost;
            margins.add(new Margin(p.plan(), p.name(), p.accounts(), net, perAccount, cost, margin, pct, fullHours, fullCost, fullMargin));
        }
        return new Report(in, month, f, totals, plansOut, qualities, margins);
    }

    private static void add(Map<String, double[]> m, String key, double sec, double b) {
        double[] v = m.computeIfAbsent(key, k -> new double[2]);
        v[0] += sec;
        v[1] += b;
    }

    private static List<Group> groups(Map<String, double[]> m, Double serverPerHour, Double costPerGb, Double pricePerGb) {
        List<Group> out = new ArrayList<>();
        m.forEach((key, v) -> {
            double hours = v[0] / 3600;
            double gb = v[1] / GIB;
            Double gbPerHour = hours > 0 ? gb / hours : null;
            Double server = hours > 0 ? serverPerHour : null;
            Double traffic = costPerGb == null || gbPerHour == null ? null : gbPerHour * costPerGb;
            Double marginal = pricePerGb == null || gbPerHour == null ? null : gbPerHour * pricePerGb;
            out.add(new Group(key, hours, gb, gbPerHour, server, traffic, marginal, sum(server, traffic)));
        });
        return out;
    }

    private static Double sum(Double a, Double b) {
        return a == null && b == null ? null : (a == null ? 0 : a) + (b == null ? 0 : b);
    }

    /**
     * The price per GB in {@code currency}: the operator's setting, or DigitalOcean's $0.01 (₹0.85 at
     * {@link #DEFAULT_INR_PER_USD}) when it's empty; null for another currency without a setting.
     */
    static Double defaultPrice(String currency) {
        String c = currency == null ? "" : currency.toUpperCase(Locale.ROOT);
        return switch (c) {
            case "USD" -> DEFAULT_PRICE_USD;
            case "INR" -> DEFAULT_PRICE_USD * DEFAULT_INR_PER_USD;
            default -> null;
        };
    }
}
