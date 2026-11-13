import java.nio.file.*;
import java.text.Bidi;
import java.util.Locale;
public class GitWorkflowArgumentProbe {
    public static void main(String[] args) throws Exception {
        String[] samples={"123","alpha/path.md","\u0645\u0644\u0627\u062d\u0638\u0627\u062a.md","\u05d4\u05e2\u05e8\u05d5\u05ea.md","100% complete"};
        int checks=0;
        for(String row:Files.readAllLines(Path.of(args[0]))) {
            String[] parts=row.split("\t",3);
            for(String sample:samples) {
                String second=sample+"-second";
                String text=String.format(Locale.forLanguageTag(parts[0]),parts[2],sample,second);
                if(!text.contains("\u2068"+sample+"\u2069")) throw new AssertionError("First payload or bounds changed");
                if(parts[2].contains("%2$s")&&!text.contains("\u2068"+second+"\u2069")) throw new AssertionError("Second payload or bounds changed");
                int start=text.indexOf("\u2068"+sample)+1;
                Bidi bidi=new Bidi(text,Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
                boolean rtl=sample.startsWith("\u0645")||sample.startsWith("\u05d4");
                if(((bidi.getLevelAt(start)&1)==1)!=rtl) throw new AssertionError("First payload character has unexpected direction");
                checks++;
            }
        }
        if(checks!=90) throw new AssertionError("Expected ninety probes, got "+checks);
        System.out.println("90 JDK formatter/Bidi probes preserve String argument payloads and isolates across nine resource templates, two locales and five sample types.");
        System.out.println("No Compose/Android execution; internal path ordering, glyph placement and speech remain unqualified.");
    }
}
