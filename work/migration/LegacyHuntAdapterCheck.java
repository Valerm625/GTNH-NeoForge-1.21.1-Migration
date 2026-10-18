import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import com.google.gson.JsonParser;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.MushroomCow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.gtnhmigration.compat.LegacyHuntEntityMatcher;

/** Compiled predicate tests plus actual upstream bytecode contracts, without launching a world. */
public final class LegacyHuntAdapterCheck {
    static int cases=0;
    static void require(boolean value, String message) {
        cases++;
        if (!value) throw new AssertionError(message);
    }
    static void rejects(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalStateException expected) { require(true,message); return; }
        throw new AssertionError("Unsupported policy accepted: " + message);
    }
    static void verifyRegistration(String json) {
        var config=JsonParser.parseString(json).getAsJsonObject();
        require(config.get("package").getAsString().equals("org.gtnhmigration.compat.mixin"),"actual JAR mixin package");
        int registered=0;
        for (var entry:config.getAsJsonArray("mixins")) if (entry.getAsString().equals("LegacyHuntTaskMixin")) registered++;
        require(registered==1,"hunt mixin must be registered once in actual JAR config");
    }
    static ClassNode node(JarFile jar, String name) throws Exception {
        var entry=jar.getJarEntry(name + ".class");
        if (entry == null) throw new AssertionError("Missing real class " + name);
        var n=new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(n,0);
        return n;
    }
    static Map<String,Object> params(AnnotationNode annotation) {
        Map<String,Object> result=new HashMap<>();
        for (int i=0;i<annotation.values.size();i+=2) result.put((String)annotation.values.get(i),annotation.values.get(i+1));
        return result;
    }
    static String registeredClass(ClassNode registry, String id) {
        String precedingClass=null;
        for (MethodNode method:registry.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (AbstractInsnNode instruction:method.instructions) {
                if (instruction instanceof LdcInsnNode ldc) {
                    if (ldc.cst instanceof Type type) precedingClass=type.getInternalName();
                    else if (id.equals(ldc.cst)) return precedingClass;
                }
            }
        }
        throw new AssertionError("No legacy entity registration for " + id);
    }
    public static void main(String[] args) throws Exception {
        require(LegacyHuntEntityMatcher.matches("cow",Cow.class,"cow",Cow.class,false),"exact cow");
        require(!LegacyHuntEntityMatcher.matches("cow",Cow.class,"mooshroom",MushroomCow.class,false),"exact must reject subtype ID");
        require(LegacyHuntEntityMatcher.matches("cow",Cow.class,"mooshroom",MushroomCow.class,true),"subtype must count real mooshroom");
        require(!LegacyHuntEntityMatcher.matches("cow",Cow.class,"pig",Pig.class,true),"unrelated real entity");
        require(!LegacyHuntEntityMatcher.matches("cow",null,"cow",Cow.class,false),"missing target");
        require(!LegacyHuntEntityMatcher.matches("cow",Cow.class,null,Cow.class,true),"unregistered subject");
        require(!LegacyHuntEntityMatcher.matches("cow",Cow.class,"cow",null,true),"missing subject class");
        require(!LegacyHuntEntityMatcher.matches(null,Cow.class,"cow",Cow.class,false),"missing target ID");
        require(LegacyHuntEntityMatcher.override(false,false,null,true,"ordinary name","{Health:1f}",null,null)==null,
                "ordinary FTB tasks must retain their filters and original matcher");
        require(LegacyHuntEntityMatcher.override(false,false,"minecraft:zombie",false,"","","minecraft:zombie",Cow.class)==null,
                "ordinary FTB unreviewed entity is not intercepted");
        require(LegacyHuntEntityMatcher.override(true,false,"minecraft:cow",false,"","","minecraft:mooshroom",MushroomCow.class),
                "actual cow class mapping counts real mooshroom in subtype mode");
        require(!LegacyHuntEntityMatcher.override(false,true,"minecraft:cow",false,"","","minecraft:mooshroom",MushroomCow.class),
                "exact marker does not count mooshroom");
        Map<String,Class<?>> modernClasses=Map.of("cow",Cow.class,"pig",Pig.class,"wither",WitherBoss.class,"ender_dragon",EnderDragon.class);
        for (var entry:modernClasses.entrySet()) {
            String id="minecraft:"+entry.getKey();
            require(LegacyHuntEntityMatcher.override(true,false,id,false,"","",id,entry.getValue()),"real target class mapping "+id);
            for (var other:modernClasses.entrySet()) if (!other.getKey().equals(entry.getKey())) {
                require(!LegacyHuntEntityMatcher.override(true,false,id,false,"","","minecraft:"+other.getKey(),other.getValue()),
                        "target class rejects unrelated real class "+id+" / "+other.getKey());
            }
        }
        require(!LegacyHuntEntityMatcher.override(true,false,"minecraft:cow",false,"","",null,Cow.class),"registered entity type is required");
        rejects(()->LegacyHuntEntityMatcher.override(true,true,"minecraft:cow",false,"","","minecraft:cow",Cow.class),"conflicting markers");
        rejects(()->LegacyHuntEntityMatcher.override(true,false,null,false,"","","minecraft:cow",Cow.class),"missing target");
        rejects(()->LegacyHuntEntityMatcher.override(true,false,"minecraft:cow",true,"","","minecraft:cow",Cow.class),"entity type tag");
        rejects(()->LegacyHuntEntityMatcher.override(true,false,"minecraft:cow",false,"name","","minecraft:cow",Cow.class),"custom name");
        rejects(()->LegacyHuntEntityMatcher.override(true,false,"minecraft:cow",false,"","{Health:1f}","minecraft:cow",Cow.class),"NBT filter");
        rejects(()->LegacyHuntEntityMatcher.override(true,false,"minecraft:chicken",false,"","","minecraft:cow",Cow.class),"unreviewed target");
        Map<String,String> oldNames=new HashMap<>();
        for (String line:Files.readAllLines(Path.of(args[1]))) {
            if (line.startsWith("CL: ")) {var fields=line.split(" ");oldNames.put(fields[2],fields[1]);}
        }
        try (var legacy=new JarFile(args[0]);var modern=new JarFile(args[2]);
             var ftb=new JarFile(args[3]);var compat=new JarFile(args[4])) {
            var configEntry=compat.getJarEntry("gtnh_migration_compat.mixins.json");
            require(configEntry!=null,"actual JAR mixin configuration exists");
            String config=new String(compat.getInputStream(configEntry).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            verifyRegistration(config);
            var metadataEntry=compat.getJarEntry("META-INF/neoforge.mods.toml");
            require(metadataEntry!=null,"actual JAR mod metadata exists");
            String metadata=new String(compat.getInputStream(metadataEntry).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            require(java.util.regex.Pattern.compile("\\[\\[mixins\\]\\]\\s*config\\s*=\\s*\"gtnh_migration_compat\\.mixins\\.json\"")
                    .matcher(metadata).find(),"actual JAR enables checked mixin configuration");
            boolean missingRegistrationRejected=false;
            try { verifyRegistration(config.replace("LegacyHuntTaskMixin","RemovedHuntMixin")); }
            catch (AssertionError expected) { missingRegistrationRejected=true; }
            require(missingRegistrationRejected,"missing hunt registration mutation must fail");
            ClassNode wrongMapping=node(compat,"org/gtnhmigration/compat/LegacyHuntEntityMatcher");
            int mutatedClasses=0;
            for (MethodNode method:wrongMapping.methods) if (method.name.equals("override")) {
                for (AbstractInsnNode instruction:method.instructions) {
                    if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Type type
                            && type.getInternalName().equals("net/minecraft/world/entity/animal/Cow")) {
                        ldc.cst=Type.getType(Pig.class);
                        mutatedClasses++;
                    }
                }
            }
            require(mutatedClasses==1,"actual matcher contains exactly one reviewed Cow class literal");
            var writer=new ClassWriter(0);wrongMapping.accept(writer);byte[] mutatedBytes=writer.toByteArray();
            Class<?> mutatedMatcher=new ClassLoader(LegacyHuntAdapterCheck.class.getClassLoader()) {
                Class<?> loadMutant() { return defineClass("org.gtnhmigration.compat.LegacyHuntEntityMatcher",mutatedBytes,0,mutatedBytes.length); }
            }.loadMutant();
            var mutatedOverride=mutatedMatcher.getMethod("override",boolean.class,boolean.class,String.class,
                    boolean.class,String.class,String.class,String.class,Class.class);
            require(!Boolean.TRUE.equals(mutatedOverride.invoke(null,true,false,"minecraft:cow",false,"","","minecraft:mooshroom",MushroomCow.class)),
                    "deliberately wrong Cow-to-Pig class mapping fails the real subtype fixture");
            String oldCow=oldNames.get("net/minecraft/entity/passive/EntityCow");
            String oldMooshroom=oldNames.get("net/minecraft/entity/passive/EntityMooshroom");
            require(node(legacy,oldMooshroom).superName.equals(oldCow),"legacy mooshroom ancestry");
            require(node(modern,"net/minecraft/world/entity/animal/MushroomCow").superName.equals("net/minecraft/world/entity/animal/Cow"),"modern mooshroom ancestry");
            ClassNode registry=node(legacy,oldNames.get("net/minecraft/entity/EntityList"));
            Map<String,String> reviewed=Map.of("Cow","passive/EntityCow","Pig","passive/EntityPig",
                "WitherBoss","boss/EntityWither","EnderDragon","boss/EntityDragon");
            for (var pair:reviewed.entrySet()) require(registeredClass(registry,pair.getKey()).equals(oldNames.get("net/minecraft/entity/"+pair.getValue())),"legacy registry class "+pair.getKey());
            ClassNode target=node(ftb,"dev/ftb/mods/ftbquests/quest/task/KillTask");
            String descriptor="(Lnet/minecraft/world/entity/LivingEntity;)Z";
            require(target.methods.stream().filter(m->m.name.equals("match") && m.desc.equals(descriptor)).count()==1,"real FTB match signature");
            ClassNode mixin=node(compat,"org/gtnhmigration/compat/mixin/LegacyHuntTaskMixin");
            int shadows=0;
            for (FieldNode field:mixin.fields) {
                if (field.visibleAnnotations==null || field.visibleAnnotations.stream().noneMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;"))) continue;
                require(target.fields.stream().anyMatch(f->f.name.equals(field.name) && f.desc.equals(field.desc)),"real FTB shadow "+field.name);
                shadows++;
            }
            require(shadows==4,"four checked shadow fields");
            MethodNode handler=mixin.methods.stream().filter(m->m.name.equals("gtnhMigration$legacyHunt")).findFirst().orElseThrow();
            AnnotationNode annotation=handler.visibleAnnotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
            var values=params(annotation);
            require(List.of("match").equals(values.get("method")),"match injection");
            require(Integer.valueOf(1).equals(values.get("require")) && Boolean.TRUE.equals(values.get("cancellable")) && Boolean.FALSE.equals(values.get("remap")),"injection requirements");
            require("HEAD".equals(params((AnnotationNode)((List<?>)values.get("at")).getFirst()).get("value")),"HEAD injection");
            Set<String> constants=new HashSet<>();
            for (AbstractInsnNode insn:handler.instructions) if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) constants.add(s);
            require(constants.containsAll(List.of("gtnh_legacy_hunt_exact","gtnh_legacy_hunt_subtypes")),"both opt-in modes");
            int policyCalls=0;
            for (AbstractInsnNode insn:handler.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals("org/gtnhmigration/compat/LegacyHuntEntityMatcher")
                        && call.name.equals("override") && call.desc.equals("(ZZLjava/lang/String;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Boolean;")) policyCalls++;
            }
            require(policyCalls==1,"mixin delegates once to executable reviewed policy");
        }
        System.out.println("{\"passed\":true,\"checks\":"+cases+",\"legacy_and_modern_mooshroom_ancestry_verified\":true,\"real_modern_class_policy_executed\":true,\"ordinary_ftb_policy_not_overridden\":true,\"invalid_markers_and_filters_rejected\":true,\"actual_jar_mixin_registration_verified\":true,\"missing_registration_mutation_rejected\":true,\"wrong_class_mapping_mutation_rejected\":true,\"actual_mixin_transformation_verified\":false,\"game_runtime_verified\":false}");
    }
}
