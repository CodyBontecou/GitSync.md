import java.nio.file.*;
import java.text.Bidi;
import java.util.Locale;
public class VaultArrowBidiProbe {
    public static void main(String[] args) throws Exception {
        int checks=0;
        for (String row : Files.readAllLines(Path.of(args[0]))) {
            String[] parts=row.split("\t",3);
            for (int count : new int[]{1,12,Integer.MAX_VALUE}) {
                Locale locale=Locale.forLanguageTag(parts[0]);
                String text=String.format(locale,parts[2],count);
                Bidi bidi=new Bidi(text,Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
                int first=Math.max(text.indexOf('↑'),text.indexOf('↓'));
                int end=text.indexOf('\u2069',first);
                if(first<0 || end<=first+1) throw new AssertionError("Arrow/count isolate missing");
                for(int i=first;i<end;i++)
                    if((bidi.getLevelAt(i)&1)!=0) throw new AssertionError("Arrow/count is not entirely LTR: "+parts[0]+" / "+parts[1]);
                String numeric=String.format(locale,"%d",count);
                if(!text.substring(first,end).substring(1).equals(numeric)) throw new AssertionError("Count changed");
                checks++;
            }
        }
        if(checks!=12) throw new AssertionError("Expected 12 probes");
        System.out.println("Twelve Arabic/Hebrew JDK Bidi probes preserve LTR arrow-plus-count spans at 1, 12 and Int.MAX_VALUE, including locale-formatted digits.");
        System.out.println("No Android/Compose rendering executed.");
    }
}
