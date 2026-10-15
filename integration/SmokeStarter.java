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
   ui(()->{
    var hints=editor[0].getInlayModel().getBlockElementsInRange(0,document[0].getTextLength(),TranslationRenderer.class);
    check(hints.size()==2,"two real translation block inlays rendered");
    check(hints.stream().allMatch(h->h.getHeightInPixels()>0 && h.getWidthInPixels()>0),"inlays have nonzero display dimensions");
    check(document[0].getText().equals(new String(before,java.nio.charset.StandardCharsets.UTF_8)),"document remains byte-equivalent to original");
    project.getService(TranslationSettings.class).getState().language="zh-TW";
    project.getService(TranslationService.class).requestRefresh(false);
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
   Path mappings=root.resolve(".codei18n/mappings.json");
   String originalMapping=Files.readString(mappings);
   Files.writeString(mappings,originalMapping.replace("計算帳戶餘額","自動重新載入譯文"));
   refreshFile(mappings);
   waitTranslation(editor[0],"自動重新載入譯文");
   Files.writeString(mappings,"{broken");refreshFile(mappings);
   waitCount(editor[0],0);
   check(true,"malformed mapping clears stale translations");
   Files.writeString(mappings,originalMapping);refreshFile(mappings);
   waitCount(editor[0],2);waitTranslation(editor[0],"計算帳戶餘額");
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
    project.getService(TranslationService.class).requestRefresh(false);
   });
   waitCount(editor[0],0);
   check(Arrays.equals(before,Files.readAllBytes(source)),"source file bytes unchanged");
   ui(()->EditorFactory.getInstance().releaseEditor(editor[0]));
   Files.writeString(Path.of(System.getProperty("codei18n.smoke.result")),"PASS: IDEA platform load, Java PSI including Javadoc, two translation block inlays, zh-CN and zh-TW text, automatic source edit and VFS mapping reload, malformed mapping cleanup and recovery, Chinese paint PNG, disable clears inlays, source unchanged\n");
   System.exit(0);
  } catch(Throwable e) {e.printStackTrace();System.exit(1);}
 }
 private static void refreshFile(Path path) {
  ui(()->{var file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);file.refresh(false,false);});
 }
 private static void waitTranslation(Editor editor,String expected)throws Exception {
  var textField=TranslationRenderer.class.getDeclaredField("text");textField.setAccessible(true);
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
  while(System.nanoTime()<end){boolean[] found={false};ui(()->{
   for(var hint:editor.getInlayModel().getBlockElementsInRange(0,editor.getDocument().getTextLength(),TranslationRenderer.class)) {
    try {if(expected.equals(textField.get(hint.getRenderer())))found[0]=true;}catch(Exception e){throw new RuntimeException(e);}
   }
  });if(found[0]){check(true,"actual translation text: "+expected);return;}Thread.sleep(100);}
  throw new AssertionError("Timed out waiting for translation "+expected);
 }
 private static void waitCount(Editor e,int expected)throws Exception {
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
  while(System.nanoTime()<end){int[] count={-1};ui(()->count[0]=e.getInlayModel().getBlockElementsInRange(0,e.getDocument().getTextLength(),TranslationRenderer.class).size());if(count[0]==expected)return;Thread.sleep(100);}
  throw new AssertionError("Timed out waiting for "+expected+" inlays");
 }
}
