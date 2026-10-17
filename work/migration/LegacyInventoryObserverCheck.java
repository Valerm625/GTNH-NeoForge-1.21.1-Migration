import java.util.*;
import java.util.jar.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.gtnhmigration.compat.LegacyInventoryRescanQueue;

/** Runs the packaged queue and vanilla menus; inspects packaged bridge contracts.
 * Does not load mods, apply Mixins, dispatch game events or start a world/server.
 */
public class LegacyInventoryObserverCheck {
    static final String BASE="org/gtnhmigration/compat/";
    static final String TASK="dev/ftb/mods/ftbquests/quest/task/Task";
    static final String LISTENER="dev/ftb/mods/ftbquests/util/FTBQuestsInventoryListener";
    static final String MENU="net/minecraft/world/inventory/AbstractContainerMenu";
    static int assertions,sourceComparisons;
    static void require(boolean ok,String message) { assertions++; if(!ok) throw new AssertionError(message); }
    static ClassNode node(String jar,String path) throws Exception {
        try(JarFile zip=new JarFile(jar)) {
            ClassNode node=new ClassNode();
            new ClassReader(zip.getInputStream(zip.getJarEntry(path+".class")).readAllBytes()).accept(node,0);
            return node;
        }
    }
    static MethodNode method(ClassNode node,String name) { return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow(); }
    static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> all=new ArrayList<>();
        if(method.visibleAnnotations!=null) all.addAll(method.visibleAnnotations);
        if(method.invisibleAnnotations!=null) all.addAll(method.invisibleAnnotations);
        return all;
    }
    static AnnotationNode annotation(MethodNode method,String name) {
        return annotations(method).stream().filter(a->a.desc.equals(name)).findFirst().orElseThrow();
    }
    static Map<String,Object> params(AnnotationNode annotation) {
        Map<String,Object> values=new HashMap<>();
        if(annotation.values!=null) for(int i=0;i<annotation.values.size();i+=2) values.put((String)annotation.values.get(i),annotation.values.get(i+1));
        return values;
    }
    static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> result=new ArrayList<>();
        for(AbstractInsnNode instruction:method.instructions) if(instruction instanceof MethodInsnNode call) result.add(call);
        return result;
    }
    static long calls(MethodNode method,String owner,String name) { return calls(method).stream().filter(c->c.owner.equals(owner)&&c.name.equals(name)).count(); }
    static boolean opcode(MethodNode method,int opcode) { for(AbstractInsnNode i:method.instructions) if(i.getOpcode()==opcode)return true; return false; }
    static AbstractInsnNode next(AbstractInsnNode instruction) { do { instruction=instruction.getNext(); } while(instruction!=null&&instruction.getOpcode()<0); return instruction; }
    static List<String> constants(MethodNode method) {
        List<String> result=new ArrayList<>(); for(AbstractInsnNode i:method.instructions) if(i instanceof LdcInsnNode l&&l.cst instanceof String s)result.add(s); return result;
    }
    static void inject(MethodNode handler,String target,String point,boolean cancellable) {
        Map<String,Object> values=params(annotation(handler,"Lorg/spongepowered/asm/mixin/injection/Inject;"));
        require(List.of(target).equals(values.get("method")) && Integer.valueOf(1).equals(values.get("require"))
                && Boolean.FALSE.equals(values.get("remap")),"Wrong injection target/require/remap: "+handler.name);
        require(cancellable?Boolean.TRUE.equals(values.get("cancellable")):!Boolean.TRUE.equals(values.get("cancellable")),"Wrong cancellation contract");
        AnnotationNode at=(AnnotationNode)((List<?>)values.get("at")).getFirst();
        require(point.equals(params(at).get("value")),"Wrong injection point: "+handler.name);
    }
    static void checkQueue() {
        LegacyInventoryRescanQueue<Object,String> queue=new LegacyInventoryRescanQueue<>();
        Object server=new Object(),otherServer=new Object();
        require(queue.drain(server).isEmpty(),"New queue not empty");
        for(int index=-5;index<=55;index++) for(boolean empty:List.of(false,true)) {
            BqInventoryReference.reset();
            BqInventoryReference.Player player=BqInventoryReference.player("p");
            BqInventoryReference.slot(player,index,empty);
            BqInventoryReference.tick(false);
            require(BqInventoryReference.observed().isEmpty(),"Source must not process at END");
            if(LegacyInventoryRescanQueue.observesSlot(true,index))queue.schedule(server,"p");
            BqInventoryReference.tick(true);
            sourceComparisons++;
            require(new HashSet<>(queue.drain(server)).equals(BqInventoryReference.observed()),"Slot/empty source mismatch: "+index);
            require(!LegacyInventoryRescanQueue.observesSlot(false,index),"Non-player menu was observed");
        }
        BqInventoryReference.reset();
        var a=BqInventoryReference.player("a"); var b=BqInventoryReference.player("b");
        BqInventoryReference.initial(a); BqInventoryReference.initial(a); BqInventoryReference.initial(b);
        queue.schedule(server,"a"); queue.schedule(server,"a"); queue.schedule(server,"b");
        BqInventoryReference.tick(false); require(BqInventoryReference.observed().isEmpty(),"Initial synchronization scanned synchronously");
        BqInventoryReference.tick(true); List<String> batch=queue.drain(server); sourceComparisons++;
        require(new HashSet<>(batch).equals(BqInventoryReference.observed())&&batch.size()==2,"Initial/source dedup mismatch");
        require(queue.drain(server).isEmpty(),"Drained queue retained players");
        queue.schedule(server,"a"); queue.schedule(otherServer,"a"); queue.schedule(otherServer,"b");
        require(queue.drain(server).equals(List.of("a")),"Server queues leaked into each other");
        require(queue.drain(otherServer).equals(List.of("a","b")),"Other server's queue was lost");
        queue.schedule(server,"a");queue.schedule(otherServer,"b");queue.clear(server);
        require(queue.drain(server).isEmpty()&&queue.drain(otherServer).equals(List.of("b")),"Server stop cleanup crossed server boundaries");
        queue.schedule(server,"a"); List<String> current=queue.drain(server); queue.schedule(server,"b");
        require(current.equals(List.of("a"))&&queue.drain(server).equals(List.of("b")),"New requests after drain did not await next pass");
        boolean immutable=false;try{current.add("c");}catch(UnsupportedOperationException expected){immutable=true;}
        require(immutable,"Drain exposed mutable batch");
        boolean nullServer=false,nullPlayer=false;
        try{queue.schedule(null,"p");}catch(NullPointerException expected){nullServer=true;}
        try{queue.schedule(server,null);}catch(NullPointerException expected){nullPlayer=true;}
        require(nullServer&&nullPlayer,"Null queue key accepted");
        BqInventoryReference.reset();BqInventoryReference.initial(a);BqInventoryReference.cleanup();BqInventoryReference.tick(true);
        require(BqInventoryReference.observed().isEmpty(),"Source cleanup changed");
    }
    static final class ProbeMenu extends AbstractContainerMenu {
        final SimpleContainer inventory=new SimpleContainer(46); int broadcasts;
        ProbeMenu(){super(null,0);for(int i=0;i<46;i++)addSlot(new Slot(inventory,i,0,0));}
        @Override public ItemStack quickMoveStack(Player player,int index){return ItemStack.EMPTY;}
        @Override public boolean stillValid(Player player){return true;}
        @Override public void broadcastChanges(){broadcasts++;super.broadcastChanges();}
    }
    static final class Listener implements ContainerListener {
        int changes,emptyChanges;
        public void slotChanged(AbstractContainerMenu menu,int index,ItemStack stack){changes++;if(stack.isEmpty())emptyChanges++;}
        public void dataChanged(AbstractContainerMenu menu,int index,int value){}
    }
    static void checkRealMenus() {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        ProbeMenu empty=new ProbeMenu();Listener listener=new Listener();empty.addSlotListener(listener);
        require(empty.broadcasts==1&&listener.changes==0,"Empty initial registration should have no vanilla slot callback");
        empty.addSlotListener(listener);
        require(empty.broadcasts==1,"Duplicate listener must return before broadcastChanges injection point");
        ProbeMenu synced=new ProbeMenu();synced.inventory.setItem(9,new ItemStack(Items.DIRT,1));synced.broadcastChanges();
        Listener addedLate=new Listener();synced.addSlotListener(addedLate);
        require(addedLate.changes==0,"Already-synchronized nonempty inventory should have no initial callback");
        synced.inventory.setItem(9,ItemStack.EMPTY);synced.broadcastChanges();
        require(addedLate.changes==1&&addedLate.emptyChanges==1,"Vanilla must deliver changed-to-empty slot callbacks");
    }
    static void checkShapes(String compat,String ftb,String minecraft) throws Exception {
        ClassNode listener=node(compat,BASE+"LegacyInventoryListenerMixin");
        MethodNode slot=method(listener,"gtnhMigration$queueLegacySlot");inject(slot,"slotChanged","HEAD",false);
        require(calls(slot,BASE+"LegacyInventoryRescanQueue","observesSlot")==1&&calls(slot,BASE+"LegacyInventoryObserver","schedule")==1,"Slot callback missing queue/policy");
        require(calls(slot,"net/minecraft/world/item/ItemStack","isEmpty")==0,"Empty slot callbacks were filtered");
        require(opcode(slot,Opcodes.IF_ACMPNE)||opcode(slot,Opcodes.IF_ACMPEQ),"No player inventory menu identity guard");
        for(AbstractInsnNode i:slot.instructions) require(!(i instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==3),"Slot callback reads stack argument");
        MethodNode redirect=method(listener,"gtnhMigration$deferLegacyAutomatic");
        Map<String,Object> redir=params(annotation(redirect,"Lorg/spongepowered/asm/mixin/injection/Redirect;"));
        String submitDescriptor="(Ldev/ftb/mods/ftbquests/quest/TeamData;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V";
        require(List.of("*").equals(redir.get("method"))&&Integer.valueOf(1).equals(redir.get("require"))&&Integer.valueOf(1).equals(redir.get("allow"))&&Boolean.FALSE.equals(redir.get("remap")),"Redirect does not enforce exact one call");
        Map<String,Object> at=params((AnnotationNode)redir.get("at"));
        require("INVOKE".equals(at.get("value"))&&("L"+TASK+";submitTask"+submitDescriptor).equals(at.get("target")),"Redirect target changed");
        require((redirect.access&Opcodes.ACC_STATIC)!=0&&calls(redirect,TASK,"submitTask")==1,"Redirect lost original fallback");
        List<MethodInsnNode> redirectCalls=calls(redirect);
        MethodInsnNode empty=redirectCalls.stream().filter(c->c.name.equals("isEmpty")).findFirst().orElseThrow();
        MethodInsnNode marked=redirectCalls.stream().filter(c->c.owner.equals(BASE+"LegacyInventoryObserver")&&c.name.equals("isMarked")).findFirst().orElseThrow();
        require(next(empty) instanceof JumpInsnNode first&&first.getOpcode()==Opcodes.IFEQ
                &&next(marked) instanceof JumpInsnNode second&&second.getOpcode()==Opcodes.IFEQ&&first.label==second.label
                &&next(second).getOpcode()==Opcodes.RETURN,"Only marked automatic submissions may be deferred");
        ClassNode originalListener=node(ftb,LISTENER);
        long originalCalls=originalListener.methods.stream().flatMap(m->calls(m).stream()).filter(c->c.owner.equals(TASK)&&c.name.equals("submitTask")&&c.desc.equals(submitDescriptor)).count();
        require(originalCalls==1,"Pinned FTB listener has changed redirect multiplicity");
        require(calls(method(originalListener,"slotChanged"),"net/minecraft/world/item/ItemStack","isEmpty")==1,"Expected original empty-slot issue disappeared");

        ClassNode observer=node(compat,BASE+"LegacyInventoryObserver");
        List<AnnotationNode> classAnnotations=new ArrayList<>();
        if(observer.visibleAnnotations!=null)classAnnotations.addAll(observer.visibleAnnotations);
        if(observer.invisibleAnnotations!=null)classAnnotations.addAll(observer.invisibleAnnotations);
        AnnotationNode subscriber=classAnnotations.stream().filter(a->a.desc.equals("Lnet/neoforged/fml/common/EventBusSubscriber;")).findFirst().orElseThrow();
        require("gtnh_migration_compat".equals(params(subscriber).get("modid")),"Observer not associated with compatibility mod");
        MethodNode before=method(observer,"beforeTick"),stopped=method(observer,"stopped"),isMarked=method(observer,"isMarked");
        require(before.desc.equals("(Lnet/neoforged/neoforge/event/tick/ServerTickEvent$Pre;)V")&&(before.access&Opcodes.ACC_STATIC)!=0,"Observer must use static server Pre event");
        annotation(before,"Lnet/neoforged/bus/api/SubscribeEvent;");annotation(stopped,"Lnet/neoforged/bus/api/SubscribeEvent;");
        require(calls(before,BASE+"LegacyInventoryRescanQueue","drain")==1&&calls(stopped,BASE+"LegacyInventoryRescanQueue","clear")==1,"Missing per-server drain/stop cleanup");
        require(stopped.desc.equals("(Lnet/neoforged/neoforge/event/server/ServerStoppedEvent;)V"),"Wrong server cleanup event");
        require(constants(isMarked).equals(List.of("gtnh_legacy_single_retrieval"))&&calls(isMarked,TASK,"hasTag")==1,"Marker scope broadened");
        MethodNode schedule=method(observer,"schedule");
        require(calls(schedule,"dev/architectury/hooks/level/entity/PlayerHooks","isFake")==1
                &&calls(schedule,BASE+"LegacyInventoryRescanQueue","schedule")==1&&opcode(schedule,Opcodes.IFNULL),"Missing fake/null-server queue guards");
        List<MethodNode> submitMethods=observer.methods.stream().filter(m->calls(m,TASK,"submitTask")>0).toList();
        require(submitMethods.size()==1,"Unexpected observer submit routes");
        MethodNode submit=submitMethods.getFirst();
        require(calls(submit,BASE+"LegacyInventoryObserver","isMarked")==1
                &&calls(submit,"dev/ftb/mods/ftbquests/quest/TeamData","canStartTasks")==1,"Observer submission scope is not guarded");
        require(observer.methods.stream().anyMatch(m->calls(m,"dev/ftb/mods/ftbquests/quest/TeamData","isLocked")==1)
                &&observer.methods.stream().anyMatch(m->calls(m,"dev/ftb/mods/ftbquests/quest/ServerQuestFile","withPlayerContext")==1),"Missing locked-team or player-context path");

        ClassNode login=node(compat,BASE+"LegacyTaskLoginTimingMixin");MethodNode loginHandler=method(login,"gtnhMigration$deferLegacyLogin");
        inject(loginHandler,"checkOnLogin","HEAD",true);
        require(calls(loginHandler,BASE+"LegacyInventoryObserver","isMarked")==1
                &&calls(loginHandler,"org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable","setReturnValue")==1
                &&opcode(loginHandler,Opcodes.ICONST_0)&&!opcode(loginHandler,Opcodes.ICONST_1),"Login suppression is not marked-only false");
        ClassNode initial=node(compat,BASE+"LegacyInitialInventorySyncMixin");MethodNode initialHandler=method(initial,"gtnhMigration$initialLegacyInventory");
        inject(initialHandler,"addSlotListener","INVOKE",false);
        Map<String,Object> initialInjection=params(annotation(initialHandler,"Lorg/spongepowered/asm/mixin/injection/Inject;"));
        Map<String,Object> initialAt=params((AnnotationNode)((List<?>)initialInjection.get("at")).getFirst());
        require(("L"+MENU+";broadcastChanges()V").equals(initialAt.get("target"))&&Integer.valueOf(1).equals(initialInjection.get("allow")),"Initial callback must target one post-add broadcast call");
        require(calls(initialHandler,BASE+"LegacyInventoryObserver","schedule")==1&&(opcode(initialHandler,Opcodes.IF_ACMPNE)||opcode(initialHandler,Opcodes.IF_ACMPEQ)),"Initial sync lacks player-menu guard");
        boolean listenerType=false;for(AbstractInsnNode i:initialHandler.instructions)if(i instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.INSTANCEOF&&t.desc.equals(LISTENER))listenerType=true;
        require(listenerType,"Initial sync affects unrelated listeners");
        require(calls(method(node(minecraft,MENU),"addSlotListener"),MENU,"broadcastChanges")==1,"Vanilla registration injection point changed");
    }
    public static void main(String[] args) throws Exception {
        checkQueue();checkRealMenus();checkShapes(args[0],args[1],args[2]);
        System.out.println("{\"test\":\"compiled_inventory_observer_and_actual_vanilla_menu\",\"passed\":true,\"assertions\":"+assertions
                +",\"source_comparisons\":"+sourceComparisons+",\"packaged_queue_executed\":true,\"actual_vanilla_menu_executed\":true,\"initial_callback_absence_reproduced\":true"
                +",\"bridge_shapes_checked\":true,\"actual_mixin_transformation_tested\":false,\"game_events_executed\":false,\"game_runtime_verified\":false}");
    }
}
