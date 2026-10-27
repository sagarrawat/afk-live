package com.afklive.streamer.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** FO-5 fair use arithmetic: the steps, GB per hour, and each plan's worst and typical case against Gate A's 60%. */
class FairUseModelTest {

    @Test
    void stepsAreEightyHundredAndHundredTenPercentOfTheAllowance() {
        assertThat(FairUseModel.step(10_000, null)).isEqualTo(FairUseModel.Step.NONE); // no allowance: never limited
        assertThat(FairUseModel.step(79.9, 100.0)).isEqualTo(FairUseModel.Step.NONE);
        assertThat(FairUseModel.step(80, 100.0)).isEqualTo(FairUseModel.Step.WARNED);
        assertThat(FairUseModel.step(100, 100.0)).isEqualTo(FairUseModel.Step.EXTRAS_PAUSED);
        assertThat(FairUseModel.step(109.9, 100.0)).isEqualTo(FairUseModel.Step.EXTRAS_PAUSED);
        assertThat(FairUseModel.step(110, 100.0)).isEqualTo(FairUseModel.Step.REDUCED);
        assertThat(FairUseModel.reached(50, 100.0)).isEmpty();
        assertThat(FairUseModel.reached(85, 100.0)).containsExactly(80);
        assertThat(FairUseModel.reached(115, 100.0)).containsExactly(80, 100, 110);
        assertThat(FairUseModel.reached(115, null)).isEmpty();
    }

    @Test
    void aDestinationAt1080p30SendsAboutTwoTerabytesAMonth() {
        // 6 Mbps video + 128 kbps audio + 4% overhead: about 2.67 GiB an hour, 1,923 GiB in 30 days.
        assertThat(FairUseModel.gbPerHour(6000)).isCloseTo(2.671, within(0.001));
        assertThat(FairUseModel.gbPerHour(6000) * 720).isCloseTo(1923, within(1.0));
        assertThat(FairUseModel.gbPerHour(4000) * 720).isCloseTo(1295, within(1.0)); // 720p30
    }

    @Test
    void pastTheAllowanceOnlyOneDestinationPerStreamCounts() {
        // 720 stream-hours, 2 destinations at 2 GB/h: 2,880 GB at full use. A 1,000 GB allowance is reached after
        // 250 hours; the other 470 hours go to one destination: 1,000 + 940.
        assertThat(FairUseModel.worstGb(720, 2, 2, null)).isEqualTo(2880);
        assertThat(FairUseModel.worstGb(720, 2, 2, 1000)).isCloseTo(1940, within(1e-9));
        assertThat(FairUseModel.worstGb(720, 2, 2, 5000)).isEqualTo(2880); // never reached
        assertThat(FairUseModel.worstGb(720, 1, 2, 1000)).isEqualTo(1440); // one destination: fair use can't lower it
        assertThat(FairUseModel.fullUseHours(1, 10)).isEqualTo(10); // Free: its monthly hours
        assertThat(FairUseModel.fullUseHours(3, 0)).isEqualTo(2160); // 3 streams 24/7
    }

    @Test
    void theWorstCaseIsComparedWithThePriceBeforeGstAndAfterTheGatewayFee() {
        // Creator as sold: 1 stream x 2 destinations at 1080p30, ₹499 a month. Net: 499 / 1.18 x 0.98 = ₹414.42.
        FairUseModel.PlanLimits creator = new FairUseModel.PlanLimits("ESSENTIALS", "Creator", 1, 2, 1080, 30, 6000, 0, null, 49_900L);
        FairUseModel.Inputs digitalOcean = new FairUseModel.Inputs("INR", 0.85, null, "unset", 2);
        FairUseModel.PlanCase c = FairUseModel.planCase(creator, digitalOcean, null);
        assertThat(c.netPrice()).isCloseTo(414.42, within(0.01));
        assertThat(c.streamHours()).isEqualTo(720);
        assertThat(c.fullUseGb()).isCloseTo(3846, within(1.0));
        assertThat(c.worstGb()).isEqualTo(c.fullUseGb());
        assertThat(c.trafficCost()).isCloseTo(3846 * 0.85, within(1.0));
        assertThat(c.serverCost()).isNull(); // unknown: traffic only
        assertThat(c.worstCost()).isEqualTo(c.trafficCost());
        assertThat(c.meetsRule()).isFalse();
        assertThat(c.costShare()).isGreaterThan(7);
        assertThat(c.typicalAccounts()).isNull();
        // At ₹0.85 a GB beyond the included transfer, even one destination all month (1,923 GB) costs more than
        // 60% of ₹414: no allowance can make it pass.
        assertThat(FairUseModel.allowanceFor(creator, digitalOcean)).isNull();
    }

    @Test
    void aSuggestedAllowanceMeetsTheSixtyPercentRuleAndOneMuchLargerDoesNot() {
        // At about ₹0.10 a GB (Hetzner's €1/TB) with a ₹40 server share per stream-month.
        FairUseModel.Inputs cheap = new FairUseModel.Inputs("INR", 0.10, 40.0 / 720, "APP_SERVER_COST_PER_STREAM_MONTH", 2);
        FairUseModel.PlanLimits creator = new FairUseModel.PlanLimits("ESSENTIALS", "Creator", 1, 2, 1080, 30, 6000, 0, null, 49_900L);
        // Budget: (414.42 x 0.6 − 40) / 0.10 = 2,086 GB. One destination all month is 1,923 of it; each GB of
        // allowance used at two destinations adds half a GB beyond that: about 327 GB.
        Integer a = FairUseModel.allowanceFor(creator, cheap);
        assertThat(a).isNotNull().isBetween(320, 335);
        FairUseModel.PlanCase atA = FairUseModel.planCase(withAllowance(creator, a), cheap, null);
        assertThat(atA.serverCost()).isCloseTo(40, within(1e-9));
        assertThat(atA.costShare()).isLessThanOrEqualTo(0.6 + 1e-9);
        assertThat(atA.meetsRule()).isTrue();
        assertThat(FairUseModel.planCase(withAllowance(creator, a + 100), cheap, null).meetsRule()).isFalse();
        assertThat(FairUseModel.planCase(creator, cheap, null).meetsRule()).isFalse(); // unlimited: 3,846 GB
    }

    @Test
    void theTypicalCaseUsesWhatAccountsReallySent() {
        FairUseModel.PlanLimits pro = new FairUseModel.PlanLimits("PRO", "Pro", 3, 5, 1080, 60, 9000, 0, null, 149_900L);
        FairUseModel.Inputs in = new FairUseModel.Inputs("INR", 0.85, 0.05, "measured", 2);
        FairUseModel.PlanCase c = FairUseModel.planCase(pro, in, new FairUseModel.Typical(4, 300, 500));
        assertThat(c.typicalAccounts()).isEqualTo(4);
        assertThat(c.typicalCost()).isCloseTo(500 * 0.85 + 300 * 0.05, within(1e-9));
        assertThat(c.typicalMargin()).isCloseTo(c.netPrice() - c.typicalCost(), within(1e-9));
        assertThat(c.typicalShare()).isCloseTo(c.typicalCost() / c.netPrice(), within(1e-9));
        // Pro at full use: 3 streams x 5 destinations x 2,160 stream-hours at 1080p60 is about 43 TB.
        assertThat(c.fullUseGb()).isCloseTo(3 * 720 * 5 * FairUseModel.gbPerHour(9000), within(1.0));
        assertThat(c.fullUseGb()).isGreaterThan(40_000);
    }

    @Test
    void aFreePlanHasNoRuleButShowsItsCost() {
        FairUseModel.PlanLimits free = new FairUseModel.PlanLimits("FREE", "Free", 1, 1, 720, 30, 4000, 10, 50, 0L);
        FairUseModel.PlanCase c = FairUseModel.planCase(free, new FairUseModel.Inputs("INR", 0.85, null, "unset", 2), null);
        assertThat(c.streamHours()).isEqualTo(10);
        assertThat(c.worstGb()).isCloseTo(10 * FairUseModel.gbPerHour(4000), within(1e-9));
        assertThat(c.netPrice()).isZero();
        assertThat(c.meetsRule()).isNull();
        assertThat(c.costShare()).isNull();
    }

    private static FairUseModel.PlanLimits withAllowance(FairUseModel.PlanLimits p, int gb) {
        return new FairUseModel.PlanLimits(p.plan(), p.name(), p.streams(), p.destinations(), p.height(), p.fps(), p.videoKbps(),
                p.monthlyHours(), gb, p.priceMinor());
    }
}
