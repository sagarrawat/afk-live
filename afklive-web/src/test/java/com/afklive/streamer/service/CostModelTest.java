package com.afklive.streamer.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** FO-3: the cost arithmetic, including the edge cases (no data, zero hours, unknown inputs). */
class CostModelTest {

    static final double GIB = CostModel.GIB;

    private static CostModel.Inputs inputs(Double serverCost, double includedGb, Double price) {
        return new CostModel.Inputs(serverCost, "USD", includedGb, false, price, false);
    }

    private static CostModel.Slice hours(String plan, int height, int fps, double hours, double gb) {
        return new CostModel.Slice(plan, height, fps, hours * 3600, gb * GIB);
    }

    private static final List<CostModel.Plan> PLANS = List.of(
            new CostModel.Plan("FREE", "Free", 0L, 100, 10),
            new CostModel.Plan("PRO", "Pro", 2360L, 4, 0)); // $23.60 with GST = $20.00 before it

    @Test
    void noRunsAtAllGivesZeroTrafficAndNoCostPerHour() {
        CostModel.Report r = CostModel.compute(inputs(48.0, 5000, 0.01), "2026-10", 0.5, List.of(), PLANS);
        CostModel.Totals t = r.totals();
        assertThat(t.hours()).isZero();
        assertThat(t.gb()).isZero();
        assertThat(t.usedPercent()).isZero();
        assertThat(t.serverPerHour()).isNull(); // nothing to divide by
        assertThat(t.trafficCostMonth()).isZero();
        assertThat(t.trafficPerHour()).isNull();
        assertThat(t.marginalTrafficPerHour()).isNull();
        assertThat(t.totalPerHour()).isNull();
        assertThat(t.projectedCostMonth()).isEqualTo(48.0); // the bill is still paid
        assertThat(r.byPlan()).isEmpty();
        assertThat(r.byQuality()).isEmpty();
        // Margins: the full price, nothing to subtract; full use unknown.
        CostModel.Margin pro = r.margins().get(1);
        assertThat(pro.netPrice()).isCloseTo(20.0, within(1e-9));
        assertThat(pro.costPerAccount()).isNull();
        assertThat(pro.marginPerAccount()).isNull();
        assertThat(pro.fullUseHours()).isEqualTo(720);
    }

    @Test
    void runsWithBytesButZeroSecondsDontDivideByZero() {
        CostModel.Report r = CostModel.compute(inputs(48.0, 0, 0.01), "2026-10", 1, List.of(hours("FREE", 720, 30, 0, 2)), PLANS);
        assertThat(r.totals().hours()).isZero();
        assertThat(r.totals().gb()).isCloseTo(2, within(1e-9));
        assertThat(r.totals().serverPerHour()).isNull();
        assertThat(r.totals().trafficCostMonth()).isCloseTo(0.02, within(1e-9));
        assertThat(r.totals().trafficPerHour()).isNull();
        assertThat(r.totals().usedPercent()).isNull(); // nothing included
        CostModel.Group g = r.byQuality().getFirst();
        assertThat(g.gbPerHour()).isNull();
        assertThat(g.totalPerHour()).isNull();
    }

    @Test
    void unknownBillAndPriceGiveNullsNotZeros() {
        CostModel.Report r = CostModel.compute(inputs(null, 5000, null), "2026-10", 1, List.of(hours("PRO", 720, 30, 10, 20)), PLANS);
        assertThat(r.totals().serverPerHour()).isNull();
        assertThat(r.totals().trafficCostMonth()).isNull();
        assertThat(r.totals().totalPerHour()).isNull();
        assertThat(r.totals().projectedCostMonth()).isNull();
        assertThat(r.totals().gb()).isCloseTo(20, within(1e-9));
        assertThat(r.totals().usedPercent()).isCloseTo(0.4, within(1e-9));
    }

    @Test
    void serverCostIsTheBillOverProjectedHoursAndTrafficIsOnlyTheOverage() {
        // Half the month gone: 300 hours and 3,000 GB so far, so 600 hours and 6,000 GB by month end.
        List<CostModel.Slice> runs = List.of(
                hours("PRO", 1080, 60, 100, 1500),
                hours("FREE", 720, 30, 200, 1500));
        CostModel.Report r = CostModel.compute(inputs(60.0, 5000, 0.01), "2026-10", 0.5, runs, PLANS);
        CostModel.Totals t = r.totals();
        assertThat(t.hours()).isCloseTo(300, within(1e-9));
        assertThat(t.projectedHours()).isCloseTo(600, within(1e-9));
        assertThat(t.projectedGb()).isCloseTo(6000, within(1e-9));
        assertThat(t.usedPercent()).isCloseTo(60, within(1e-9));
        assertThat(t.projectedPercent()).isCloseTo(120, within(1e-9));
        assertThat(t.serverPerHour()).isCloseTo(0.10, within(1e-9)); // $60 / 600 h
        assertThat(t.trafficCostMonth()).isCloseTo(10.0, within(1e-9)); // (6000 − 5000) × $0.01
        assertThat(t.trafficPerHour()).isCloseTo(10.0 / 600, within(1e-9));
        assertThat(t.marginalTrafficPerHour()).isCloseTo(0.10, within(1e-9)); // 10 GB/h × $0.01
        assertThat(t.totalPerHour()).isCloseTo(0.10 + 10.0 / 600, within(1e-9));
        assertThat(t.projectedCostMonth()).isCloseTo(70.0, within(1e-9));

        // By quality: 1080p60 sends 15 GB/h, 720p30 7.5 GB/h; the overage is shared by GB.
        assertThat(r.byQuality()).extracting(CostModel.Group::key).containsExactly("720p 30 fps", "1080p 60 fps");
        CostModel.Group hd = r.byQuality().get(1);
        assertThat(hd.gbPerHour()).isCloseTo(15, within(1e-9));
        assertThat(hd.serverPerHour()).isCloseTo(0.10, within(1e-9));
        // $10 over 6,000 GB = $1/600 per GB; × 15 GB/h.
        assertThat(hd.trafficPerHour()).isCloseTo(15.0 / 600, within(1e-9));
        assertThat(hd.marginalTrafficPerHour()).isCloseTo(0.15, within(1e-9));
        // The groups' traffic adds back up to the month's.
        double sum = r.byQuality().stream().mapToDouble(g -> g.trafficPerHour() * g.hours() / 0.5).sum();
        assertThat(sum).isCloseTo(10.0, within(1e-9));

        // Plans in the order they're sold.
        assertThat(r.byPlan()).extracting(CostModel.Group::key).containsExactly("FREE", "PRO");
    }

    @Test
    void underTheIncludedTransferTrafficCostsNothingButTheMarginalRateIsShown() {
        CostModel.Report r = CostModel.compute(inputs(48.0, 5000, 0.01), "2026-10", 1, List.of(hours("PRO", 720, 30, 100, 400)), PLANS);
        assertThat(r.totals().trafficCostMonth()).isZero();
        assertThat(r.totals().trafficPerHour()).isZero();
        assertThat(r.totals().marginalTrafficPerHour()).isCloseTo(0.04, within(1e-9));
        assertThat(r.byPlan().getFirst().trafficPerHour()).isZero();
    }

    @Test
    void marginsComparePriceBeforeGstWithAverageAndFullUseCost() {
        // A finished month: Pro streamed 400 hours over 4 accounts; nothing beyond the included transfer.
        CostModel.Report r = CostModel.compute(inputs(40.0, 5000, 0.01), "2026-09", 1,
                List.of(hours("PRO", 720, 30, 400, 100)), PLANS);
        CostModel.Margin pro = r.margins().stream().filter(m -> m.plan().equals("PRO")).findFirst().orElseThrow();
        assertThat(pro.accounts()).isEqualTo(4);
        assertThat(pro.hoursPerAccount()).isCloseTo(100, within(1e-9));
        assertThat(pro.costPerAccount()).isCloseTo(10.0, within(1e-9)); // 100 h × $0.10
        assertThat(pro.marginPerAccount()).isCloseTo(10.0, within(1e-9)); // $20 − $10
        assertThat(pro.marginPercent()).isEqualTo(50);
        assertThat(pro.fullUseCost()).isCloseTo(72.0, within(1e-9)); // 720 h × $0.10
        assertThat(pro.fullUseMargin()).isCloseTo(-52.0, within(1e-9));
        // Free didn't stream: it's priced at the platform rate, at its 10 included hours.
        CostModel.Margin free = r.margins().getFirst();
        assertThat(free.costPerAccount()).isZero();
        assertThat(free.marginPercent()).isNull(); // no price to take a share of
        assertThat(free.fullUseHours()).isEqualTo(10);
        assertThat(free.fullUseMargin()).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void theStartOfAMonthDoesntProjectToInfinity() {
        CostModel.Report r = CostModel.compute(inputs(48.0, 5000, 0.01), "2026-10", 0, List.of(hours("PRO", 720, 30, 0.5, 1)), PLANS);
        assertThat(r.monthElapsed()).isPositive();
        assertThat(r.totals().projectedHours()).isFinite();
    }

    @Test
    void qualitiesRoundToStandardSizes() {
        assertThat(CostModel.quality(720, 30)).isEqualTo("720p 30 fps");
        assertThat(CostModel.quality(480, 24)).isEqualTo("720p 30 fps");
        assertThat(CostModel.quality(1080, 60)).isEqualTo("1080p 60 fps");
        assertThat(CostModel.quality(1080, 50)).isEqualTo("1080p 60 fps");
        assertThat(CostModel.quality(0, 0)).isEqualTo("720p 30 fps");
    }

    @Test
    void emptySettingsUseTheDocumentedDigitalOceanDefaults() {
        CostModel.Inputs inr = CostMeter.inputs("", "INR", "", "");
        assertThat(inr.monthlyServerCost()).isNull();
        assertThat(inr.includedGb()).isEqualTo(5000);
        assertThat(inr.includedDefault()).isTrue();
        assertThat(inr.pricePerGb()).isCloseTo(0.85, within(1e-9));
        assertThat(inr.priceDefault()).isTrue();

        CostModel.Inputs usd = CostMeter.inputs("48", "usd", null, null);
        assertThat(usd.currency()).isEqualTo("USD");
        assertThat(usd.pricePerGb()).isEqualTo(0.01);

        CostModel.Inputs eur = CostMeter.inputs("40", "EUR", "", "");
        assertThat(eur.pricePerGb()).isNull(); // no default for other currencies

        CostModel.Inputs set = CostMeter.inputs("4000", "INR", "10000", "0.9");
        assertThat(set.includedGb()).isEqualTo(10000);
        assertThat(set.includedDefault()).isFalse();
        assertThat(set.pricePerGb()).isEqualTo(0.9);
        assertThat(set.priceDefault()).isFalse();

        CostModel.Inputs zero = CostMeter.inputs("4000", "INR", "0", "abc");
        assertThat(zero.includedGb()).isZero(); // nothing included
        assertThat(zero.includedDefault()).isFalse();
        assertThat(zero.pricePerGb()).isCloseTo(0.85, within(1e-9)); // unreadable: the default
    }

    @Test
    void aRunsQualityComesFromItsSpecThenTheStream() {
        assertThat(CostMeter.quality("{\"maxHeight\":1080,\"studio\":{\"height\":1080,\"fps\":60}}", 720, 30)).containsExactly(1080, 60);
        assertThat(CostMeter.quality("{\"maxHeight\":720,\"studio\":null}", null, null)).containsExactly(720, 30);
        assertThat(CostMeter.quality(null, 1080, 60)).containsExactly(1080, 60);
        assertThat(CostMeter.quality("not json", 1080, 30)).containsExactly(1080, 30);
    }
}
