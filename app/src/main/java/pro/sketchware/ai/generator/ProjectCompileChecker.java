package pro.sketchware.ai.generator;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

import a.a.a.ProjectBuilder;
import a.a.a.eC;
import a.a.a.hC;
import a.a.a.iC;
import a.a.a.jC;
import a.a.a.kC;
import a.a.a.lC;
import a.a.a.wq;
import a.a.a.yq;
import a.a.a.zy;
import mod.jbk.build.BuildProgressReceiver;
import mod.jbk.build.BuiltInLibraries;
import pro.sketchware.utility.FileUtil;

/**
 * Compiles a project's resources and Java code the way the editor's Run button does, without
 * dexing or packaging, to find out whether it builds at all.
 * <p>
 * The project is read from its saved files through fresh manager instances. Sketchware's source
 * generators look some data up through the editor's shared managers in {@link jC}, so for as long
 * as the sources are generated, those point to the fresh instances, and are put back afterwards.
 * An editor that is open meanwhile only waits for that, and its state stays untouched.
 * <p>
 * Blocks for as long as compiling takes, so run it on a background thread.
 */
public class ProjectCompileChecker {

    private final Context context;

    public ProjectCompileChecker(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * @return The compile errors, described for a model by {@link CompileErrorReport}, or null
     * if the project compiles.
     * @throws Exception If compiling couldn't even be started, e.g. because a file of the
     *                   compiler is missing. That's no fault of the project.
     */
    @Nullable
    public String check(@NonNull String sc_id, @NonNull BuildProgressReceiver progress) throws Exception {
        hC fileManager = new hC(sc_id);
        fileManager.i();
        eC dataManager = new eC(sc_id);
        dataManager.g();
        dataManager.e();
        iC libraryManager = new iC(sc_id);
        libraryManager.i();
        kC resourceManager = new kC(sc_id);
        resourceManager.s();

        yq q = new yq(context, wq.d(sc_id), lC.b(sc_id));
        FileUtil.deleteFile(q.projectMyscPath);
        q.c(context);
        q.a();
        q.a(context, wq.e("600"));
        resourceManager.b(q.resDirectoryPath + File.separator + "drawable-xhdpi");
        resourceManager.c(q.resDirectoryPath + File.separator + "raw");
        resourceManager.a(q.assetsPath + File.separator + "fonts");

        ProjectBuilder builder = new ProjectBuilder(progress, context, q);
        synchronized (jC.class) {
            eC editorData = jC.a;
            hC editorFiles = jC.b;
            kC editorResources = jC.c;
            iC editorLibraries = jC.d;
            jC.a = dataManager;
            jC.b = fileManager;
            jC.c = resourceManager;
            jC.d = libraryManager;
            try {
                q.a(libraryManager, fileManager, dataManager);
                builder.buildBuiltInLibraryInformation();
                q.b(fileManager, dataManager, libraryManager, builder.getBuiltInLibraryManager());
            } finally {
                jC.a = editorData;
                jC.b = editorFiles;
                jC.c = editorResources;
                jC.d = editorLibraries;
            }
        }
        q.f();
        q.e();

        builder.maybeExtractAapt2();
        BuiltInLibraries.extractCompileAssets(progress);
        try {
            builder.compileResources();
        } catch (zy e) {
            return CompileErrorReport.resources(e.getMessage());
        }
        builder.generateViewBinding();
        try {
            builder.compileJavaCode();
        } catch (zy e) {
            return CompileErrorReport.java(e.getMessage());
        }
        return null;
    }
}
