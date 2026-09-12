import com.tj.portfolio.data.Txn;
import com.tj.portfolio.data.Quote;
import com.tj.portfolio.domain.Ledger;
import com.tj.portfolio.domain.Position;
import com.tj.portfolio.domain.PortfolioTotals;
import com.tj.portfolio.domain.Fees;
import java.util.*;

/**
 * Property tests run against the SHIPPED Ledger bytecode, not a port of it. Asserts the
 * invariants this project has always claimed, over randomised transaction histories.
 */
public class LedgerPropTest {
    static final String[] SYMS = {"NVDA","CSCO","SOXQ","BA","ONDS"};
    static final String[] TYPES = {"BUY","BUY","SELL","DEPOSIT","WITHDRAWAL","DIVIDEND","FEE"};
    static final long DAY = 86_400_000L;

    static List<Txn> gen(Random r, int n) {
        List<Txn> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String ty = TYPES[r.nextInt(TYPES.length)];
            boolean trade = ty.equals("BUY") || ty.equals("SELL");
            String sym = (trade || ty.equals("DIVIDEND")) ? SYMS[r.nextInt(SYMS.length)] : null;
            double qty = trade ? Math.round(r.nextDouble()*50*1e4)/1e4 : 0.0;
            if (trade && qty < 1e-9) qty = 0.5;               // the importer refuses 0-share trades
            double px = trade ? Math.round(r.nextDouble()*500*1e4)/1e4 : 0.0;
            boolean amountOnly = trade && r.nextInt(4) == 0;
            if (amountOnly) px = 0.0;
            // fees big enough to occasionally swamp a small sale - the v5.9 unitPrice case
            double fee = r.nextInt(3) == 0 ? Math.round(r.nextDouble()*8*100)/100.0 : 0.0;
            double gross = trade ? qty * (px > 0 ? px : r.nextDouble()*500) : r.nextDouble()*3000;
            gross = Math.round(gross*100)/100.0;
            double amount = Txn.Companion.cashEffect(ty, qty, px, gross, fee);
            out.add(new Txn((long) i, ty, sym, qty, px, amount, fee,
                1_750_000_000_000L + r.nextInt(400)*DAY, null, "TEST"));
        }
        return out;
    }

    static double sumRealized(List<Position> p){ double s=0; for(Position x:p) s+=x.getRealized(); return s; }
    static double sumBasis(List<Position> p){ double s=0; for(Position x:p) s+=x.getCostBasis(); return s; }

    public static void main(String[] a) {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        int runs = 20000, bad = 0;
        // How many runs actually held shares bought inside the session window. Reported so
        // that "is this path even being exercised?" is answerable from the output rather
        // than assumed - the question nobody asked while it sat at zero for 20,000 runs.
        int sawToday = 0;
        String firstFailure = null;
        for (int seed = 0; seed < runs; seed++) {
            Random r = new Random(seed);
            List<Txn> txns = gen(r, 1 + r.nextInt(40));
            Map<String, Double> prices = new HashMap<>();
            for (String s : SYMS) prices.put(s, Math.round(r.nextDouble()*500*100)/100.0);

            // THE SESSION INSTANT MUST LAND INSIDE THE GENERATED DATES (Part 10 audit).
            //
            // This was pinned at 9_000_000_000_000L - the year 2255 - while `gen` dates every
            // transaction around 1_750_000_000_000L (2025-26). No generated transaction could
            // ever fall inside `today`, so across all 20,000 runs `sharesToday` was always 0,
            // the entire same-day pool path of both replays was never executed, and invariant
            // 6 below ("day figures differ with boughtTodayCount 0") could not fire in either
            // direction. Drawing the session from the SAME distribution as the transactions
            // means roughly a tenth of runs now hold shares bought "today", which is what
            // actually exercises it.
            long session = 1_750_000_000_000L + r.nextInt(400)*DAY;
            List<Position> f = Ledger.INSTANCE.positions(txns, new HashMap<>(), Ledger.FIFO, session);
            List<Position> v = Ledger.INSTANCE.positions(txns, new HashMap<>(), Ledger.AVERAGE, session);
            for (Position p : f) if (p.getSharesToday() > 1e-9) { sawToday++; break; }

            double mvF=0, mvV=0;
            for (Position p : f) mvF += p.getShares()*prices.get(p.getSymbol());
            for (Position p : v) mvV += p.getShares()*prices.get(p.getSymbol());

            List<String> problems = new ArrayList<>();
            // 1. total lifetime P/L is identical under both methods
            double lifeF = sumRealized(f) + (mvF - sumBasis(f));
            double lifeV = sumRealized(v) + (mvV - sumBasis(v));
            if (Math.abs(lifeF - lifeV) > 1e-6)
                problems.add(String.format("lifetime P/L FIFO %.6f vs AVG %.6f", lifeF, lifeV));
            // 2. share counts agree between methods
            Map<String,Double> sf = new HashMap<>(), sv = new HashMap<>();
            for (Position p : f) sf.put(p.getSymbol(), p.getShares());
            for (Position p : v) sv.put(p.getSymbol(), p.getShares());
            for (String s : sf.keySet())
                if (Math.abs(sf.get(s) - sv.getOrDefault(s, 0.0)) > 1e-6)
                    problems.add(s + ": shares disagree");
            // 2b. AND SO DO THE SHARES BOUGHT TODAY (Part 10 audit). Whether a held share was
            // exposed to the overnight move is a fact about the world, not a cost-basis
            // convention, so the two methods must answer it identically - `sharesToday` feeds
            // `dayPnl`, and the app's "Today" headline must not move when the Settings
            // preference does. (What they may legitimately differ on is `avgCostToday`: FIFO
            // knows which of today's lots is still open, an average-cost book does not.)
            // This is the invariant that would have caught the depletion-order bug.
            Map<String,Double> tf = new HashMap<>(), tv = new HashMap<>();
            for (Position p : f) tf.put(p.getSymbol(), p.getSharesToday());
            for (Position p : v) tv.put(p.getSymbol(), p.getSharesToday());
            for (String s : tf.keySet())
                if (Math.abs(tf.get(s) - tv.getOrDefault(s, 0.0)) > 1e-6)
                    problems.add(s + ": sharesToday disagree FIFO " + tf.get(s) + " vs AVG " + tv.getOrDefault(s, 0.0));
            // 2c. and neither may ever claim more was bought today than is actually held
            for (List<Position> pos : Arrays.asList(f, v)) for (Position p : pos)
                if (p.getSharesToday() > p.getShares() + 1e-6)
                    problems.add(p.getSymbol()+": sharesToday exceeds shares");
            // 3. no negative shares, no negative basis, a closed position keeps no basis
            for (List<Position> pos : Arrays.asList(f, v)) for (Position p : pos) {
                if (p.getShares() < -1e-9) problems.add(p.getSymbol()+": negative shares");
                if (p.getCostBasis() < -1e-6) problems.add(p.getSymbol()+": negative basis");
                if (p.getShares() < 1e-9 && Math.abs(p.getCostBasis()) > 1e-6)
                    problems.add(p.getSymbol()+": closed but keeps basis");
            }
            // 4. equity - net deposits == realized + unrealized + dividends - fee rows
            double feeRows = 0;
            for (Txn t : txns) if (t.getType().equals("FEE")) feeRows += Math.abs(t.getAmount());
            for (int k = 0; k < 2; k++) {
                List<Position> pos = k == 0 ? f : v;
                double mv = k == 0 ? mvF : mvV;
                double lhs = (mv + Ledger.INSTANCE.cash(txns)) - Ledger.INSTANCE.netDeposits(txns);
                double rhs = sumRealized(pos) + (mv - sumBasis(pos))
                    + Ledger.INSTANCE.dividends(txns) - feeRows;
                if (Math.abs(lhs - rhs) > 1e-6)
                    problems.add(String.format("%s reconciliation off by %.9f", k==0?"FIFO":"AVG", lhs-rhs));
            }
            // 5. totals() never produces NaN or Infinity
            Map<String, Quote> qs = new HashMap<>();
            for (String s : SYMS) qs.put(s, new Quote(s, s, prices.get(s), prices.get(s)*0.98,
                0,0,null,null,"OPEN", Collections.emptyList(), "USD", 0L, 0L, false));
            PortfolioTotals tot = Ledger.INSTANCE.totals(txns, f, qs, null);
            for (double d : new double[]{tot.getMarketValue(), tot.getCash(), tot.getTotalGain(),
                    tot.getTotalGainPct(), tot.getDayGain(), tot.getDayGainPct(),
                    tot.getBrokerDayGain(), tot.getBrokerDayGainPct(), tot.getUnrealizedPct()})
                if (Double.isNaN(d) || Double.isInfinite(d)) { problems.add("totals produced NaN/Inf"); break; }
            // 6. the summary card only names same-day buys when the two day figures differ
            if (Math.abs(tot.getBrokerDayGain() - tot.getDayGain()) > 0.005
                && tot.getBoughtTodayCount() == 0)
                problems.add("day figures differ with boughtTodayCount 0");

            if (!problems.isEmpty()) {
                bad++;
                if (firstFailure == null) firstFailure = "seed " + seed + ": " + String.join("; ", problems);
            }
        }
        System.out.println(runs + " randomised histories against the shipped Ledger");
        System.out.println("  clean      : " + (runs - bad));
        System.out.println("  violations : " + bad);
        System.out.println("  with same-day holdings (exercises the today pool): " + sawToday);
        if (sawToday == 0) {
            System.out.println("  !! the same-day path was never exercised - the session instant "
                + "and the generated dates have drifted apart again");
            bad++;
        }
        if (firstFailure != null) System.out.println("  first: " + firstFailure);

        // Fee schedule regression - the v5.1 cases, unchanged by this round
        System.out.println("\nAlly fee schedule (must be untouched by v5.9)");
        double[][] fee = {
            // type 0=BUY 1=SELL, shares, price, expected total
            {0, 100, 50, 0.00}, {0, 1, 291.06, 0.00},
            {1, 1, 291.06, 0.01}, {1, 100, 291.06, 0.62},
            {1, 100000, 50, 103.00 + 9.79},
            // under $2: 4.95 + 1c/share = 5.95, capped at 5% of the $100 trade value
            {0, 100, 1.00, 5.00},
            {0, 100, 2.00, 0.00},            // the $2.00 boundary exactly
            // 5% cap on a $15 sale = 0.75 commission, PLUS the SEC fee rounded up to a cent
            {1, 10, 1.50, 0.76},
        };
        int fp = 0, ff = 0;
        for (double[] c : fee) {
            String ty = c[0] == 0 ? "BUY" : "SELL";
            double got = Fees.INSTANCE.forEquityTrade(ty, c[1], c[2]).getTotal();
            boolean ok = Math.abs(got - c[3]) < 0.005;
            if (ok) fp++; else ff++;
            System.out.printf("  [%s] %-4s %8.0f @ %8.2f -> %.2f%s%n", ok?"PASS":"FAIL", ty, c[1], c[2],
                got, ok ? "" : String.format("  want %.2f", c[3]));
        }
        System.out.printf("%nledger violations: %d   fee cases: %d passed, %d failed%n", bad, fp, ff);
        if (bad > 0 || ff > 0) System.exit(1);
    }
}
