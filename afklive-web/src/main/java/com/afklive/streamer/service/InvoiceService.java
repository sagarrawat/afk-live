package com.afklive.streamer.service;

import com.afklive.streamer.model.BillingProfile;
import com.afklive.streamer.model.Invoice;
import com.afklive.streamer.repository.BillingProfileRepository;
import com.afklive.streamer.repository.InvoiceRepository;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * BI-3: GST tax invoices. Prices are GST-inclusive; an INR invoice splits the 18 % GST into CGST + SGST
 * when the customer is in the seller's state and IGST otherwise. USD invoices are exports of service
 * (zero-rated). Numbers are sequential per financial year (April to March), e.g. AFK/2026-27/000042.
 */
@Service
@Slf4j
public class InvoiceService {

    static final int GST_PERCENT = 18;

    private final InvoiceRepository invoices;
    private final BillingProfileRepository profiles;
    private final String sellerName;
    private final String sellerAddress;
    private final String sellerGstin;
    private final String sellerState;
    private final String sacCode;

    public InvoiceService(InvoiceRepository invoices, BillingProfileRepository profiles,
                          @Value("${app.billing.seller-name:AFK Live}") String sellerName,
                          @Value("${app.billing.seller-address:}") String sellerAddress,
                          @Value("${app.billing.seller-gstin:}") String sellerGstin,
                          @Value("${app.billing.seller-state:}") String sellerState,
                          @Value("${app.billing.sac:998439}") String sacCode) {
        this.invoices = invoices;
        this.profiles = profiles;
        this.sellerName = sellerName;
        this.sellerAddress = sellerAddress;
        this.sellerGstin = sellerGstin;
        this.sellerState = sellerState;
        this.sacCode = sacCode;
    }

    /** GST split of a GST-inclusive total. */
    public record Tax(long taxable, long cgst, long sgst, long igst) {
    }

    static Tax split(long total, String currency, String customerState, String sellerState) {
        if (!"INR".equalsIgnoreCase(currency)) {
            return new Tax(total, 0, 0, 0); // export of services: zero-rated
        }
        long taxable = Math.round(total * 100.0 / (100 + GST_PERCENT));
        long tax = total - taxable;
        boolean intraState = customerState != null && !customerState.isBlank() && customerState.equals(sellerState);
        if (intraState) {
            long cgst = tax / 2;
            return new Tax(taxable, cgst, tax - cgst, 0);
        }
        return new Tax(taxable, 0, 0, tax);
    }

    static String financialYear(ZonedDateTime at) {
        ZonedDateTime ist = at.withZoneSameInstant(ZoneId.of("Asia/Kolkata"));
        int start = ist.getMonthValue() >= 4 ? ist.getYear() : ist.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /** Issues the invoice for a completed payment (once per payment; repeated calls return the same invoice). */
    @Transactional
    public Invoice issue(Long userId, String orderId, String description, ZonedDateTime periodStart, ZonedDateTime periodEnd,
                         String currency, long total) {
        var existing = invoices.findByPaymentOrderId(orderId);
        if (existing.isPresent()) {
            return existing.get();
        }
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        BillingProfile p = profiles.findById(userId).orElse(null);
        String customerState = p == null ? null : p.getStateCode();
        Tax tax = split(total, currency, customerState, sellerState);
        String fy = financialYear(now);
        int n = invoices.nextNumber(fy);
        Invoice inv = new Invoice();
        inv.setNumber("AFK/" + fy + "/" + String.format("%06d", n));
        inv.setUserId(userId);
        inv.setPaymentOrderId(orderId);
        inv.setDescription(description);
        inv.setPeriodStart(periodStart);
        inv.setPeriodEnd(periodEnd);
        inv.setCurrency(currency.toUpperCase(Locale.ROOT));
        inv.setTaxableAmount(tax.taxable());
        inv.setCgst(tax.cgst());
        inv.setSgst(tax.sgst());
        inv.setIgst(tax.igst());
        inv.setTotal(total);
        if (p != null) {
            inv.setCustomerName(p.getLegalName());
            inv.setCustomerAddress(String.join(", ", java.util.stream.Stream.of(p.getAddressLine(), p.getCity(), p.getPostalCode())
                    .filter(x -> x != null && !x.isBlank()).toList()));
            inv.setCustomerState(p.getStateCode());
            inv.setCustomerGstin(p.getGstin());
        }
        inv.setIssuedAt(now);
        return invoices.save(inv);
    }

    // ------------------------------------------------------------------ PDF

    public byte[] pdf(Invoice inv, String customerEmail) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 56, 56);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8);
        DateTimeFormatter date = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
        ZoneId ist = ZoneId.of("Asia/Kolkata");

        doc.add(new Paragraph("INR".equals(inv.getCurrency()) ? "Tax Invoice" : "Invoice", h1));
        doc.add(new Paragraph(" ", small));
        PdfPTable head = new PdfPTable(2);
        head.setWidthPercentage(100);
        head.addCell(cell(sellerName + "\n" + nz(sellerAddress) + (sellerGstin.isBlank() ? "" : "\nGSTIN: " + sellerGstin), normal, false));
        head.addCell(cell("Invoice no: " + inv.getNumber() + "\nDate: " + inv.getIssuedAt().withZoneSameInstant(ist).format(date)
                + (sellerState.isBlank() ? "" : "\nPlace of supply: " + nz(inv.getCustomerState() == null ? sellerState : inv.getCustomerState())),
                normal, true));
        doc.add(head);
        doc.add(new Paragraph(" ", small));
        doc.add(new Paragraph("Billed to", bold));
        doc.add(new Paragraph((inv.getCustomerName() == null ? customerEmail : inv.getCustomerName())
                + (inv.getCustomerAddress() == null || inv.getCustomerAddress().isBlank() ? "" : "\n" + inv.getCustomerAddress())
                + (inv.getCustomerGstin() == null ? "" : "\nGSTIN: " + inv.getCustomerGstin())
                + "\n" + customerEmail, normal));
        doc.add(new Paragraph(" ", small));

        PdfPTable lines = new PdfPTable(new float[]{5f, 1.4f, 2f});
        lines.setWidthPercentage(100);
        lines.addCell(cell("Description", bold, false));
        lines.addCell(cell("SAC", bold, false));
        lines.addCell(cell("Amount", bold, true));
        String period = inv.getPeriodStart() == null ? "" : "\n" + inv.getPeriodStart().withZoneSameInstant(ist).format(date)
                + " – " + inv.getPeriodEnd().withZoneSameInstant(ist).format(date);
        lines.addCell(cell(inv.getDescription() + period, normal, false));
        lines.addCell(cell(sacCode, normal, false));
        lines.addCell(cell(money(inv.getTaxableAmount(), inv.getCurrency()), normal, true));
        if (inv.getCgst() > 0 || inv.getSgst() > 0) {
            lines.addCell(cell("CGST 9%", normal, false));
            lines.addCell(cell("", normal, false));
            lines.addCell(cell(money(inv.getCgst(), inv.getCurrency()), normal, true));
            lines.addCell(cell("SGST 9%", normal, false));
            lines.addCell(cell("", normal, false));
            lines.addCell(cell(money(inv.getSgst(), inv.getCurrency()), normal, true));
        }
        if (inv.getIgst() > 0) {
            lines.addCell(cell("IGST 18%", normal, false));
            lines.addCell(cell("", normal, false));
            lines.addCell(cell(money(inv.getIgst(), inv.getCurrency()), normal, true));
        }
        lines.addCell(cell("Total (paid)", bold, false));
        lines.addCell(cell("", normal, false));
        lines.addCell(cell(money(inv.getTotal(), inv.getCurrency()), bold, true));
        doc.add(lines);
        doc.add(new Paragraph(" ", small));
        doc.add(new Paragraph(!"INR".equals(inv.getCurrency())
                ? "Export of services without payment of IGST (zero-rated supply)."
                : "Prices include GST. This is a computer-generated invoice and needs no signature.", small));
        doc.close();
        return out.toByteArray();
    }

    private static PdfPCell cell(String text, Font font, boolean right) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setPadding(6);
        c.setBorderWidth(0.5f);
        c.setBorderColor(new java.awt.Color(220, 220, 220));
        if (right) {
            c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        }
        return c;
    }

    public static String money(long minor, String currency) {
        String symbol = "USD".equalsIgnoreCase(currency) ? "$" : "Rs. ";
        return symbol + String.format(Locale.ENGLISH, "%,.2f", minor / 100.0);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
