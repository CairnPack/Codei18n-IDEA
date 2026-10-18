package dev.codei18n.integration;
import com.intellij.openapi.application.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.*;
import com.intellij.openapi.command.WriteCommandAction;
import dev.codei18n.idea.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public final class SmokeStarter implements ApplicationStarter {
 public boolean isHeadless() {return true;}
 public int getRequiredModality() {return NOT_IN_EDT;}
 private static void ui(Runnable action) {ApplicationManager.getApplication().invokeAndWait(action);}
 private static void check(boolean value,String message) {if(!value) throw new AssertionError(message); System.out.println("PASS: "+message);}
 public void main(List<String> args) {
  try {
   Path root=Path.of(System.getProperty("codei18n.smoke.project"));
   Path source=root.resolve("Demo.java"); byte[] before=Files.readAllBytes(source);
   Project project=ProjectManagerEx.getInstanceEx().openProject(root,OpenProjectTask.build());
   check(project!=null,"isolated project opened");
   Editor[] editor=new Editor[1]; Document[] document=new Document[1];
   ui(()->{
    VirtualFile file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source);
    document[0]=FileDocumentManager.getInstance().getDocument(file);
    check(document[0]!=null,"physical Java document loaded");
    PsiFile psi=PsiDocumentManager.getInstance(project).getPsiFile(document[0]);
    int[] count={0}; psi.accept(new PsiRecursiveElementWalkingVisitor(){public void visitElement(PsiElement c){if(c instanceof PsiComment)count[0]++;else super.visitElement(c);}});
    System.out.println("PSI="+psi.getClass()+" language="+psi.getLanguage()+" commentCount="+count[0]);
    check(count[0]==2,"Java PSI finds exactly two comments, ignores string literal");
    editor[0]=EditorFactory.getInstance().createEditor(document[0],project);
    project.getService(TranslationSettings.class).getState().enabled=true;
    project.getService(TranslationSettings.class).getState().language="zh-CN";
   });
   waitCount(editor[0],2);
   waitTranslation(editor[0],"计算账户余额");
   ui(()->HighlightingTest.run(project,editor[0],root.getParent().resolve("highlighting-render.png")));
   DisplayModeTest.run(project,editor[0],"计算账户余额");
   ui(()->{
    var hints=editor[0].getInlayModel().getBlockElementsInRange(0,document[0].getTextLength(),TranslationRenderer.class);
    check(hints.size()==2,"two real translation block inlays rendered");
    check(hints.stream().allMatch(h->h.getHeightInPixels()>0 && h.getWidthInPixels()>0),"inlays have nonzero display dimensions");
    check(document[0].getText().equals(new String(before,java.nio.charset.StandardCharsets.UTF_8)),"document remains byte-equivalent to original");
    project.getService(TranslationSettings.class).getState().language="zh-TW";
    TranslationService.getInstance(project).refresh();
   });
   waitTranslation(editor[0],"計算帳戶餘額");
   ui(()->WriteCommandAction.runWriteCommandAction(project,()->{
    document[0].setText(document[0].getText().replace("// Calculate account balance", "// A changed comment"));
    PsiDocumentManager.getInstance(project).commitDocument(document[0]);
   }));
   waitCount(editor[0],1);
   check(true,"editing source comment removes stale translation automatically");
   ui(()->WriteCommandAction.runWriteCommandAction(project,()->{
    document[0].setText(new String(before,java.nio.charset.StandardCharsets.UTF_8));
    PsiDocumentManager.getInstance(project).commitDocument(document[0]);
   }));
   waitCount(editor[0],2);
   waitTranslation(editor[0],"計算帳戶餘額");
   ui(()->WriteCommandAction.runWriteCommandAction(project,()->{
    // Checked inside the same EDT event: before the debounced refresh can rebuild anything.
    document[0].insertString(document[0].getText().indexOf("return income")," ");
    check(count(editor[0])==2,"editing code outside comments keeps every translation in place");
    document[0].insertString(document[0].getText().indexOf("// Calculate account balance")+3,"X");
    check(count(editor[0])==1,"editing one comment removes only its own translation immediately");
   }));
   waitCount(editor[0],1);
   ui(()->WriteCommandAction.runWriteCommandAction(project,()->{
    document[0].setText(new String(before,java.nio.charset.StandardCharsets.UTF_8));
    PsiDocumentManager.getInstance(project).commitDocument(document[0]);
   }));
   waitCount(editor[0],2);
   ui(()->WriteCommandAction.runWriteCommandAction(project,()->{
    String text=document[0].getText();
    int comment=text.indexOf("// Calculate account balance");
    document[0].insertString(comment+"// Calculate account balance".length(),"\n");
    check(count(editor[0])==2,"a new line after a comment keeps its translation");
    document[0].insertString(comment,"    ");
    check(count(editor[0])==2,"indenting before a comment keeps its translation");
   }));
   Thread.sleep(1000); // Let the debounced refresh run: it must keep both translations.
   waitCount(editor[0],2);
   ui(()->WriteCommandAction.runWriteCommandAction(project,()->{
    document[0].setText(new String(before,java.nio.charset.StandardCharsets.UTF_8));
    PsiDocumentManager.getInstance(project).commitDocument(document[0]);
   }));
   waitCount(editor[0],2);
   waitTranslation(editor[0],"計算帳戶餘額");
   TranslationService service=project.getService(TranslationService.class);
   var repository=(MappingRepository)field(service,"repository");
   Thread.sleep(2500); // Let the disk check and debounced refresh settle.
   MappingIndex cached=repository.load(root,"zh-TW");
   Path otherSource=root.resolve("Other.java");
   Files.writeString(otherSource,"// Calculate account balance\nclass Other {}\n");
   Editor[] other=new Editor[1];
   ui(()->{
    VirtualFile file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(otherSource);
    other[0]=EditorFactory.getInstance().createEditor(FileDocumentManager.getInstance().getDocument(file),project);
   });
   waitTranslation(other[0],"計算帳戶餘額");
   Thread.sleep(2500);
   check(repository.load(root,"zh-TW")==cached,"creating and opening another source under the same root does not reparse mappings");
   ui(()->WriteCommandAction.runWriteCommandAction(project,()->{
    Document otherDocument=other[0].getDocument();
    otherDocument.insertString(otherDocument.getTextLength(),"\n");
    FileDocumentManager.getInstance().saveDocument(otherDocument);
   }));
   Thread.sleep(2500);
   check(repository.load(root,"zh-TW")==cached,"saving a source (VFS content event outside .codei18n) does not reparse mappings");
   ui(()->{
    var attached=(Map<?,?>)field(field(service,"presenter"),"editorListeners");
    var listeners=(com.intellij.openapi.Disposable)attached.get(other[0]);
    check(listeners!=null,"opened editor has its own listener disposable");
    EditorFactory.getInstance().releaseEditor(other[0]);
    check(com.intellij.openapi.util.Disposer.isDisposed(listeners) && !attached.containsKey(other[0]),
     "released editor's listeners are disposed, so the service no longer retains the editor");
   });
   Thread.sleep(2500);
   check(repository.load(root,"zh-TW")==cached,"closing a source under a still-open root does not reparse mappings");
   Files.delete(otherSource);
   Path mappings=root.resolve(".codei18n/mappings.json");
   String originalMapping=Files.readString(mappings);
   Files.writeString(mappings,originalMapping.replace("計算帳戶餘額","自動重新載入譯文"));
   // External CLI writes must reload without an explicit VFS refresh or UI interaction.
   waitTranslation(editor[0],"自動重新載入譯文",10);
   Files.writeString(mappings,"{broken");
   waitCount(editor[0],0);
   check(true,"malformed mapping clears stale translations");
   Files.writeString(mappings,originalMapping);
   waitCount(editor[0],2);waitTranslation(editor[0],"計算帳戶餘額");
   Path replacement=mappings.resolveSibling("mappings.tmp");
   Files.writeString(replacement,originalMapping.replace("計算帳戶餘額","原子替換譯文"));
   Files.setLastModifiedTime(replacement,Files.getLastModifiedTime(mappings));
   Files.move(replacement,mappings,StandardCopyOption.REPLACE_EXISTING);
   waitTranslation(editor[0],"原子替換譯文",10);
   check(true,"external replacement with unchanged size and timestamp reloads");
   Files.delete(mappings);
   waitCount(editor[0],0);
   Files.writeString(mappings,originalMapping);
   waitTranslation(editor[0],"計算帳戶餘額",10);
   Path data=root.resolve(".codei18n"),backup=root.resolve("mapping-backup");
   Files.move(data,backup);
   waitCount(editor[0],0);
   Files.move(backup,data);
   waitTranslation(editor[0],"計算帳戶餘額",10);
   check(true,"external mapping file and directory deletion/recreation reloads");
   Path config=data.resolve("config.json");
   String originalConfig=Files.readString(config);
   ui(()->{
    project.getService(TranslationSettings.class).getState().language="";
    TranslationService.getInstance(project).reload();
   });
   waitTranslation(editor[0],"计算账户余额");
   Files.writeString(config,originalConfig.replace("zh-CN","zh-TW"));
   waitTranslation(editor[0],"計算帳戶餘額",10);
   Files.writeString(config,originalConfig);
   waitTranslation(editor[0],"计算账户余额",10);
   check(true,"external config language changes reload without VFS refresh");
   LanguageSamplesTest.run(project,Path.of(System.getProperty("codei18n.smoke.languages")),
    Path.of(System.getProperty("codei18n.smoke.branch")),Path.of(System.getProperty("codei18n.smoke.screenshots")));
   ui(()->{
    var hints=editor[0].getInlayModel().getBlockElementsInRange(0,document[0].getTextLength(),TranslationRenderer.class);
    java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(700,300,java.awt.image.BufferedImage.TYPE_INT_ARGB);
    var graphics=image.createGraphics();int y=0;
    try {
     for(var hint:hints){
      hint.getRenderer().paint(hint,(java.awt.Graphics)graphics,new java.awt.Rectangle(0,y,hint.getWidthInPixels(),hint.getHeightInPixels()),new com.intellij.openapi.editor.markup.TextAttributes());
      y+=hint.getHeightInPixels();
     }
     javax.imageio.ImageIO.write(image,"png",root.getParent().resolve("inlay-render.png").toFile());
    }catch(Exception e){throw new RuntimeException(e);}finally{graphics.dispose();}
    check(true,"Chinese multiline inlay paint succeeds and produces PNG");
   });
   ui(()->{
    var configurable=new TranslationConfigurable(project);
    check(configurable.createComponent()!=null,"settings component created");
    configurable.reset();check(!configurable.isModified(),"settings reset matches persisted state");
    configurable.apply();configurable.disposeUIResources();
    check(true,"settings apply and dispose succeed");
    project.getService(TranslationSettings.class).getState().enabled=false;
    TranslationService.getInstance(project).refresh();
   });
   waitCount(editor[0],0);
   check(Arrays.equals(before,Files.readAllBytes(source)),"source file bytes unchanged");
   ui(()->EditorFactory.getInstance().releaseEditor(editor[0]));
   Files.writeString(Path.of(System.getProperty("codei18n.smoke.result")),"PASS: IDEA platform load, Java PSI including Javadoc, two translation block inlays, zh-CN and zh-TW text, automatic source edit, external mapping/config reload without VFS refresh, same-metadata replacement, file/directory deletion and recreation, malformed mapping cleanup and recovery, Chinese paint PNG, disable clears inlays, source unchanged; language samples Kotlin/Java/TypeScript/JavaScript/Dart/C# (block, merged line group, doc, string literal, PSI vs fallback path), auto refresh on open/rewrite/branch switch, editor screenshots\n");
   System.exit(0);
  } catch(Throwable e) {e.printStackTrace();System.exit(1);}
 }
 private static void waitTranslation(Editor editor,String expected)throws Exception {
  waitTranslation(editor,expected,60);
 }
 private static void waitTranslation(Editor editor,String expected,int timeoutSeconds)throws Exception {
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);
  while(System.nanoTime()<end){boolean[] found={false};ui(()->{
   for(var hint:editor.getInlayModel().getBlockElementsInRange(0,editor.getDocument().getTextLength(),TranslationRenderer.class)) {
    if(expected.equals(hint.getRenderer().text()))found[0]=true;
   }
  });if(found[0]){check(true,"actual translation text: "+expected);return;}Thread.sleep(100);}
  throw new AssertionError("Timed out waiting for translation "+expected);
 }
 private static int count(Editor e) {
  return e.getInlayModel().getBlockElementsInRange(0,e.getDocument().getTextLength(),TranslationRenderer.class).size();
 }
 private static Object field(Object owner,String name) {
  try {var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
  catch(ReflectiveOperationException e){throw new RuntimeException(e);}
 }
 private static void waitCount(Editor e,int expected)throws Exception {
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
  while(System.nanoTime()<end){int[] count={-1};ui(()->count[0]=e.getInlayModel().getBlockElementsInRange(0,e.getDocument().getTextLength(),TranslationRenderer.class).size());if(count[0]==expected)return;Thread.sleep(100);}
  throw new AssertionError("Timed out waiting for "+expected+" inlays");
 }
}
