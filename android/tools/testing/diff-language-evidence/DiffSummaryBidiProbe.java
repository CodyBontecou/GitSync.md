import java.nio.file.*;
import java.text.Bidi;
import java.util.Locale;
public class DiffSummaryBidiProbe {
    public static void main(String[] args) throws Exception {
        int checks=0;
        for(String row:Files.readAllLines(Path.of(args[0]))) {
            String[] parts=row.split("\t",2); Locale locale=Locale.forLanguageTag(parts[0]);
            for(int[] counts:new int[][]{{0,0,0},{1,2,3},{Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE}}) {
                String text=String.format(locale,parts[1],counts[0],counts[1],counts[2]);
                Bidi bidi=new Bidi(text,Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
                int start=text.indexOf('\u2066')+1,end=text.indexOf('\u2069',start);
                if(start==0||end<=start) throw new AssertionError("Signed segment not isolated");
                String signed=String.format(locale,"+%d \u2212%d",counts[1],counts[2]);
                if(!text.substring(start,end).replace("\u200e", "").equals(signed)) throw new AssertionError("Signed counts changed");
                for(int i=start;i<end;i++) if((bidi.getLevelAt(i)&1)!=0) throw new AssertionError("Signed segment not LTR");
                checks++;
            }
        }
        if(checks!=6) throw new AssertionError("Expected six probes");
        System.out.println("Six JDK Bidi probes preserve LTR signed diff totals at zero, small and Int.MAX_VALUE counts, including Arabic digits.");
        System.out.println("No Android/Compose rendering executed.");
    }
}
