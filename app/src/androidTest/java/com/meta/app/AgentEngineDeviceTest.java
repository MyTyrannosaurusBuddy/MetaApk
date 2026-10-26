package com.meta.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.*;
import com.meta.app.config.LlmConfig;
import com.meta.app.core.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Real ECJ/D8/ART + scripted local model; never uses saved API keys or user projects. */
@RunWith(AndroidJUnit4.class)
public class AgentEngineDeviceTest {
    private static final String ENTRY = "package com.meta.plugin;\n"
        + "public class DynamicPlugin implements PluginContract {\n"
        + " public String getTitle(){return Helper.title();}\n public void onDestroy(){}\n"
        + " public void onCreate(Context c, ViewGroup v, ApiBridge b){\n"
        + "  android.content.SharedPreferences p=c.getSharedPreferences(\"counter\",0);\n"
        + "  Button button=new Button(c); button.setText(Helper.title()+\" \"+p.getInt(\"count\",0));\n"
        + "  button.setOnClickListener(w->{int n=p.getInt(\"count\",0)+1; p.edit().putInt(\"count\",n).commit(); button.setText(Helper.title()+\" \"+n);});\n"
        + "  v.addView(button);\n }\n}";
    private static final String HELPER = "package com.meta.plugin;\nclass Helper {static String title(){return \"Before\";}}";

    /**
     * The web_search tool must return real results (or a clean no-results
     * message) over the device network - never an exception.
     */
    @Test public void webSearch_returnsResultsOnDeviceNetwork() {
        String result = WebSearch.search("android setTextSize java");
        assertNotNull(result);
        assertTrue(result, result.startsWith("1.") || result.startsWith("No results"));
    }

    /** The template repository must list and download over the device network. */
    @Test public void templateRepo_listsAndFetchesOnDeviceNetwork() {
        String list = TemplateRepo.list();
        assertTrue(list, list.contains("counter"));
        String source = TemplateRepo.fetch("counter");
        assertTrue(source, source.contains("DynamicPlugin"));
        assertTrue(TemplateRepo.fetch("nonexistent-template").startsWith("Error"));
    }

    /**
     * The click tool must inject a REAL touch, not performClick(): generated
     * apps (custom views, Canvas games - e.g. minesweeper) handle interaction
     * in onTouchEvent, a path performClick() never reaches. Runs on the main
     * thread: pressed-state animators require a Looper.
     */
    @Test public void clickTool_reachesOnTouchEventOfCustomView() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
            final AtomicInteger taps = new AtomicInteger();
            View custom = new View(ctx) {
                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        taps.incrementAndGet();
                    }
                    return true;
                }
            };
            FrameLayout root = new FrameLayout(ctx);
            root.addView(custom, new FrameLayout.LayoutParams(200, 200));
            int spec = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY);
            root.measure(spec, spec);
            root.layout(0, 0, 400, 400);
            JsonObject args = new JsonObject();
            args.addProperty("path", "0/0");
            PreviewUi.perform(root, "click", args);
            assertEquals("Custom-view onTouchEvent must receive the injected tap", 1, taps.get());

            // A regular Button not attached to any window: the injected tap
            // reaches onTouchEvent but its click action is queued (View.post ->
            // RunQueue) and would never run; the fallback must fire the listener.
            final AtomicInteger clicks = new AtomicInteger();
            android.widget.Button button = new android.widget.Button(ctx);
            button.setOnClickListener(v -> clicks.incrementAndGet());
            FrameLayout buttonRoot = new FrameLayout(ctx);
            buttonRoot.addView(button, new FrameLayout.LayoutParams(200, 200));
            buttonRoot.measure(spec, spec);
            buttonRoot.layout(0, 0, 400, 400);
            JsonObject buttonArgs = new JsonObject();
            buttonArgs.addProperty("path", "0/0");
            PreviewUi.perform(buttonRoot, "click", buttonArgs);
            assertEquals("Unattached button's click listener must fire via the fallback", 1, clicks.get());
        });
    }

    @Test public void multiFileAgent_canBuildInteractAndReviseWithoutLosingData() throws Exception {
        try (Fixture fixture = new Fixture(); ModelServer server = new ModelServer()) {
            server.handler = (index, request) -> {
                switch (index) {
                    case 0: return reply(tools(write("src/DynamicPlugin.java", ENTRY), write("src/Helper.java", HELPER),
                        write("src/alarms/Alarm.java", "package com.meta.plugin.alarms;\npublic class Alarm extends android.content.BroadcastReceiver {public void onReceive(android.content.Context c,android.content.Intent i){}}"),
                        write("src/Scheduler.java", "package com.meta.plugin;\nimport android.app.PendingIntent; import android.content.*; import com.meta.plugin.alarms.Alarm;\nclass Scheduler {static PendingIntent schedule(Context c){return PendingIntent.getBroadcast(c,1,new Intent(c,Alarm.class),PendingIntent.FLAG_IMMUTABLE);}}")));
                    case 1: return reply(tools(call("build", new JsonObject()), call("run_app", new JsonObject())));
                    case 2: return reply(tools(call("click", args("path", "0/0"))));
                    case 3: return reply(answer("Counter verified"));
                    case 4: {
                        JsonObject edit = args("path", "src/Helper.java");
                        edit.addProperty("old_string", "Before"); edit.addProperty("new_string", "After");
                        return reply(tools(call("edit_file", edit)));
                    }
                    default: return reply(answer("Revision verified"));
                }
            };
            MetaEngine engine = fixture.engine(server);
            Delivery first = new Delivery(fixture, engine, "original counter requirements");
            engine.process("original counter requirements", first);
            first.await();
            assertTrue(first.dump, first.dump.contains("Before 1"));
            assertTrue(first.previewDump, first.previewDump.contains("Before 1"));
            String lineage = engine.getActivePluginLineage();
            assertEquals(lineage, first.previewLineage);
            assertTrue(new File(fixture.getFilesDir(), "workspaces/" + lineage + "/src/Helper.java").isFile());
            Delivery revision = new Delivery(fixture, engine, "original counter requirements");
            engine.feedback("original counter requirements", "preserve saved counter", revision);
            revision.await();
            assertTrue(revision.dump, revision.dump.contains("After 1"));
            assertEquals(lineage, engine.getActivePluginLineage());
            String request = server.requests.get(4).toString();
            assertTrue(request.contains("original counter requirements"));
            assertTrue(request.contains("preserve saved counter"));
            assertTrue("Screenshot must reach the model", server.requests.get(2).toString().contains("data:image/jpeg;base64,"));
        }
    }

    @Test public void cancelledRequest_doesNotBlockOrDeliverIntoNextGeneration() throws Exception {
        try (Fixture fixture = new Fixture(); ModelServer server = new ModelServer()) {
            CountDownLatch firstRequest = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            server.handler = (index, request) -> {
                if (index == 0) { firstRequest.countDown(); release.await(30, TimeUnit.SECONDS); }
                return reply(answer("<java_code>" + ENTRY.replace("Helper.title()", "\"Next\"") + "</java_code>"));
            };
            MetaEngine engine = fixture.engine(server);
            Delivery old = new Delivery(fixture, engine, "old");
            engine.process("old", old);
            assertTrue(firstRequest.await(10, TimeUnit.SECONDS));
            engine.cancel();
            Delivery next = new Delivery(fixture, engine, "next");
            engine.process("next", next);
            try {
                next.await();
                assertTrue(next.dump.contains("Next"));
                assertEquals(1L, old.done.getCount());
            } finally { release.countDown(); }
        }
    }

    @Test public void processRestart_restoresWorkspaceAndToolHistory() throws Exception {
        try (Fixture fixture = new Fixture(); ModelServer server = new ModelServer()) {
            CountDownLatch checkpointReached = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            server.handler = (index, request) -> {
                if (index == 0) return reply(tools(write("src/DynamicPlugin.java", ENTRY), write("src/Helper.java", HELPER)));
                if (index == 1) { checkpointReached.countDown(); release.await(30, TimeUnit.SECONDS); }
                return reply(answer("Resume complete"));
            };
            MetaEngine engine = fixture.engine(server);
            engine.process("keep original requirements after restart", new Delivery(fixture, engine, "original"));
            assertTrue(checkpointReached.await(15, TimeUnit.SECONDS));
            engine.destroy();
            MetaEngine resumed = fixture.engine(server);
            // A resume after process death delivers with a NULL host-side
            // lastIntent (the field lives in memory); the engine must recover
            // the prompt from the persisted task instead of storing "".
            Delivery delivery = new Delivery(fixture, resumed, null);
            assertTrue(resumed.resumePendingTask(delivery));
            try {
                delivery.await();
                assertTrue(server.requests.get(2).toString().contains("keep original requirements after restart"));
                assertTrue(server.requests.get(2).toString().contains("tool_call_id"));
                assertTrue(delivery.dump.contains("Before 0"));
                assertEquals("Prompt must survive a resume via the persisted task",
                    "keep original requirements after restart", resumed.getActivePluginPrompt());
            } finally { release.countDown(); }
        }
    }

    @Test public void authenticationFailure_stopsWithoutRepeatingRequests() throws Exception {
        try (Fixture fixture = new Fixture(); ModelServer server = new ModelServer()) {
            server.handler = (index, request) -> new Reply(401, "invalid test key");
            MetaEngine engine = fixture.engine(server);
            Delivery delivery = new Delivery(fixture, engine, "auth test");
            engine.process("auth test", delivery);
            assertTrue(delivery.done.await(10, TimeUnit.SECONDS));
            assertNotNull(delivery.error.get());
            assertTrue(delivery.error.get().toString().contains("401"));
            assertEquals(1, server.requests.size());
        }
    }

    @Test public void textOnlyModel_continuesWithUiDumpWhenImageIsUnsupported() throws Exception {
        try (Fixture fixture = new Fixture(); ModelServer server = new ModelServer()) {
            server.handler = (index, request) -> {
                if (index == 0) return reply(tools(write("src/DynamicPlugin.java", ENTRY), write("src/Helper.java", HELPER)));
                if (index == 1) return reply(tools(call("run_app", new JsonObject())));
                if (index == 2) return new Reply(400, "model does not support image input");
                assertFalse(request.toString().contains("data:image/jpeg;base64,"));
                assertTrue(request.toString().contains("Before 0"));
                return reply(answer("Verified using UI dump"));
            };
            MetaEngine engine = fixture.engine(server);
            Delivery delivery = new Delivery(fixture, engine, "text-only preview");
            engine.process("text-only preview", delivery);
            delivery.await();
            assertTrue(delivery.dump.contains("Before 0"));
            assertEquals(4, server.requests.size());
        }
    }

    @Test public void initialAttachFailure_retriesInTheSameProject() throws Exception {
        try (Fixture fixture = new Fixture(); ModelServer server = new ModelServer()) {
            String broken = ENTRY.replace("  android.content.SharedPreferences", "  if(Helper.title().equals(\"Before\")) throw new IllegalStateException(\"initialization failed\");\n  android.content.SharedPreferences");
            server.handler = (index, request) -> {
                if (index == 0) return reply(tools(write("src/DynamicPlugin.java", broken), write("src/Helper.java", HELPER)));
                if (index == 2) {
                    JsonObject edit = args("path", "src/Helper.java");
                    edit.addProperty("old_string", "Before"); edit.addProperty("new_string", "After");
                    return reply(tools(call("edit_file", edit)));
                }
                return reply(answer("Application ready"));
            };
            MetaEngine engine = fixture.engine(server);
            AtomicReference<String> initialLineage = new AtomicReference<>();
            Delivery delivery = new Delivery(fixture, engine, "retry initialization") {
                @Override public void onPlugin(PluginContract plugin) {
                    if (initialLineage.get() == null) {
                        initialLineage.set(engine.getPluginLineage(plugin));
                        try { plugin.onCreate(scoped(plugin), new FrameLayout(fixture), new com.meta.app.bridge.ApiBridge(fixture)); }
                        catch (Throwable failure) {
                            engine.discardPlugin(plugin);
                            engine.retry("retry initialization", PluginRuntimeGuard.summarize(failure), this);
                            return;
                        }
                    }
                    super.onPlugin(plugin);
                }
            };
            engine.process("retry initialization", delivery);
            delivery.await();
            assertEquals(initialLineage.get(), engine.getActivePluginLineage());
            assertTrue(delivery.dump.contains("After 0"));
        }
    }

    private static class Delivery implements MetaEngine.Callback {
        final Fixture fixture; final MetaEngine engine; final String prompt;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        FrameLayout preview; PluginContract previewPlugin;
        String dump = "", previewDump = "", previewLineage;
        Delivery(Fixture fixture, MetaEngine engine, String prompt) { this.fixture=fixture; this.engine=engine; this.prompt=prompt; }
        public void onStatusUpdate(String status) { }
        public void onLlmToken(String text) { }
        public void onError(String text) { error.set(new AssertionError(text)); done.countDown(); }
        public void onPlugin(PluginContract plugin) {
            try {
                FrameLayout root = new FrameLayout(fixture);
                plugin.onCreate(scoped(plugin), root, new com.meta.app.bridge.ApiBridge(fixture));
                dump = PreviewUi.dump(root);
                engine.markPluginActive(plugin, prompt);
                engine.clearPendingTask();
                plugin.onDestroy();
            } catch (Throwable t) { error.set(t); }
            finally { done.countDown(); }
        }
        Context scoped(PluginContract plugin) {
            String lineage = engine.getPluginLineage(plugin);
            return new PluginDataContext(fixture, lineage,
                new File(fixture.getFilesDir(), "data/"+lineage), new File(fixture.getCacheDir(), "data/"+lineage));
        }
        public void onPreviewPlugin(PluginContract plugin, MetaEngine.PreviewReady ready) {
            preview = new FrameLayout(fixture); previewPlugin = plugin;
            previewLineage = engine.getPluginLineage(plugin);
            plugin.onCreate(scoped(plugin), preview, new com.meta.app.bridge.ApiBridge(fixture));
            inspect(ready);
        }
        public void onInspectPreview(String action, JsonObject args, MetaEngine.PreviewReady ready) {
            PreviewUi.perform(preview, action, args); inspect(ready);
        }
        void inspect(MetaEngine.PreviewReady ready) {
            preview.measure(android.view.View.MeasureSpec.makeMeasureSpec(640,1073741824), android.view.View.MeasureSpec.makeMeasureSpec(800,1073741824));
            preview.layout(0,0,640,800); previewDump=PreviewUi.dump(preview);
            ready.done(previewDump, PreviewUi.screenshot(preview));
        }
        public void onClosePreview() { if(previewPlugin!=null) {previewPlugin.onDestroy(); previewPlugin=null;} }
        void await() throws Exception {
            assertTrue("Agent delivery timed out", done.await(60, TimeUnit.SECONDS));
            if(error.get()!=null) throw new AssertionError("Agent failed", error.get());
        }
    }

    private static class Fixture extends ContextWrapper implements AutoCloseable {
        final String prefix = "agent_test_"+UUID.randomUUID().toString().replace("-","");
        final File root; final List<String> prefs = new ArrayList<>(); final List<MetaEngine> engines = new ArrayList<>();
        Fixture() { super(InstrumentationRegistry.getInstrumentation().getTargetContext()); root=new File(super.getCacheDir(),prefix); getFilesDir().mkdirs(); getCacheDir().mkdirs(); }
        public File getFilesDir() {return new File(root,"files");}
        public File getCacheDir() {return new File(root,"cache");}
        public SharedPreferences getSharedPreferences(String name,int mode) {prefs.add(prefix+name); return super.getSharedPreferences(prefix+name,mode);}
        MetaEngine engine(ModelServer server) {MetaEngine engine=new MetaEngine(this,new LlmConfig("http://127.0.0.1:"+server.server.getLocalPort()+"/v1","test","test","")); engines.add(engine); return engine;}
        public void close() {for(MetaEngine engine:engines) engine.destroy(); for(String name:prefs) deleteSharedPreferences(name); delete(root);}
        void delete(File file) {File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.setWritable(true);file.delete();}
    }

    private interface Handler { Reply respond(int index,JsonObject request) throws Exception; }
    private static class Reply {final int status;final String body;Reply(int status,String body){this.status=status;this.body=body;}}
    private static Reply reply(JsonObject body) {return new Reply(200,body.toString());}
    private static class ModelServer implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
        final ExecutorService threads=Executors.newCachedThreadPool();
        final List<JsonObject> requests=Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger next=new AtomicInteger(); volatile Handler handler;
        ModelServer() throws Exception { threads.execute(()->{while(!server.isClosed())try{Socket socket=server.accept();threads.execute(()->serve(socket));}catch(IOException ignored){return;}}); }
        void serve(Socket socket) {
            try(Socket connection=socket) {
                connection.setSoTimeout(15000); InputStream in=connection.getInputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();int tail=0;
                while(tail!=0x0d0a0d0a){int b=in.read();if(b<0)return;headers.write(b);tail=(tail<<8)|b;if(headers.size()>65536)return;}
                int length=0;for(String line:headers.toString("UTF-8").split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                byte[] body=new byte[length];new DataInputStream(in).readFully(body);JsonObject request=JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();requests.add(request);
                Reply reply=handler.respond(next.getAndIncrement(),request);byte[] data=reply.body.getBytes(StandardCharsets.UTF_8);OutputStream out=connection.getOutputStream();
                out.write(("HTTP/1.1 "+reply.status+" Test\r\nContent-Type: application/json\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(data);out.flush();
            } catch(Exception ignored) { }
        }
        public void close() throws IOException {server.close();threads.shutdownNow();}
    }
    private static JsonObject args(String key,String value) {JsonObject object=new JsonObject();object.addProperty(key,value);return object;}
    private static JsonObject write(String path,String source) {JsonObject args=args("path",path);args.addProperty("content",source);return call("write_file",args);}
    private static JsonObject call(String name,JsonObject args) {JsonObject call=args("id",UUID.randomUUID().toString());call.addProperty("type","function");JsonObject fn=args("name",name);fn.addProperty("arguments",args.toString());call.add("function",fn);return call;}
    private static JsonObject tools(JsonObject... calls) {JsonObject message=args("role","assistant");JsonArray array=new JsonArray();for(JsonObject call:calls)array.add(call);message.add("tool_calls",array);return response(message);}
    private static JsonObject answer(String text) {JsonObject message=args("role","assistant");message.addProperty("content",text);return response(message);}
    private static JsonObject response(JsonObject message) {JsonObject choice=new JsonObject();choice.add("message",message);JsonArray choices=new JsonArray();choices.add(choice);JsonObject response=new JsonObject();response.add("choices",choices);return response;}
}
