package com.meta.app;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.Gson;
import com.meta.app.core.MetaEngine;
import com.meta.app.core.PluginArchive;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Deleting an app must invalidate every persisted reference to it: the
 * archive entry itself AND a pending task pointing at its lineage /
 * replacement id. Otherwise a later launch would resume the task, write
 * into the deleted workspace and re-register the deleted app.
 */
@RunWith(AndroidJUnit4.class)
public class DeleteInvalidatesPendingDeviceTest {

    @Test
    public void deleteClearsPendingTaskReferencingTheApp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String pluginId = "plugin_deltest";
        String lineage = "lineage_deltest";

        // A fake workspace so the deletion has lineage data to remove.
        File workspace = new File(context.getFilesDir(), "workspaces/" + lineage + "/src");
        workspace.mkdirs();
        new File(workspace, "DynamicPlugin.java").createNewFile();

        PluginArchive archive = new PluginArchive(context);
        archive.addFront(pluginId, "DeleteMe", "p", System.currentTimeMillis(), lineage);

        // A persisted pending task whose lineage and replacement target both
        // point at the app being deleted.
        MetaEngine.PendingTask task = new MetaEngine.PendingTask();
        task.type = "FEEDBACK";
        task.originalIntent = "p";
        task.errorMessage = "";
        task.sessionPrompt = "";
        task.feedback = null;
        task.replacingId = pluginId;
        task.lineage = lineage;
        task.timestamp = System.currentTimeMillis();
        context.getSharedPreferences("meta_pending_task", Context.MODE_PRIVATE)
            .edit().putString("task", new Gson().toJson(task)).commit();

        MetaEngine engine = new MetaEngine(context);
        assertTrue("Pending task not persisted", engine.hasPendingTask());
        engine.deleteArchivedPlugin(pluginId);

        assertNull("Archive entry survived deletion", archive.find(pluginId));
        assertFalse("Pending task survived deletion", engine.hasPendingTask());
        assertFalse("Workspace survived deletion", workspace.exists());
        engine.destroy();
    }
}
