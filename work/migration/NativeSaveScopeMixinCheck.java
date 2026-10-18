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
public final class NativeSaveScopeMixinCheck {
    static int assertions, verifiedMethods, injections;
    record Contract(String target, String method, String handler) {}
    static final List<Contract> CONTRACTS=List.of(
        new Contract("net.minecraft.server.MinecraftServer","loadLevel","gtnhMigration$beforeWorldLoad"),
        new Contract("net.minecraft.server.MinecraftServer","stopServer","gtnhMigration$afterWorldStop"),
        new Contract("net.minecraft.server.players.PlayerList","placeNewPlayer","gtnhMigration$guardAdmission"),
        new Contract("net.minecraft.server.players.PlayerList","load","gtnhMigration$permitPlayerLoadFile"),
        new Contract("net.minecraft.server.players.PlayerList","load","gtnhMigration$permitOwnerLoadData"),
        new Contract("net.minecraft.world.level.storage.PlayerDataStorage","load","gtnhMigration$verifiedPlayerRead"),
        new Contract("net.minecraft.world.level.storage.PlayerDataStorage","backup","gtnhMigration$beforePlayerBackup"),
        new Contract("net.minecraft.world.level.storage.PlayerDataStorage","lambda$load$1","gtnhMigration$permitFileLoadData"),
        new Contract("net.minecraft.world.entity.Entity","load","gtnhMigration$enterPlayerDataLoad"),
        new Contract("net.minecraft.server.MinecraftServer","saveEverything","gtnhMigration$enterSaveEverything"),
        new Contract("net.minecraft.server.MinecraftServer","saveAllChunks","gtnhMigration$enterSaveChunks"),
        new Contract("net.minecraft.server.MinecraftServer","tickServer","gtnhMigration$denyNestedTick"),
        new Contract("net.minecraft.server.MinecraftServer","stopServer","gtnhMigration$denyNestedStop"),
        new Contract("net.minecraft.server.MinecraftServer","saveEverything","gtnhMigration$permitPlayerListSave"),
        new Contract("net.minecraft.server.MinecraftServer","saveEverything","gtnhMigration$permitChunkSave"),
        new Contract("net.minecraft.server.MinecraftServer","saveAllChunks","gtnhMigration$permitWorldTag"),
        new Contract("net.minecraft.server.players.PlayerList","saveAll","gtnhMigration$enterSavePlayers"),
        new Contract("net.minecraft.server.players.PlayerList","save","gtnhMigration$enterSavePlayer"),
        new Contract("net.minecraft.server.players.PlayerList","saveAll","gtnhMigration$permitOnePlayer"),
        new Contract("net.minecraft.server.players.PlayerList","save","gtnhMigration$permitPlayerFile"),
        new Contract("net.minecraft.server.players.PlayerList","load","gtnhMigration$denyPlayerLoad"),
        new Contract("net.minecraft.server.players.PlayerList","placeNewPlayer","gtnhMigration$denyPlayerJoin"),
        new Contract("net.minecraft.server.players.PlayerList","remove","gtnhMigration$denyPlayerRemove"),
        new Contract("net.minecraft.server.players.PlayerList","respawn","gtnhMigration$denyPlayerRespawn"),
        new Contract("net.minecraft.world.level.storage.PlayerDataStorage","save","gtnhMigration$enterPlayerFile"),
        new Contract("net.minecraft.world.level.storage.PlayerDataStorage","load","gtnhMigration$denyPlayerFileLoad"),
        new Contract("net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess","saveDataTag","gtnhMigration$enterWorldTag"),
        new Contract("net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess","saveLevelData","gtnhMigration$enterWorldFile"),
        new Contract("net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess","saveDataTag","gtnhMigration$permitWorldFile"),
        new Contract("net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess","close","gtnhMigration$denyWorldClose"),
        new Contract("net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess","deleteLevel","gtnhMigration$denyWorldDelete"),
        new Contract("net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess","renameLevel","gtnhMigration$denyWorldRename"),
        new Contract("net.minecraft.server.MinecraftServer","tickChildren","gtnhMigration$denyChildTicks"),
        new Contract("net.minecraft.server.level.ServerPlayer","tick","gtnhMigration$denyPlayerTick"),
        new Contract("net.minecraft.server.level.ServerPlayer","doTick","gtnhMigration$denyPlayerDoTick"),
        new Contract("net.minecraft.server.level.ServerPlayer","die","gtnhMigration$denyPlayerDeath"),
        new Contract("net.minecraft.server.level.ServerPlayer","restoreFrom","gtnhMigration$denyPlayerClone"),
        new Contract("net.minecraft.server.level.ServerPlayer","changeDimension","gtnhMigration$denyPlayerDimension"),
        new Contract("net.minecraft.client.server.IntegratedPlayerList","save","gtnhMigration$enterOwnerCache")
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
        int count=0;
        for(MethodNode method:node.methods) if(method.name.equals(mode.equals("missing_player_load_target")?"load":"save")){method.name="removedNativeEntry";count++;}
        require(count==(mode.equals("missing_player_load_target")?2:1),"Wrong native mutation target");
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
        require(Set.of("baseline","dedicated","missing_player_file_target","missing_player_load_target","missing_registration").contains(mode),"Unknown test mode");
        MixinBootstrap.init();
        require(MixinService.getService() instanceof OfflineMixinService,"Unexpected Mixin service");
        MixinEnvironment environment=MixinEnvironment.getCurrentEnvironment();
        require(environment.getPhase()==MixinEnvironment.Phase.DEFAULT,"Wrong Mixin phase");
        boolean dedicated=mode.equals("dedicated");
        environment.setSide(dedicated?MixinEnvironment.Side.SERVER:MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration(mode.equals("missing_registration")?"missing-registration.mixins.json":"gtnh_migration_compat.mixins.json");
        IMixinTransformer transformer=((OfflineMixinService)MixinService.getService()).transformer();
        Set<String> targets=new LinkedHashSet<>(); CONTRACTS.forEach(c->{if(!dedicated || !c.target().startsWith("net.minecraft.client."))targets.add(c.target());});
        for(String target:targets) {
            byte[] before;
            try(InputStream in=NativeSaveScopeMixinCheck.class.getClassLoader().getResourceAsStream(target.replace('.','/')+".class")) {
                require(in!=null,"Missing actual target "+target); before=in.readAllBytes();
            }
            boolean negative=(mode.equals("missing_player_file_target") || mode.equals("missing_player_load_target")) && target.endsWith(".PlayerDataStorage");
            if(negative) before=mutate(before,mode);
            byte[] after;
            try { after=transformer.transformClass(environment,target,before); }
            catch(Throwable error) {
                if(!negative || !injectionFailure(error)) throw error;
                System.out.println("{\"passed\":true,\"control\":\""+mode+"\",\"mixin_rejected_mutated_target\":true}");
                return;
            }
            require(!negative,"Mixin accepted changed target: "+mode);
            if(mode.equals("missing_registration") && target.endsWith(".PlayerDataStorage")) {
                require(Arrays.equals(before,after),"Unregistered player file target unexpectedly changed");
                System.out.println("{\"passed\":true,\"control\":\"missing_registration\",\"missing_injection_detected\":true}");return;
            }
            ClassNode node=parse(after);
            for(Contract contract:CONTRACTS) if(contract.target().equals(target)) {
                List<MethodNode> handlers=node.methods.stream().filter(m->m.name.endsWith("$"+contract.handler())).toList();
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
                if(contract.handler().contains("$enter") || contract.handler().contains("$deny")) {
                    boolean found=false;
                    for(MethodNode method:node.methods) if(method.name.equals(contract.method())) {
                        List<MethodInsnNode> invocations=new ArrayList<>();
                        for(AbstractInsnNode instruction:method.instructions) if(instruction instanceof MethodInsnNode invoke)invocations.add(invoke);
                        if(invocations.stream().anyMatch(i->i.name.equals(handler.name))) {
                            require(!invocations.isEmpty() && invocations.getFirst().name.equals(handler.name),"Guard is not before native method calls: "+contract.handler());found=true;
                        }
                    }
                    require(found,"No guarded method body");
                }

                if(contract.handler().equals("gtnhMigration$permitFileLoadData")) {
                    MethodNode load=node.methods.stream().filter(m->m.name.equals("lambda$load$1")).findFirst().orElseThrow();
                    int position=0,fix=-1,apply=-1,event=-1;
                    for(AbstractInsnNode instruction:load.instructions) {
                        if(instruction instanceof MethodInsnNode call){
                            if(call.name.equals("updateToCurrentVersion"))fix=position;
                            if(call.name.equals(handler.name))apply=position;
                            if(call.name.equals("firePlayerLoadingEvent"))event=position;
                        }position++;
                    }
                    require(fix>=0 && apply>fix && event>apply,"DataFixer/load/event order changed");
                }
                injections++;
            }
            require(!Arrays.equals(before,after),"Unchanged target "+target);
            for(MethodNode method:node.methods) if((method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0) {
                new Analyzer<>(new BasicVerifier()).analyze(node.name,method); verifiedMethods++;
            }
            Files.write(Path.of(target.substring(target.lastIndexOf('.')+1)+".class.transformed"),after);
        }
        require(mode.equals("baseline") || dedicated,"Negative control did not reach its target");
        require(injections==(dedicated?38:39),"Expected all registered native guard injections for this distribution");
        System.out.println("{\"test\":\"native_save_scope_mixin_transformation\",\"passed\":true,\"assertions\":"+assertions
            +",\"targets_transformed\":"+targets.size()+",\"injections_verified\":"+injections+",\"asm_basic_dataflow_methods\":"+verifiedMethods
            +",\"actual_sponge_transformer_executed\":true,\"isolated_mixin_transformation_verified\":true,\"neoforge_transform_chain_verified\":false,\"game_runtime_verified\":false}");
    }
}
