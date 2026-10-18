package org.gtnhmigration.audit;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.lang.reflect.Proxy;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.*;
import org.openjdk.nashorn.api.scripting.*;

/** Checks actual transformations and actual distribution classes, without a world. */
public final class LibraryClientCallbacksCheck {
    private static final String ONLY_IN = "Lnet/neoforged/api/distmarker/OnlyIn;";
    private static final Map<String,List<String>> CALLBACKS = Map.of(
        "com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup", List.of("drawOverlay"),
        "com.lowdragmc.lowdraglib.gui.widget.PhantomFluidWidget", List.of("drawInBackground"),
        "com.lowdragmc.lowdraglib.gui.widget.SceneWidget", List.of("lambda$setBeforeWorldRender$0"),
        "com.gregtechceu.gtceu.api.fluid.FluidBuilder", List.of("lambda$makeFluidType$14", "lambda$build$10", "lambda$build$5"),
        "com.gregtechceu.gtceu.data.block.GTMaterialBlocks", List.of("lambda$registerOreBlock$12", "lambda$registerMaterialBlock$5"),
        "com.gregtechceu.gtceu.data.item.GTMaterialItems", List.of("lambda$generateArmor$14", "lambda$generateArmor$12", "lambda$generateMaterialItem$4"),
        "com.gregtechceu.gtceu.data.item.GTItems", List.of("lambda$addArmorClientExtensions$177", "lambda$modelPredicate$174", "lambda$static$105", "lambda$static$104"));
    private static int assertions;

    public static void main(String[] args) throws Exception {
        var engine = new NashornScriptEngineFactory().getScriptEngine("--language=es6");
        engine.eval(Files.readString(Path.of(args[2])));
        var transforms = (ScriptObjectMirror) engine.eval("initializeCoreMod()");
        require(transforms.size() == 8, "Expected eight version-pinned classes");
        int methods = 0, annotations = 0, negatives = 0;
        TreeSet<String> expectedAccessors = new TreeSet<>();
        try (var gt = new ZipFile(args[0]); var ld = new ZipFile(args[1])) {
            for (ZipFile jar : List.of(gt, ld)) {
                for (var entries = jar.entries(); entries.hasMoreElements();) {
                    var entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class")) continue;
                    var node = read(jar.getInputStream(entry).readAllBytes());
                    if (node.visibleAnnotations != null && node.visibleAnnotations.stream().anyMatch(a ->
                        a.desc.equals("Lcom/lowdragmc/lowdraglib/gui/editor/annotation/ConfigAccessor;")))
                        expectedAccessors.add(node.name.replace('/', '.'));
                }
            }
            require(expectedAccessors.size() == 23, "Re-audit changed accessor inventory");
            for (String key : transforms.keySet()) {
                var definition = (ScriptObjectMirror) transforms.get(key);
                String name = ((ScriptObjectMirror) definition.get("target")).get("name").toString();
                String entry = name.replace('.', '/')+".class";
                ZipFile jar = name.startsWith("com.lowdragmc.") ? ld : gt;
                byte[] bytes = jar.getInputStream(jar.getEntry(entry)).readAllBytes();
                ClassNode before = read(bytes), after = read(bytes);
                var transform = (ScriptObjectMirror) definition.get("transformer");
                transform.call(null, after);
                for (MethodNode m : after.methods) {
                    if ((m.access & (Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE)) == 0) {
                        new Analyzer<>(new BasicInterpreter()).analyze(after.name, m); methods++;
                    }
                }
                boolean rejected = false;
                try { transform.call(null, after); } catch (RuntimeException expected) { rejected = true; }
                require(rejected, "Repeated transformation accepted "+name); negatives++;
                ClassNode bad = read(bytes);
                String targetMethod = CALLBACKS.containsKey(name) ? CALLBACKS.get(name).getFirst() : "checkNoArgsConstructor";
                method(bad, targetMethod).name = "unexpectedMethod";
                rejected = false;
                try { transform.call(null, bad); } catch (RuntimeException expected) { rejected = true; }
                require(rejected, "Unknown method layout accepted "+name); negatives++;

                // Remove precisely the permitted changes, then compare the entire
                // class: fields, method bodies, descriptors, annotations and handles.
                after = read(bytes); transform.call(null, after);
                for (String callback : CALLBACKS.getOrDefault(name, List.of())) {
                    MethodNode m = method(after, callback);
                    var a = m.visibleAnnotations.stream().filter(x -> x.desc.equals(ONLY_IN)).findFirst().orElseThrow();
                    var dist = (String[]) a.values.get(1);
                    require(a.values.get(0).equals("value") && Arrays.equals(dist,
                        new String[]{"Lnet/neoforged/api/distmarker/Dist;", "CLIENT"}), "Wrong side annotation");
                    m.visibleAnnotations.remove(a); annotations++;
                }
                if (name.endsWith("AnnotationDetector")) {
                    MethodNode m = method(after, "checkNoArgsConstructor");
                    JumpInsnNode branch = removeGuard(m);
                    var check = Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof TypeInsnNode t &&
                        t.getOpcode() == Opcodes.INSTANCEOF && t.desc.endsWith("/LDLRegisterClient")).findFirst().orElseThrow();
                    require(branch.label == ((JumpInsnNode)check.getNext()).label, "Constructor check must remain after server branch");
                }
                if (name.endsWith("FluidBuilder")) {
                    MethodNode m = method(after, "makeFluidType");
                    JumpInsnNode branch = removeGuard(m);
                    require(branch.label.getPrevious() instanceof MethodInsnNode call && call.owner.equals("com/tterrag/registrate/util/OneTimeEventReceiver") &&
                        call.name.equals("addModListener"), "Guard must end exactly after client listener registration");
                    m.instructions.remove(branch.label);
                    bad = read(bytes);
                    var ldc = Arrays.stream(method(bad, "makeFluidType").instructions.toArray()).filter(i -> i instanceof LdcInsnNode).findFirst().orElseThrow();
                    ((LdcInsnNode)ldc).cst = Type.getType("Ljava/lang/Object;"); rejected = false;
                    try { transform.call(null, bad); } catch (RuntimeException expected) { rejected = true; }
                    require(rejected, "Unexpected client event accepted"); negatives++;
                }
                require(whole(before).equals(whole(after)), "Original class content changed: "+name);
            }
        }
        require(annotations == 15, "Missing client callback");
        JsonObject report = new JsonObject();
        report.addProperty("passed", true); report.addProperty("assertions", assertions);
        report.addProperty("asm_methods_checked", methods); report.addProperty("negative_layout_checks", negatives);
        report.addProperty("client_callbacks_annotated", annotations); report.addProperty("physical_side_guards", 2);
        report.addProperty("original_class_content_preserved_except_annotations_and_guards", true);
        report.add("expected_configurators", new Gson().toJsonTree(expectedAccessors));
        report.addProperty("visual_rendering_verified", false);
        Files.writeString(Path.of(args[3]), new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        System.out.println(report);
    }

    /** Called after real mod registration in both server startup and client datagen. */
    public static JsonObject verifyRuntime(boolean client, Path specification) throws Exception {
        JsonObject expected = JsonParser.parseString(Files.readString(specification)).getAsJsonObject();
        TreeSet<String> wanted = new TreeSet<>();
        for (var name : expected.getAsJsonArray("expected_configurators")) wanted.add(name.getAsString());
        Class<?> detector = Class.forName("com.lowdragmc.lowdraglib.gui.editor.runtime.AnnotationDetector");
        List<?> accessors = (List<?>)detector.getField("CONFIGURATOR_ACCESSORS").get(null);
        TreeSet<String> actual = new TreeSet<>();
        for (Object accessor : accessors) actual.add(accessor.getClass().getName());
        require(actual.equals(wanted) && accessors.size() == wanted.size(), "LDLib configurator inventory differs: "+actual);
        int callbacks = 0;
        for (var target : CALLBACKS.entrySet()) {
            Class<?> type = Class.forName(target.getKey());
            var methods = List.of(type.getDeclaredMethods()); // Force all descriptors to resolve.
            for (String name : target.getValue()) {
                require(methods.stream().anyMatch(m -> m.getName().equals(name)) == client, "Incorrect callback side: "+name);
                callbacks++;
            }
        }
        // Shared APIs must still exist on both distributions.
        Class<?> scene = Class.forName("com.lowdragmc.lowdraglib.gui.widget.SceneWidget");
        scene.getDeclaredMethod("setBeforeWorldRender", java.util.function.Consumer.class);
        scene.getDeclaredMethod("setRenderedCore", Collection.class,
            Class.forName("com.lowdragmc.lowdraglib.client.scene.ISceneBlockRenderHook"));
        var predicate = detector.getDeclaredMethod("checkNoArgsConstructor", Object.class, Class.class);
        require((boolean)predicate.invoke(null, new Object(), String.class), "Shared no-args constructor rejected");
        require(!(boolean)predicate.invoke(null, new Object(), Integer.class), "Missing no-args constructor accepted");
        int modFilters = 0;
        for (String annotation : client ? List.of("LDLRegister", "LDLRegisterClient") : List.of("LDLRegister")) {
            Class<?> type = Class.forName("com.lowdragmc.lowdraglib.gui.editor.annotation."+annotation);
            for (String mod : List.of("", "ldlib", "gtnh_nonexistent_mod_for_audit")) {
                Object marker = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> method.getName().equals("modID") ? mod : method.getDefaultValue());
                require((boolean)predicate.invoke(null, marker, String.class) == !mod.equals("gtnh_nonexistent_mod_for_audit"), "Mod dependency filter changed");
                require(!(boolean)predicate.invoke(null, marker, Integer.class), "Constructor filter changed"); modFilters += 2;
            }
        }
        JsonObject report = new JsonObject(); report.addProperty("passed", true);
        report.addProperty("client_distribution", client); report.addProperty("callback_side_checks", callbacks);
        report.add("registered_configurators", new Gson().toJsonTree(actual));
        report.addProperty("mod_dependency_filter_checks", modFilters); report.addProperty("shared_scene_apis_retained", true);
        return report;
    }

    private static JumpInsnNode removeGuard(MethodNode m) {
        var guards = Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof FieldInsnNode f &&
            f.getOpcode() == Opcodes.GETSTATIC && f.owner.equals("net/neoforged/fml/loading/FMLEnvironment") && f.name.equals("dist")).toList();
        require(guards.size() == 1, "Exactly one physical-side guard");
        var field = guards.getFirst(); var call = (MethodInsnNode)field.getNext(); var branch = (JumpInsnNode)call.getNext();
        require(call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.owner.equals("net/neoforged/api/distmarker/Dist") &&
            call.name.equals("isClient") && call.desc.equals("()Z") && branch.getOpcode() == Opcodes.IFEQ, "Physical client condition changed");
        require(branch.getNext().getOpcode() == Opcodes.ALOAD, "Guard inserted after argument evaluation");
        m.instructions.remove(field); m.instructions.remove(call); m.instructions.remove(branch); return branch;
    }
    private static ClassNode read(byte[] bytes) { var c = new ClassNode(); new ClassReader(bytes).accept(c, ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES); return c; }
    private static MethodNode method(ClassNode c, String n) { return c.methods.stream().filter(m -> m.name.equals(n)).findFirst().orElseThrow(); }
    private static String whole(ClassNode c) { var w = new StringWriter(); c.accept(new TraceClassVisitor(new PrintWriter(w))); return w.toString(); }
    private static void require(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
