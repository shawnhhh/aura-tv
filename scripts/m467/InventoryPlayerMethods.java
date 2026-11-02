/* Original Herrin audit utility; inventories declarations without resolving Android dependencies. */
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

class InventoryPlayerMethods {
    public static void main(String[] args) throws Exception {
        Path sourceRoot = Path.of(args[0]);
        List<String> files = List.of(
            "player/exo/ExoPlayerEngine.java", "player/exo/ExoPlayerSession.java",
            "player/exo/ExoMediaSourceFactory.java", "player/exo/ExoUtil.java",
            "player/exo/ExoSubtitleController.java", "player/mpv/MpvPlayerEngine.java",
            "player/mpv/MpvUtil.java", "player/engine/PlayerEngineFactory.java",
            "player/media/MediaItemFactory.java", "player/media/ConcatenatingUrl.java",
            "player/track/TrackUtil.java");
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            for (String relative : files) {
                File file = sourceRoot.resolve("app/src/main/java/com/fongmi/android/tv/" + relative).toFile();
                var task = (JavacTask)compiler.getTask(null, manager, null, List.of("-proc:none"), null,
                    manager.getJavaFileObjects(file));
                for (CompilationUnitTree unit : task.parse()) {
                    var positions = Trees.instance(task).getSourcePositions();
                    new TreePathScanner<Void, Void>() {
                        @Override public Void visitMethod(MethodTree method, Void unused) {
                            long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, method));
                            String signature = method.getName() + "(" + method.getParameters().toString().replace('\n', ' ') + ")";
                            System.out.println(relative + "\t" + line + "\t" + signature);
                            return super.visitMethod(method, unused);
                        }
                    }.scan(unit, null);
                }
            }
        }
    }
}
