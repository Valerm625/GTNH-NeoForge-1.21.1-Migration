package org.gtnhmigration.audit;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.*;
import org.openjdk.nashorn.api.scripting.*;

/** Real ASM/Nashorn test of instruction preservation, not a model of rendering. */
public final class GtceuClientIsolationCheck {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        var engine = new NashornScriptEngineFactory().getScriptEngine("--language=es6");
        engine.eval(Files.readString(Path.of(args[1])));
        var transforms = (ScriptObjectMirror) engine.eval("initializeCoreMod()");
        int methods = 0;
        try (ZipFile jar = new ZipFile(args[0])) {
            for (String key : transforms.keySet()) {
                var definition = (ScriptObjectMirror) transforms.get(key);
                var target = (ScriptObjectMirror) definition.get("target");
                String name = target.get("name").toString().replace('.', '/');
                byte[] bytes = jar.getInputStream(jar.getEntry(name+".class")).readAllBytes();
                ClassNode before = read(bytes), after = read(bytes);
                var transform = (ScriptObjectMirror) definition.get("transformer");
                transform.call(null, after);
                boolean fancy = key.contains("fancy");
                String changed = fancy ? "createUIWidget" : "<init>";
                MethodNode original = method(before, changed), edited = method(after, changed);
                MethodNode helper = after.methods.stream().filter(m -> m.name.startsWith("gtnhMigration$")).findFirst().orElseThrow();
                require(after.methods.size() == before.methods.size()+1, "Only one helper added");
                AbstractInsnNode first, end;
                if (fancy) {
                    var guard = (JumpInsnNode) call(original, "isRemote").getNext(); first = guard.getNext(); end = guard.label;
                    require(trace(original.instructions.getFirst(), guard.getNext()).equals(trace(edited.instructions.getFirst(), call(edited,"isRemote").getNext().getNext())), "Original scene guard and group retained");
                    require(trace(end, null).equals(trace(((JumpInsnNode)call(edited,"isRemote").getNext()).label, null)), "Original group return retained");
                } else {
                    first = call(original, "registerDefaultState").getNext();
                    end = Arrays.stream(original.instructions.toArray()).filter(i -> i.getOpcode()==Opcodes.RETURN).findFirst().orElseThrow();
                    require(trace(original.instructions.getFirst(), first).equals(trace(edited.instructions.getFirst(), call(edited,"registerDefaultState").getNext())), "All common lamp initialization retained");
                    require(call(edited,"isClient").owner.equals("net/neoforged/api/distmarker/Dist"), "Physical client guard");
                }
                require(trace(first,end).equals(trace(helper.instructions.getFirst(), helper.instructions.getLast())), "Every client instruction and operand retained in order");
                require(helper.visibleAnnotations.stream().anyMatch(a -> a.desc.equals("Lnet/neoforged/api/distmarker/OnlyIn;")), "Helper annotated");
                for (MethodNode m : before.methods) {
                    if (m == original) continue;
                    var n = after.methods.stream().filter(x -> x.name.equals(m.name) && x.desc.equals(m.desc)).findFirst().orElseThrow();
                    require(whole(m).equals(whole(n)), "Unrelated method changed: "+m.name);
                }
                for (MethodNode m : after.methods) {
                    if ((m.access & (Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE)) != 0) continue;
                    new Analyzer<>(new BasicInterpreter()).analyze(after.name,m); methods++;
                }
                ClassNode bad = read(bytes); method(bad, changed).name = "unexpectedMethod";
                boolean rejected = false;
                try { transform.call(null,bad); } catch (RuntimeException expected) { rejected = true; }
                require(rejected, "Unknown target layout accepted");
                rejected = false;
                try { transform.call(null,after); } catch (RuntimeException expected) { rejected = true; }
                require(rejected, "Repeated transformation accepted");
            }
        }
        String report = "{\"passed\":true,\"assertions\":"+assertions+",\"asm_methods_checked\":"+methods+",\"original_client_instructions_preserved\":true,\"negative_layout_checks\":4,\"visual_rendering_verified\":false}";
        Files.writeString(Path.of(args[2]),report+"\n"); System.out.println(report);
    }
    private static ClassNode read(byte[] bytes) { var c=new ClassNode(); new ClassReader(bytes).accept(c,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES); return c; }
    private static MethodNode method(ClassNode c,String n) { return c.methods.stream().filter(m -> m.name.equals(n)).findFirst().orElseThrow(); }
    private static MethodInsnNode call(MethodNode m,String n) { return (MethodInsnNode)Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof MethodInsnNode c && c.name.equals(n)).findFirst().orElseThrow(); }
    private static String trace(AbstractInsnNode first,AbstractInsnNode end) {
        Textifier text = new Textifier(); var visitor=new TraceMethodVisitor(text);
        for(var i=first;i!=end;i=i.getNext()) if(i.getType()!=AbstractInsnNode.FRAME && i.getType()!=AbstractInsnNode.LINE) i.accept(visitor);
        var output=new StringWriter(); text.print(new PrintWriter(output)); return output.toString();
    }
    private static String whole(MethodNode m) { Textifier t=new Textifier(); m.accept(new TraceMethodVisitor(t)); var w=new StringWriter(); t.print(new PrintWriter(w)); return w.toString(); }
    private static void require(boolean c,String m) { assertions++; if(!c)throw new AssertionError(m); }
}
