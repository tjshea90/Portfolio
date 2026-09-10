import com.tj.portfolio.util.Fmt;
import com.tj.portfolio.data.Txn;
import com.tj.portfolio.domain.Ledger;
import com.tj.portfolio.domain.Position;
import com.tj.portfolio.data.Quote;
import com.tj.portfolio.net.Claude;
import java.text.SimpleDateFormat;
import java.util.*;

/** Runs the ACTUAL COMPILED v5.9 classes out of the release build. No re-implementation. */
public class ShippedTest {
    static int pass = 0, fail = 0;
    static void check(String what, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (ok) pass++; else fail++;
        System.out.printf("  [%s] %-52s got %s%s%n", ok ? "PASS" : "FAIL", what, got,
            ok ? "" : "   want " + want);
    }
    static void near(String what, double got, double want, double tol) {
        boolean ok = Math.abs(got - want) <= tol;
        if (ok) pass++; else fail++;
        System.out.printf("  [%s] %-52s got %.6f%s%n", ok ? "PASS" : "FAIL", what, got,
            ok ? "" : String.format("   want %.6f", want));
    }

    public static void main(String[] a) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));   // TJ's phone
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));

        System.out.println("1. Fmt.parseRss - every format the app's live feeds emit");
        String[][] dates = {
            {"Fri, 04 Sep 2026 18:45:00 GMT",   "2026-09-04 18:45:00", "MarketWatch"},
            {"Fri, 04 Sep 2026 17:43:00 +0000", "2026-09-04 17:43:00", "Nasdaq"},
            {"2026-09-04 18:38:16",             "2026-09-04 18:38:16", "Investing.com (was +4h)"},
            {"2026-09-02T18:10:34-04:00",       "2026-09-02 22:10:34", "SEC EDGAR"},
            {"2026-09-04T18:30:45.000Z",        "2026-09-04 18:30:45", "ISO + millis (was midnight)"},
            {"2026-09-04T14:30:45.123-04:00",   "2026-09-04 18:30:45", "ISO + millis + offset"},
            {"2026-09-04",                      "2026-09-04 00:00:00", "date only"},
        };
        for (String[] d : dates) {
            long t = Fmt.INSTANCE.parseRss(d[0]);
            check(d[2], t == 0 ? "UNPARSED" : utc.format(new Date(t)), d[1]);
        }
        check("garbage returns 0 rather than a wrong date", Fmt.INSTANCE.parseRss("not a date"), 0L);
        check("empty returns 0", Fmt.INSTANCE.parseRss("   "), 0L);
        // the whole point: a real headline must not read "just now" forever
        long inv = Fmt.INSTANCE.parseRss("2026-09-04 18:38:16");
        boolean future = inv > System.currentTimeMillis() + 60_000;
        check("Investing.com stamp is not in the future", future, false);

        System.out.println("\n2. Txn.unitPriceFromTotal - the fee is not charged twice");
        Txn.Companion T = Txn.Companion;
        near("BUY 100 @ net $155.95 with $5.95 fee -> price", T.unitPriceFromTotal("BUY", 100, 155.95, 5.95), 1.50, 1e-9);
        near("  ...and the recorded cash effect", T.cashEffect("BUY", 100, T.unitPriceFromTotal("BUY",100,155.95,5.95), 155.95, 5.95), -155.95, 1e-9);
        near("SELL 100 @ net $144.05 with $5.95 fee -> price", T.unitPriceFromTotal("SELL", 100, 144.05, 5.95), 1.50, 1e-9);
        near("  ...and the recorded cash effect", T.cashEffect("SELL", 100, T.unitPriceFromTotal("SELL",100,144.05,5.95), 144.05, 5.95), 144.05, 1e-9);
        near("unchanged on a zero-fee Ally buy (3 NVDA)", T.unitPriceFromTotal("BUY", 3, 682.32, 0.0), 682.32/3, 1e-9);
        near("zero quantity is refused, not divided by", T.unitPriceFromTotal("BUY", 0, 500.0, 0.0), 0.0, 1e-12);

        System.out.println("\n3. Ledger - a sale whose fees exceed its proceeds");
        List<Txn> txns = new ArrayList<>();
        txns.add(new Txn(1L, "BUY", "CSCO", 0.1458, 40.0, T.cashEffect("BUY",0.1458,40.0,0,0), 0.0, 1000L, null, "TEST"));
        // amount-only SELL: gross 5.09, fees 5.31 -> signed amount is NEGATIVE
        double sellAmt = T.cashEffect("SELL", 0.1458, 0.0, 5.09, 5.31);
        txns.add(new Txn(2L, "SELL", "CSCO", 0.1458, 0.0, sellAmt, 5.31, 2000L, null, "TEST"));
        near("  the sell's signed cash effect", sellAmt, -0.22, 1e-9);
        List<Position> pos = Ledger.INSTANCE.positions(txns, new HashMap<>(), Ledger.FIFO, 9_000_000_000_000L);
        double realized = 0; for (Position p : pos) realized += p.getRealized();
        double buyCost = 0.1458 * 40.0;
        // realized must be (proceeds - fees) - cost = -0.22 - 5.832
        near("  realized reconciles with cash", realized, sellAmt - buyCost, 1e-9);
        double cash = Ledger.INSTANCE.cash(txns);
        double basis = 0; for (Position p : pos) basis += p.getCostBasis();
        near("  equity - deposits == realized + unrealized", cash - 0.0, realized + (0.0 - basis), 1e-9);

        System.out.println("\n4. Claude.preferredModel - newest family wins, alias breaks ties");
        check("dated snapshot of a NEWER family beats an older alias",
            Claude.INSTANCE.preferredModel(Arrays.asList("claude-sonnet-4-5", "claude-sonnet-5-20260201")),
            "claude-sonnet-5-20260201");
        check("alias still beats a snapshot of the SAME version",
            Claude.INSTANCE.preferredModel(Arrays.asList("claude-sonnet-5-20260201", "claude-sonnet-5")),
            "claude-sonnet-5");
        check("two-digit versions sort numerically, not as strings",
            Claude.INSTANCE.preferredModel(Arrays.asList("claude-sonnet-5", "claude-sonnet-10")),
            "claude-sonnet-10");
        check("sonnet is preferred over opus",
            Claude.INSTANCE.preferredModel(Arrays.asList("claude-opus-9", "claude-sonnet-4")),
            "claude-sonnet-4");
        check("falls back to opus when there is no sonnet",
            Claude.INSTANCE.preferredModel(Arrays.asList("claude-opus-9", "claude-opus-4")),
            "claude-opus-9");

        System.out.printf("%n=== %d passed, %d failed ===%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
