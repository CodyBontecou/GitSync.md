import java.nio.file.*;
import java.text.Bidi;
import java.util.*;
public class SettingsBidiProbe {
    public static void main(String[] args) throws Exception {
        Map<String,Map<String,String>> forms=new HashMap<>();
        for(String row:Files.readAllLines(Path.of(args[0]))) {
            String[] parts=row.split("\t",3);
            forms.computeIfAbsent(parts[0],k->new HashMap<>()).put(parts[1],parts[2]);
        }
        int checks=0;
        for(String tag:new String[]{"ar","he"}) {
            Locale locale=Locale.forLanguageTag(tag); Map<String,String> f=forms.get(tag);
            for(String login:new String[]{"alpha","alpha-beta","12345"}) {
                String subject="\u2066@"+login+"\u2069";
                String text=String.format(locale,f.get("message"),subject);
                int start=text.indexOf('@'),end=start+login.length()+1;
                Bidi bidi=new Bidi(text,Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
                if(!text.contains("\u2068"+subject+"\u2069")) throw new AssertionError("Nested subject bounds missing");
                for(int i=start;i<end;i++) if((bidi.getLevelAt(i)&1)!=0) throw new AssertionError("Handle not entirely LTR");
                checks++;
            }
            String fallback=f.get("fallback");
            String text=String.format(locale,f.get("message"),fallback);
            Bidi bidi=new Bidi(text,Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
            int start=text.indexOf(fallback),latin=text.indexOf("GitHub",start);
            if(start<0 || (bidi.getLevelAt(start)&1)!=1 || latin<start) throw new AssertionError("Fallback lost RTL direction");
            for(int i=latin;i<latin+6;i++) if((bidi.getLevelAt(i)&1)!=0) throw new AssertionError("GitHub brand not LTR");
            checks++;
            for(int build:new int[]{8,Integer.MAX_VALUE}) {
                text=String.format(locale,f.get("version"),"1.0.3",build);
                if(text.charAt(0)!='\u2066' || text.charAt(text.length()-1)!='\u2069') throw new AssertionError("Version bounds missing");
                bidi=new Bidi(text,Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
                for(int i=1;i<text.length()-1;i++) if((bidi.getLevelAt(i)&1)!=0) throw new AssertionError("Version data not LTR");
                if(!text.contains(String.format(locale,"%d",build))) throw new AssertionError("Build number changed");
                checks++;
            }
        }
        if(checks!=12) throw new AssertionError("Expected twelve probes");
        System.out.println("12 JDK Bidi/formatter probes pass: six handles, two RTL fallbacks, four version/build displays.");
        System.out.println("Library spot checks only; no Android/Compose rendering or production composable execution.");
    }
}
