import java.nio.file.*;
import java.text.Bidi;
import java.util.Locale;
public class UpstreamDisplayBidiProbe {
    public static void main(String[] args) throws Exception {
        String[][] pairs={{"origin","main"},{"origin","feature/notes"},{"123","456"},
            {"\u0628\u0639\u064a\u062f","\u0645\u0644\u0627\u062d\u0638\u0627\u062a/\u0641\u0631\u0639"},
            {"\u05de\u05e8\u05d5\u05d7\u05e7","\u05d4\u05e2\u05e8\u05d5\u05ea/\u05e2\u05e0\u05e3"},
            {"\u0661\u0662\u0663","\u0664\u0665\u0666"}};
        int checks=0;
        for(String row:Files.readAllLines(Path.of(args[0]))) {
            String[] parts=row.split("\t",2);
            for(String[] pair:pairs) {
                String text=String.format(Locale.forLanguageTag(parts[0]),parts[1],pair[0],pair[1]);
                int start=text.indexOf('\u2066')+1,end=text.indexOf('\u2069',start);
                if(start==0||end<=start) throw new AssertionError("Group isolate missing");
                String group=text.substring(start,end);
                boolean marked=group.contains("\u200e/\u200e");
                String expected=pair[0]+(marked?"\u200e/\u200e":"/")+pair[1];
                if(!group.equals(expected)) throw new AssertionError("Identifier payload changed");
                int separator=start+pair[0].length()+(marked?1:0);
                Bidi bidi=new Bidi(text,Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
                if(text.charAt(separator)!='/' || (bidi.getLevelAt(separator)&1)!=0)
                    throw new AssertionError("Remote/branch separator is not LTR");
                if(pair[0].equals("origin") || pair[0].equals("123")) {
                    for(int i=start;i<end;i++) if((bidi.getLevelAt(i)&1)!=0) throw new AssertionError("ASCII identifier group is not LTR");
                }
                checks++;
            }
        }
        if(checks!=12) throw new AssertionError("Expected twelve probes");
        System.out.println("Twelve JDK Bidi probes preserve identifier payloads and the outer LTR remote/branch separator, including Unicode identifiers and Arabic digits.");
        System.out.println("Internal RTL runs/path separators and Android/Compose rendering are not qualified.");
    }
}
