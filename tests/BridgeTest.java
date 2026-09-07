import com.tj.portfolio.net.ClaudeBridge;
import com.tj.portfolio.net.BridgeResult;
import com.tj.portfolio.data.Txn;
import java.lang.reflect.Method;
import java.util.*;

/** Exercises the SHIPPED offline-bridge parser and the shipped headline cleaner. */
public class BridgeTest {
    static int pass = 0, fail = 0;
    static void check(String what, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (ok) pass++; else fail++;
        System.out.printf("  [%s] %-56s got %s%s%n", ok?"PASS":"FAIL", what, got, ok?"":"   want "+want);
    }

    public static void main(String[] a) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));

        System.out.println("1. A buy/sell with no share count is refused and REPORTED");
        String reply = "Here you go.\n```json\n{\n \"portfolioAppResponse\":1,\n" +
            " \"notes\":\"one row was unclear\",\n \"transactions\":[\n" +
            "  {\"type\":\"BUY\",\"symbol\":\"NVDA\",\"quantity\":3,\"price\":227.44,\"amount\":682.32,\"fees\":0,\"date\":\"2026-09-02\"},\n" +
            "  {\"type\":\"BUY\",\"symbol\":\"CSCO\",\"quantity\":0,\"price\":0,\"amount\":500,\"fees\":0,\"date\":\"2026-09-02\"},\n" +
            "  {\"type\":\"DEPOSIT\",\"symbol\":null,\"quantity\":0,\"price\":0,\"amount\":1000,\"fees\":0,\"date\":\"2026-09-01\"}\n" +
            " ]\n}\n```";
        BridgeResult r = ClaudeBridge.INSTANCE.parse(reply);
        check("the good buy and the deposit survive", r.getTransactions().size(), 2);
        check("the 0-share buy is gone", r.getTransactions().stream()
            .noneMatch(t -> "CSCO".equals(t.getSymbol())), true);
        check("the user is told, not left guessing",
            r.getNotes().contains("no share count"), true);
        check("the original note is kept too", r.getNotes().startsWith("one row was unclear"), true);
        check("a 0-quantity DEPOSIT is untouched - it is not a trade",
            r.getTransactions().stream().anyMatch(t -> "DEPOSIT".equals(t.getType())), true);

        System.out.println("\n2. The fee is not charged twice on an amount-only row");
        String feeReply = "```json\n{\"portfolioAppResponse\":1,\"transactions\":[" +
            "{\"type\":\"BUY\",\"symbol\":\"ONDS\",\"quantity\":100,\"price\":0,\"amount\":155.95,\"fees\":5.95,\"date\":\"2026-09-02\"}]}\n```";
        Txn t = ClaudeBridge.INSTANCE.parse(feeReply).getTransactions().get(0);
        check("per-share price excludes the fee", String.format("%.4f", t.getPrice()), "1.5000");
        check("cash effect is exactly the net the statement showed",
            String.format("%.2f", t.getAmount()), "-155.95");

        System.out.println("\n3. The v5.2 protections still hold");
        check("a prompt file is still refused",
            ClaudeBridge.INSTANCE.parse(
                "<!-- portfolio-app-prompt-file: ... -->\n# Portfolio review request\n" +
                "## IMPORTANT - how to answer\n```json\n{\"portfolioAppResponse\":1,\"advice\":{}}\n```"
            ).getError() != null, true);
        check("a placeholder row is still discarded",
            ClaudeBridge.INSTANCE.parse(
                "```json\n{\"portfolioAppResponse\":1,\"transactions\":[{\"type\":\"BUY\"," +
                "\"symbol\":\"XXXX\",\"quantity\":3,\"price\":227.44,\"amount\":682.32," +
                "\"fees\":0,\"date\":\"1900-01-01\"}]}\n```").getTransactions().size(), 0);
        BridgeResult adv = ClaudeBridge.INSTANCE.parse(
            "```json\n{\"portfolioAppResponse\":1,\"advice\":{\"summary\":\"Concentrated in " +
            "semis; cash is thin.\",\"risks\":\"One sector drives most of the book.\"," +
            "\"actions\":[\"Trim SOXQ\"],\"stocks\":[{\"symbol\":\"NVDA\",\"rating\":8," +
            "\"action\":\"HOLD\",\"target\":\"$240\",\"reasoning\":\"Priced for growth.\"}]}}\n```");
        check("a real advice reply still loads", adv.getAdvice() != null, true);
        check("  with its ratings", adv.getAdvice().getStocks().size(), 1);

        System.out.println("\n4. News.stripHtml - entities that survive XML parsing");
        Class<?> news = Class.forName("com.tj.portfolio.net.News");
        Method strip = news.getDeclaredMethod("stripHtml", String.class);
        strip.setAccessible(true);
        Object N = news.getField("INSTANCE").get(null);
        String[][] cases = {
            {"Nvidia&#8217;s Q3 beat", "Nvidia’s Q3 beat"},
            {"Boeing&rsquo;s backlog &mdash; a look", "Boeing’s backlog — a look"},
            {"Fed&#x2019;s Powell signals a pause", "Fed’s Powell signals a pause"},
            {"S&amp;P 500 hits a record", "S&P 500 hits a record"},
            {"Cisco &ndash; still cheap?", "Cisco – still cheap?"},
            {"Analysts see <b>20%</b> upside", "Analysts see 20% upside"},
            {"Margins improved &lt;200bps", "Margins improved <200bps"},
            {"double escaped &amp;#8217;", "double escaped ’"},
            {"unknown &zzz; left alone", "unknown &zzz; left alone"},
        };
        for (String[] c : cases) check("\"" + c[0] + "\"", strip.invoke(N, c[0]), c[1]);

        System.out.printf("%n=== %d passed, %d failed ===%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
