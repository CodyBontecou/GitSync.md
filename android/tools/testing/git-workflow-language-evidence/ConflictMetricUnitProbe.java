public class ConflictMetricUnitProbe {
    public static void main(String[] args) {
        String text="\u00e9\ud83d\ude00\r\n";
        int units=text.length(), bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(units!=5 || bytes!=8) throw new AssertionError("Unexpected fixture encoding");
        System.out.println("Generated UTF-8 text: accented letter + emoji + CRLF");
        System.out.println("String.length (current UI byte input): "+units);
        System.out.println("UTF-8 encoded bytes: "+bytes);
        System.out.println("Counterexample only; no production metrics correction or native rendering executed.");
    }
}
