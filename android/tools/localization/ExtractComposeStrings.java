import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import com.google.gson.Gson;
import org.jetbrains.kotlin.cli.jvm.compiler.*;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.psi.*;

/** PSI inventory: only visible String arguments of known/composable UI functions, never arbitrary literals. */
public final class ExtractComposeStrings {
  private static final Set<String> VISIBLE = Set.of("text", "title", "subtitle", "label", "placeholder", "contentDescription", "description", "actionTitle", "note", "key");
  private static boolean composable(KtNamedFunction function) {
    return function.getAnnotationEntries().stream().anyMatch(a -> a.getShortName() != null && a.getShortName().asString().equals("Composable"));
  }
  private static boolean insideComposable(PsiElement expression) {
    for (PsiElement p = expression.getParent(); p != null; p = p.getParent()) {
      if (p instanceof KtNamedFunction) return composable((KtNamedFunction)p);
    }
    return false;
  }
  public static void main(String[] args) throws Exception {
    Disposable disposable = Disposer.newDisposable();
    try {
      KotlinCoreEnvironment env = KotlinCoreEnvironment.createForProduction(disposable, new CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES);
      KtPsiFactory factory = new KtPsiFactory(env.getProject(), false);
      Map<Path,KtFile> files = new LinkedHashMap<>();
      try (Stream<Path> paths = Files.walk(Path.of(args[0]))) {
        for (Path path : paths.filter(p -> p.toString().endsWith(".kt")).sorted().collect(Collectors.toList()))
          files.put(path, factory.createFile(path.getFileName().toString(), Files.readString(path)));
      }
      Map<String,List<String>> parameters = new HashMap<>();
      parameters.put("Text", List.of("text")); parameters.put("Icon", List.of("image", "contentDescription")); parameters.put("Image", List.of("image", "contentDescription"));
      for (KtFile file : files.values()) file.accept(new KtTreeVisitorVoid() {
        @Override public void visitNamedFunction(KtNamedFunction function) {
          if (composable(function)) parameters.put(function.getName(), function.getValueParameters().stream().map(p -> p.getName()).collect(Collectors.toList()));
          super.visitNamedFunction(function);
        }
      });
      List<Map<String,Object>> output = new ArrayList<>();
      for (Map.Entry<Path,KtFile> source : files.entrySet()) source.getValue().accept(new KtTreeVisitorVoid() {
        @Override public void visitCallExpression(KtCallExpression call) {
          super.visitCallExpression(call);
          if (call.getCalleeExpression() == null || !insideComposable(call)) return;
          String name = call.getCalleeExpression().getText(); List<String> names = parameters.get(name);
          if (names == null) return;
          List<? extends ValueArgument> arguments = call.getValueArguments();
          for (int i=0; i<arguments.size(); i++) {
            ValueArgument argument = arguments.get(i);
            String parameter = argument.getArgumentName() == null ? (i<names.size()?names.get(i):"") : argument.getArgumentName().getAsName().asString();
            if (!VISIBLE.contains(parameter) || !(argument.getArgumentExpression() instanceof KtStringTemplateExpression)) continue;
            KtStringTemplateExpression literal = (KtStringTemplateExpression)argument.getArgumentExpression();
            StringBuilder format = new StringBuilder(); List<String> substitutions = new ArrayList<>(); boolean valid=true;
            for (KtStringTemplateEntry entry : literal.getEntries()) {
              if (entry instanceof KtLiteralStringTemplateEntry) format.append(entry.getText().replace("%","%%"));
              else if (entry instanceof KtEscapeStringTemplateEntry) format.append(((KtEscapeStringTemplateEntry)entry).getUnescapedValue().replace("%","%%"));
              else if (entry instanceof KtStringTemplateEntryWithExpression) {
                KtExpression expression = ((KtStringTemplateEntryWithExpression)entry).getExpression();
                if (expression == null) { valid=false; break; }
                substitutions.add(expression.getText()); format.append("%").append(substitutions.size()).append("$s");
              } else { valid=false; break; }
            }
            if (!valid) continue;
            String text = substitutions.isEmpty()?format.toString().replace("%%","%"):format.toString();
            // Symbols, repository names alone and technical examples aren't translated copy.
            String words = text.replaceAll("%[0-9]+\\$s", "");
            if (!words.matches("(?s).*[A-Za-z]{2}.*") || text.matches("(?i)(GitSync\\.md|GitHub|Obsidian|HEAD|origin|main|Git LFS|LFS|SSH|HTTPS|HTTP|SHA|MIT)") || text.startsWith("https://") || text.startsWith("git@") || text.startsWith("ssh://")) continue;
            Map<String,Object> record = new LinkedHashMap<>(); record.put("file",source.getKey().toString());
            String sourceText = literal.getContainingFile().getText();
            record.put("start",sourceText.codePointCount(0,literal.getTextRange().getStartOffset()));
            record.put("end",sourceText.codePointCount(0,literal.getTextRange().getEndOffset()));record.put("literal",literal.getText());
            record.put("call",name);record.put("parameter",parameter);record.put("text",text);record.put("arguments",substitutions);
            output.add(record);
          }
        }
      });
      System.out.println(new Gson().toJson(output));
    } finally { Disposer.dispose(disposable); }
  }
}
