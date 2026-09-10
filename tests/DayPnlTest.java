import java.util.*;

/**
 * Ports Position.dayPnl / dayBasis and Ledger.totals's broker-vs-app split, then property-
 * tests the invariants the summary card's reconciliation note depends on.
 */
public class DayPnlTest {
    static class Q { double price, prevClose; Q(double p, double pc){price=p;prevClose=pc;}
        double dayChange(){ return prevClose > 0 ? price - prevClose : 0.0; } }
    static class P {
        double shares, costBasis, sharesToday, costToday;
        P(double s,double cb,double st,double ct){shares=s;costBasis=cb;sharesToday=st;costToday=ct;}
        double avgCostToday(){ return sharesToday > 1e-9 ? costToday/sharesToday : 0.0; }
        double dayPnl(Q q){
            if (q.price <= 0) return 0.0;
            double fresh = Math.min(sharesToday, shares);
            double held  = Math.max(shares - fresh, 0.0);
            double fromClose = q.prevClose > 0 ? held * (q.price - q.prevClose) : 0.0;
            return fromClose + fresh * (q.price - avgCostToday());
        }
        double dayBasis(Q q){
            double fresh = Math.min(sharesToday, shares);
            double held  = Math.max(shares - fresh, 0.0);
            double ref = q.prevClose > 0 ? q.prevClose : q.price;
            return held * ref + fresh * avgCostToday();
        }
    }

    public static void main(String[] a) {
        Random r = new Random(20260904);
        int nDiffersWithZeroCount = 0, nDayBasisNonPositive = 0, nPctBlowup = 0, runs = 200000;
        for (int i = 0; i < runs; i++) {
            int n = 1 + r.nextInt(6);
            double day = 0, brokerDay = 0, dayBase = 0, brokerBase = 0;
            int freshCount = 0;
            for (int k = 0; k < n; k++) {
                double prevClose = r.nextInt(10) == 0 ? 0.0 : 1 + r.nextDouble() * 400;
                double price = r.nextInt(20) == 0 ? 0.0 : 1 + r.nextDouble() * 400;
                double shares = Math.round(r.nextDouble() * 200 * 1e4) / 1e4;
                if (shares < 1e-9) continue;
                double sharesToday = r.nextInt(3) == 0 ? Math.min(shares, r.nextDouble()*shares*1.4) : 0.0;
                double costToday = sharesToday * (1 + r.nextDouble()*400);
                P p = new P(shares, shares * (1+r.nextDouble()*400), sharesToday, costToday);
                Q q = new Q(price, prevClose);
                if (q.price > 0) {
                    day += p.dayPnl(q);
                    dayBase += p.dayBasis(q);
                    if (q.prevClose > 0) { brokerDay += p.shares * q.dayChange(); brokerBase += p.shares * q.prevClose; }
                    if (p.sharesToday > 1e-9) freshCount++;
                    if (p.dayBasis(q) < 0) nDayBasisNonPositive++;
                }
            }
            // INVARIANT: the summary only prints "the N holdings you bought today" when the
            // two figures differ, so they must never differ while N is 0.
            if (Math.abs(brokerDay - day) > 0.005 && freshCount == 0) nDiffersWithZeroCount++;
            double pct = dayBase > 1e-9 ? day / dayBase * 100.0 : 0.0;
            if (Double.isNaN(pct) || Double.isInfinite(pct)) nPctBlowup++;
        }
        System.out.println("runs                                        : " + runs);
        System.out.println("differ while boughtTodayCount == 0 (must be 0): " + nDiffersWithZeroCount);
        System.out.println("negative dayBasis                  (must be 0): " + nDayBasisNonPositive);
        System.out.println("NaN/Inf day percentage             (must be 0): " + nPctBlowup);
    }
}
