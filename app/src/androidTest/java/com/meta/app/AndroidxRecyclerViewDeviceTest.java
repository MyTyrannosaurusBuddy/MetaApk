package com.meta.app;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.meta.app.core.CodeCompiler;
import com.meta.app.core.DexTransformer;
import com.meta.app.core.PluginLoader;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertTrue;

/**
 * androidx availability for generated apps: a plugin using
 * androidx.recyclerview.RecyclerView must compile (the dynamic compile
 * classpath carries androidx now) AND load (the host APK bundles it).
 */
@RunWith(AndroidJUnit4.class)
public class AndroidxRecyclerViewDeviceTest {

    @Test
    public void recyclerViewPluginCompilesAndLoads() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String pluginId = "plugin_androidx_test";
        File srcDir = new File(context.getCacheDir(), "androidx-test-src");
        srcDir.mkdirs();
        File src = new File(srcDir, "DynamicPlugin.java");
        try (FileOutputStream out = new FileOutputStream(src)) {
            out.write(DYNAMIC_PLUGIN.getBytes("UTF-8"));
        }

        CodeCompiler compiler = new CodeCompiler(context);
        CodeCompiler.CompileResult compile = compiler.compileWorkspace(pluginId, srcDir);
        assertTrue("RecyclerView plugin must compile now:\n" + compile.errorMessage,
            compile.success);

        DexTransformer transformer = new DexTransformer(context);
        DexTransformer.TransformResult transform =
            transformer.transform(pluginId, compile.classesDir);
        assertTrue("Dex transform failed: " + transform.errorMessage, transform.success);

        PluginLoader loader = new PluginLoader(context);
        PluginLoader.LoadResult load = loader.loadPlugin(pluginId, transform.dexFile);
        assertTrue("RecyclerView plugin must load (host bundles androidx): "
            + load.errorMessage, load.success);
        loader.unloadPlugin(pluginId);
        compiler.cleanPlugin(pluginId);
    }

    private static final String DYNAMIC_PLUGIN = ""
        + "package com.meta.plugin;\n"
        + "import android.content.Context;\n"
        + "import android.view.ViewGroup;\n"
        + "import android.widget.TextView;\n"
        + "import androidx.recyclerview.widget.LinearLayoutManager;\n"
        + "import androidx.recyclerview.widget.RecyclerView;\n"
        + "import com.meta.app.core.PluginContract;\n"
        + "import com.meta.app.bridge.ApiBridge;\n"
        + "public class DynamicPlugin implements PluginContract {\n"
        + "    public String getTitle() { return \"AndroidxList\"; }\n"
        + "    public void onCreate(Context c, ViewGroup container, ApiBridge bridge) {\n"
        + "        RecyclerView list = new RecyclerView(c);\n"
        + "        list.setLayoutManager(new LinearLayoutManager(c));\n"
        + "        final String[] items = {\"a\", \"b\", \"c\", \"d\", \"e\"};\n"
        + "        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {\n"
        + "            @Override\n"
        + "            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {\n"
        + "                return new RecyclerView.ViewHolder(new TextView(c)) {};\n"
        + "            }\n"
        + "            @Override\n"
        + "            public void onBindViewHolder(RecyclerView.ViewHolder h, int i) {\n"
        + "                ((TextView) h.itemView).setText(items[i]);\n"
        + "            }\n"
        + "            @Override\n"
        + "            public int getItemCount() { return items.length; }\n"
        + "        });\n"
        + "        container.addView(list, new ViewGroup.LayoutParams(-1, -1));\n"
        + "    }\n"
        + "    public void onDestroy() {}\n"
        + "}\n";
}
