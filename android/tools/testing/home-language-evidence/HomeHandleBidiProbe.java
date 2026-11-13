import java.nio.file.*;
import java.text.Bidi;
import java.util.Locale;
public class HomeHandleBidiProbe {
    public static void main(String[] args) throws Exception {
        int checks=0, oldOdd=0;
        for (String row : Files.readAllLines(Path.of(args[0]))) {
            String[] parts=row.split("\t",2);
            Locale locale=Locale.forLanguageTag(parts[0]);
            for (String login : new String[]{"sample-user-23","12345","CodyBontecou"}) {
                String text=String.format(locale,parts[1],login);
                Bidi bidi=new Bidi(text,Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
                int first=text.indexOf("@"+login);
                if (first<0) throw new AssertionError("Literal handle lost");
                for(int i=first;i<first+login.length()+1;i++)
                    if ((bidi.getLevelAt(i)&1)!=0) throw new AssertionError("Handle is not entirely LTR: "+parts[0]);
                String old=String.format(locale,parts[1].replace("\u200e",""),login);
                Bidi oldBidi=new Bidi(old,Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
                if((oldBidi.getLevelAt(old.indexOf('@'))&1)!=0) oldOdd++;
                checks++;
            }
        }
        if(checks!=6 || oldOdd!=6) throw new AssertionError("Expected six probes and six old RTL @ levels");
        System.out.println("Six authored Arabic/Hebrew handle probes: @ and login remain LTR; all six unbounded originals put @ at an RTL level.");
        System.out.println("JDK Bidi only: no Android/Compose rendering executed.");
    }
}
