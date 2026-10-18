import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Inspects compiled injection contracts without claiming Mixin transformation. */
public class LegacyCraftingShapeCheck {
    static ClassNode node(String jar, String path) throws Exception {
        try (JarFile z = new JarFile(jar)) {
            ClassNode n = new ClassNode();
            new ClassReader(z.getInputStream(z.getJarEntry(path)).readAllBytes()).accept(n, 0);
            return n;
        }
    }
    static Map<String,Object> params(AnnotationNode a) {
        Map<String,Object> out = new HashMap<>();
        for (int i=0; i<a.values.size(); i+=2) out.put((String)a.values.get(i), a.values.get(i+1));
        return out;
    }
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static AbstractInsnNode nextOpcode(AbstractInsnNode i) {
        do { i=i.getNext(); } while (i != null && i.getOpcode() < 0);
        return i;
    }
    public static void main(String[] args) throws Exception {
        ClassNode target = node(args[0], "dev/ftb/mods/ftbquests/quest/task/ItemTask.class");
        String desc = "(Lnet/minecraft/world/item/ItemStack;)Z";
        require(target.methods.stream().filter(m -> m.name.equals("test") && m.desc.equals(desc)).count()==1,
                "ItemTask.test target shape changed");
        ClassNode mixin = node(args[1], "org/gtnhmigration/compat/mixin/LegacyCraftingItemTaskMixin.class");
        FieldNode field = mixin.fields.stream().filter(f -> f.name.equals("matchComponents")).findFirst().orElseThrow();
        require(target.fields.stream().anyMatch(f -> f.name.equals(field.name) && f.desc.equals(field.desc)),
                "Missing matchComponents shadow field");
        require(field.visibleAnnotations.stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;")),
                "matchComponents must shadow FTB state");
        int shadows=0;
        MethodNode handler=null;
        for (MethodNode method : mixin.methods) {
            if (method.name.equals("gtnhMigration$legacyCraftingMatch")) handler=method;
            if (method.visibleAnnotations != null && method.visibleAnnotations.stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;"))) {
                require(target.methods.stream().anyMatch(m -> m.name.equals(method.name) && m.desc.equals(method.desc)),
                        "Missing shadow method: " + method.name);
                shadows++;
            }
        }
        require(handler != null && shadows==3, "Expected crafting handler and three shadow methods");
        AnnotationNode annotation=handler.visibleAnnotations.stream().filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
        Map<String,Object> inject=params(annotation);
        require(List.of("test").equals(inject.get("method")) && Boolean.TRUE.equals(inject.get("cancellable"))
                && Boolean.FALSE.equals(inject.get("remap")) && Integer.valueOf(1).equals(inject.get("require")), "Unsafe injection flags");
        AnnotationNode at=(AnnotationNode)((List<?>)inject.get("at")).getFirst();
        require("HEAD".equals(params(at).get("value")), "Expected HEAD injection");
        boolean marker=false,earlyReturn=false; int helpers=0;
        for (AbstractInsnNode instruction : handler.instructions) {
            if (instruction instanceof LdcInsnNode l && "gtnh_legacy_crafting".equals(l.cst)) marker=true;
            if (instruction instanceof MethodInsnNode m) {
                require(!m.name.equals("test"), "Recursive ItemTask.test call in crafting injection");
                if (m.name.equals("hasTag")) {
                    AbstractInsnNode branch=nextOpcode(m);
                    earlyReturn=branch instanceof JumpInsnNode && branch.getOpcode()==Opcodes.IFNE
                            && nextOpcode(branch).getOpcode()==Opcodes.RETURN;
                }
                if (m.owner.equals("org/gtnhmigration/compat/LegacyCraftingStackMatcher") && m.name.equals("override")) helpers++;
            }
        }
        require(marker && earlyReturn && helpers==1, "Missing opt-in early return or exact single matcher call");
        System.out.println("{\"test\":\"compiled_legacy_crafting_mixin_shape\",\"passed\":true,\"unmarked_early_return\":true,\"recursive_test_call\":false,\"actual_mixin_transformation_tested\":false,\"game_runtime_verified\":false}");
    }
}
