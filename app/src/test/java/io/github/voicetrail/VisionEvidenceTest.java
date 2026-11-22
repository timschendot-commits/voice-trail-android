package io.github.voicetrail;

import org.junit.Test;
import java.time.LocalDate;
import static org.junit.Assert.*;

public class VisionEvidenceTest {
    private final LocalDate today = LocalDate.of(2026, 11, 22);

    @Test public void chineseExpiryUsesFullDateAndQuotesNumberedSource() {
        VisionEvidence photo = new VisionEvidence(true);
        photo.add("生产日期：2023-01-01");
        photo.add("有效期至：2026年11月21日");
        String result = photo.answer("过期没有", today);
        assertTrue(result.contains("早于今天"));
        assertTrue(result.contains("第 2 行"));
        assertTrue(result.contains("2026-11-22"));
    }

    @Test public void ambiguousAndMixedLabelDatesNeverGetCompared() {
        for (String s : new String[]{"EXP 01/02/27", "EXP 2026-02-30", "EXP 2028-07",
                "MFG 2026-01-01", "MFG 2026-01-01 EXP 2029-01-01",
                "EXP batch 2029-01-01", "EXP 2029-01-01 2027-01-01"}) {
            VisionEvidence photo = new VisionEvidence(false);
            photo.add(s);
            String reply = photo.answer("expiry", today);
            assertTrue(s, reply.contains("can't establish"));
            assertFalse(s, reply.contains("Device date"));
        }
    }

    @Test public void multipleExpiryCandidatesRemainUncertain() {
        VisionEvidence photo = new VisionEvidence(false);
        photo.add("EXP 2026-12-01");
        photo.add("USE BY 2027-01-01");
        assertTrue(photo.answer("expiry", today).contains("can't establish"));
    }

    @Test public void explicitSameDayNeverBecomesSafeToUseClaim() {
        VisionEvidence photo = new VisionEvidence(false);
        photo.add("Best before: 2026-11-22");
        String response = photo.answer("best before?", today);
        assertTrue(response.contains("2026-11-22, today"));
        assertTrue(response.contains("Line 1"));
    }

    @Test public void aPrivateLabelSuppressesEvenNeighbouringLines() {
        VisionEvidence photo = new VisionEvidence(false);
        photo.add("901243");
        photo.add("Verification code");
        for (String reply : new String[]{photo.summary(), photo.answer("line 1", today)}) {
            assertTrue(reply.contains("hidden"));
            assertFalse(reply.contains("901243"));
        }
    }

    @Test public void chineseNumberedLinesAndReceiptFollowupsStayInSamePhoto() {
        VisionEvidence photo = new VisionEvidence(true);
        photo.add("商品 55.00");
        photo.add("合计 55.00");
        assertTrue(photo.answer("读第二行", today).contains("合计 55.00"));
        assertTrue(photo.answer("多少钱，合计多少", today).contains("第 2 行"));
        assertTrue(photo.answer("读第九行", today).contains("没有这一行"));
    }
}
