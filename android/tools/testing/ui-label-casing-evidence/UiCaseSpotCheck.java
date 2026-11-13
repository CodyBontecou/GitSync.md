import java.util.Locale;
public class UiCaseSpotCheck {
    public static void main(String[] args) {
        Locale.setDefault(Locale.ENGLISH); // Isolated JVM only; does not change device/app settings.
        Locale tr = Locale.forLanguageTag("tr");
        check("BİTİR", "bitir".toUpperCase(tr));
        check("İ", "i".toUpperCase(tr));
        check("I", "ı".toUpperCase(tr));
        check("STRASSE", "Straße".toUpperCase(Locale.GERMAN));
        check("日本語", "日本語".toUpperCase(Locale.JAPANESE));
        if ("BİTİR".equals("bitir".toUpperCase(Locale.ROOT))) throw new AssertionError("Probe must distinguish the old casing");
        String authored = "İLERİ";
        String lower = authored.toLowerCase(Locale.ROOT);
        String legacy = Character.toTitleCase(lower.charAt(0)) + lower.substring(1);
        if (!legacy.contains("\u0307")) throw new AssertionError("Expected legacy combining-dot artifact");
        System.out.println("Five explicit-locale JDK casing probes passed with English default. Legacy root casing differs and introduces combining dots.");
        System.out.println("This does not execute Compose or resolve Android configuration.");
    }
    private static void check(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
}
