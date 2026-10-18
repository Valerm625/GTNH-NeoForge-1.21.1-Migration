import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
public class QuestAdapterShapeCheck {
 static ClassNode node(String jar,String path)throws Exception {try(JarFile z=new JarFile(jar)){ClassNode n=new ClassNode();new ClassReader(z.getInputStream(z.getJarEntry(path)).readAllBytes()).accept(n,0);return n;}}
 static Map<String,Object> params(AnnotationNode a){Map<String,Object> out=new HashMap<>();for(int i=0;i<a.values.size();i+=2)out.put((String)a.values.get(i),a.values.get(i+1));return out;}
 static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  ClassNode target=node(args[0],"dev/ftb/mods/ftbquests/quest/task/ItemTask.class");
  String desc="(Ldev/ftb/mods/ftbquests/quest/TeamData;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V";
  require(target.methods.stream().filter(m->m.name.equals("submitTask")&&m.desc.equals(desc)).count()==1,"submitTask target shape changed");
  ClassNode mixin=node(args[1],"org/gtnhmigration/compat/mixin/LegacyRetrievalItemTaskMixin.class");
  int shadows=0;MethodNode handler=null;
  for(MethodNode m:mixin.methods){
   if(m.name.equals("gtnhMigration$legacyRetrieval"))handler=m;
   if(m.visibleAnnotations!=null && m.visibleAnnotations.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;"))){
    require(target.methods.stream().anyMatch(t->t.name.equals(m.name)&&t.desc.equals(m.desc)),"Missing shadow target: "+m.name);shadows++;
   }
  }
  require(shadows==5 && handler!=null,"Expected five explicit shadows and a handler");
  AnnotationNode inject=handler.visibleAnnotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
  Map<String,Object> v=params(inject);
  require(Integer.valueOf(1).equals(v.get("require"))&&Boolean.FALSE.equals(v.get("remap"))&&Boolean.TRUE.equals(v.get("cancellable")),"Unsafe injection flags");
  require(List.of("submitTask").equals(v.get("method")),"Unexpected target method");
  AnnotationNode at=(AnnotationNode)((List<?>)v.get("at")).getFirst();require("HEAD".equals(params(at).get("value")),"Not HEAD injection");
  boolean marker=false,offhand=false;int durabilityMatchers=0;
  for(AbstractInsnNode i:handler.instructions){if(i instanceof LdcInsnNode l && "gtnh_legacy_single_retrieval".equals(l.cst))marker=true;if(i instanceof FieldInsnNode f && "offhand".equals(f.name))offhand=true;if(i instanceof MethodInsnNode m && m.owner.equals("org/gtnhmigration/compat/LegacyRetrievalStackMatcher") && m.name.equals("matches"))durabilityMatchers++;}
  require(marker&&!offhand,"Missing opt-in guard or unexpected offhand scan");
  require(durabilityMatchers==2,"Main inventory and armor must both preserve legacy durability semantics");
  System.out.println("{\"test\":\"compiled_quest_adapter_target_shape\",\"passed\":true,\"shadow_methods\":5,\"legacy_durability_matcher_calls\":2,\"actual_mixin_transformation_tested\":false,\"game_runtime_verified\":false}");
 }
}
