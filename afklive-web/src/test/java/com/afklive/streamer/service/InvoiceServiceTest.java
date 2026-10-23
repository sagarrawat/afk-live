package com.afklive.streamer.service;

import com.afklive.streamer.model.Invoice;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InvoiceServiceTest {

    @Test
    void gstSplitOfAnInclusivePrice() {
        // ₹499 incl. 18 % GST -> ₹422.88 taxable + ₹76.12 GST
        InvoiceService.Tax intra = InvoiceService.split(49_900, "INR", "29", "29");
        assertThat(intra.taxable()).isEqualTo(42_288);
        assertThat(intra.cgst()).isEqualTo(3_806);
        assertThat(intra.sgst()).isEqualTo(3_806);
        assertThat(intra.igst()).isZero();

        InvoiceService.Tax inter = InvoiceService.split(49_900, "INR", "07", "29");
        assertThat(inter.igst()).isEqualTo(7_612);
        assertThat(inter.cgst() + inter.sgst()).isZero();

        InvoiceService.Tax unknown = InvoiceService.split(49_900, "INR", null, "29");
        assertThat(unknown.igst()).isEqualTo(7_612);

        InvoiceService.Tax odd = InvoiceService.split(101, "INR", "29", "29");
        assertThat(odd.taxable() + odd.cgst() + odd.sgst()).isEqualTo(101);
    }

    @Test
    void exportsAreZeroRated() {
        InvoiceService.Tax usd = InvoiceService.split(900, "USD", null, "29");
        assertThat(usd.taxable()).isEqualTo(900);
        assertThat(usd.cgst() + usd.sgst() + usd.igst()).isZero();
    }

    @Test
    void financialYearStartsInAprilIndianTime() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        assertThat(InvoiceService.financialYear(ZonedDateTime.of(2026, 3, 31, 23, 0, 0, 0, ist))).isEqualTo("2025-26");
        assertThat(InvoiceService.financialYear(ZonedDateTime.of(2026, 4, 1, 0, 10, 0, 0, ist))).isEqualTo("2026-27");
        // 31 Mar 20:00 UTC is already 1 April in India
        assertThat(InvoiceService.financialYear(ZonedDateTime.of(2026, 3, 31, 20, 0, 0, 0, ZoneId.of("UTC")))).isEqualTo("2026-27");
        assertThat(InvoiceService.financialYear(ZonedDateTime.of(2099, 6, 1, 0, 0, 0, 0, ist))).isEqualTo("2099-00");
    }

    @Test
    void moneyFormatting() {
        assertThat(InvoiceService.money(149_900, "INR")).isEqualTo("Rs. 1,499.00");
        assertThat(InvoiceService.money(900, "USD")).isEqualTo("$9.00");
    }

    @Test
    void pdfRendersForBothCurrencies() {
        InvoiceService svc = new InvoiceService(mock(com.afklive.streamer.repository.InvoiceRepository.class),
                mock(com.afklive.streamer.repository.BillingProfileRepository.class), "AFK Live", "Bengaluru", "29ABCDE1234F1Z5", "29", "998439");
        for (String cur : new String[]{"INR", "USD"}) {
            Invoice inv = new Invoice();
            inv.setNumber("AFK/2026-27/000001");
            inv.setDescription("AFK Live Creator plan, monthly");
            inv.setCurrency(cur);
            inv.setTaxableAmount(42_288);
            inv.setCgst(3_806);
            inv.setSgst(3_806);
            inv.setTotal(49_900);
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
            inv.setIssuedAt(now);
            inv.setPeriodStart(now);
            inv.setPeriodEnd(now.plusMonths(1));
            byte[] pdf = svc.pdf(inv, "aarti@example.com");
            assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
            assertThat(pdf.length).isGreaterThan(1000);
        }
    }
}
