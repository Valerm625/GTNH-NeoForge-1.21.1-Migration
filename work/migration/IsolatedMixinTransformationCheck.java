import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.*;
import org.spongepowered.asm.service.*;

/** Real Sponge transformations of real upstream bytecode in a test service.
 * No Minecraft main, registry bootstrap, game class definition or world runs.
 * This does not emulate NeoForge's complete transformation chain.
 */
public final class IsolatedMixinTransformationCheck {
    static int assertions, verifiedMethods, injections;
    record Contract(String target, String method, String handler) {}
    static final List<Contract> CONTRACTS=List.of(
        new Contract("codechicken.enderstorage.storage.EnderLiquidStorage","<clinit>","gtnhMigration$restoreBucketCount"),
        new Contract("dev.ftb.mods.ftbquests.quest.task.ItemTask","submitTask","gtnhMigration$legacyRetrieval"),
        new Contract("dev.ftb.mods.ftbquests.quest.task.ItemTask","test","gtnhMigration$legacyCraftingMatch"),
        new Contract("dev.ftb.mods.ftbquests.quest.task.KillTask","match","gtnhMigration$legacyHunt"),
        new Contract("dev.ftb.mods.ftbquests.quest.task.LocationTask","canSubmit","gtnhMigration$dimensionOnly"),
        new Contract("dev.ftb.mods.ftbquests.util.FTBQuestsInventoryListener","slotChanged","gtnhMigration$queueLegacySlot"),
        new Contract("dev.ftb.mods.ftbquests.util.FTBQuestsInventoryListener","lambda$detect$0","gtnhMigration$deferLegacyAutomatic"),
        new Contract("dev.ftb.mods.ftbquests.quest.task.Task","checkOnLogin","gtnhMigration$deferLegacyLogin"),
        new Contract("net.minecraft.world.inventory.AbstractContainerMenu","addSlotListener","gtnhMigration$initialLegacyInventory")
    );
    static void require(boolean ok,String message) {
        assertions++;
        if(!ok) throw new AssertionError(message);
    }
    static ClassNode parse(byte[] bytes) {
        ClassNode node=new ClassNode(); new ClassReader(bytes).accept(node,0); return node;
    }
    static byte[] mutate(byte[] bytes,String mode) {
        ClassNode node=parse(bytes);
        if(mode.equals("missing_hunt_target")) {
            int count=0;
            for(MethodNode method:node.methods) if(method.name.equals("match")) { method.name="removedMatch"; count++; }
            require(count==1,"Wrong hunt mutation target");
        } else if(mode.equals("changed_capacity_constant")) {
            int count=0;
            for(MethodNode method:node.methods) if(method.name.equals("<clinit>")) {
                for(AbstractInsnNode insn:method.instructions) if(insn instanceof IntInsnNode value && value.operand==16) {
                    value.operand=15; count++;
                }
            }
            require(count==1,"Wrong capacity mutation target");
        }
        ClassWriter writer=new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    static boolean injectionFailure(Throwable error) {
        for(Throwable e=error;e!=null;e=e.getCause()) {
            String name=e.getClass().getName();
            if(name.startsWith("org.spongepowered.asm.mixin.injection.") &&
                    (name.endsWith("Exception") || name.endsWith("Error"))) return true;
        }
        return false;
    }
    public static void main(String[] args) throws Exception {
        String mode=args.length==0?"baseline":args[0];
        require(Set.of("baseline","missing_hunt_target","changed_capacity_constant","missing_registration").contains(mode),"Unknown test mode");
        MixinBootstrap.init();
        require(MixinService.getService() instanceof OfflineMixinService,"Unexpected Mixin service");
        MixinEnvironment environment=MixinEnvironment.getCurrentEnvironment();
        require(environment.getPhase()==MixinEnvironment.Phase.DEFAULT,"Wrong Mixin phase");
        Mixins.addConfiguration(mode.equals("missing_registration")?"missing-registration.mixins.json":"gtnh_migration_compat.mixins.json");
        IMixinTransformer transformer=((OfflineMixinService)MixinService.getService()).transformer();
        Set<String> targets=new LinkedHashSet<>(); CONTRACTS.forEach(c->targets.add(c.target()));
        for(String target:targets) {
            byte[] before;
            try(InputStream in=IsolatedMixinTransformationCheck.class.getClassLoader().getResourceAsStream(target.replace('.','/')+".class")) {
                require(in!=null,"Missing actual target "+target); before=in.readAllBytes();
            }
            boolean negative=mode.equals("missing_hunt_target") && target.endsWith(".KillTask")
                || mode.equals("changed_capacity_constant") && target.endsWith(".EnderLiquidStorage");
            if(negative) before=mutate(before,mode);
            byte[] after;
            try { after=transformer.transformClass(environment,target,before); }
            catch(Throwable error) {
                if(!negative || !injectionFailure(error)) throw error;
                System.out.println("{\"passed\":true,\"control\":\""+mode+"\",\"mixin_rejected_mutated_target\":true}");
                return;
            }
            require(!negative,"Mixin accepted changed target: "+mode);
            ClassNode node=parse(after);
            for(Contract contract:CONTRACTS) if(contract.target().equals(target)) {
                List<MethodNode> handlers=node.methods.stream().filter(m->m.name.endsWith("$"+contract.handler())).toList();
                if(mode.equals("missing_registration") && contract.handler().equals("gtnhMigration$legacyHunt")) {
                    require(handlers.isEmpty(),"Negative registration fixture still contains hunt handler");
                    require(Arrays.equals(before,after),"Unregistered hunt target unexpectedly changed");
                    System.out.println("{\"passed\":true,\"control\":\"missing_registration\",\"missing_injection_detected\":true}");
                    return;
                }
                require(handlers.size()==1,"Expected exactly one transformed handler: "+contract.handler());
                MethodNode handler=handlers.getFirst();
                long calls=0;
                for(MethodNode method:node.methods) if(method.name.equals(contract.method())) {
                    for(AbstractInsnNode instruction:method.instructions) {
                        if(instruction instanceof MethodInsnNode call && call.owner.equals(node.name)
                            && call.name.equals(handler.name) && call.desc.equals(handler.desc)) calls++;
                    }
                }
                require(calls==1,"Expected exactly one injection call from "+contract.method());
                injections++;
            }
            require(!Arrays.equals(before,after),"Unchanged target "+target);
            for(MethodNode method:node.methods) if((method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0) {
                new Analyzer<>(new BasicVerifier()).analyze(node.name,method); verifiedMethods++;
            }
            Files.write(Path.of(target.substring(target.lastIndexOf('.')+1)+".class.transformed"),after);
        }
        require(mode.equals("baseline"),"Negative control did not reach its target");
        require(injections==9,"Expected all nine registered injections");
        System.out.println("{\"test\":\"isolated_real_sponge_mixin_transformation\",\"passed\":true,\"assertions\":"+assertions
            +",\"targets_transformed\":7,\"injections_verified\":"+injections+",\"asm_basic_dataflow_methods\":"+verifiedMethods
            +",\"actual_sponge_transformer_executed\":true,\"isolated_mixin_transformation_verified\":true,\"neoforge_transform_chain_verified\":false,\"game_runtime_verified\":false}");
    }
}
