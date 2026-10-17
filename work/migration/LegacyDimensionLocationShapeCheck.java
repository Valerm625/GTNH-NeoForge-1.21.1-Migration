import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
public class LegacyDimensionLocationShapeCheck {
 static ClassNode node(String jar,String path)throws Exception {try(JarFile z=new JarFile(jar)){ClassNode n=new ClassNode();new ClassReader(z.getInputStream(z.getJarEntry(path)).readAllBytes()).accept(n,0);return n;}}
 static Map<String,Object> params(AnnotationNode a){Map<String,Object> out=new HashMap<>();for(int i=0;i<a.values.size();i+=2)out.put((String)a.values.get(i),a.values.get(i+1));return out;}
 static void require(boolean ok,String m){if(!ok)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception{
  ClassNode target=node(args[0],"dev/ftb/mods/ftbquests/quest/task/LocationTask.class");
  String desc="(Ldev/ftb/mods/ftbquests/quest/TeamData;Lnet/minecraft/server/level/ServerPlayer;)Z";
  require(target.methods.stream().anyMatch(m->m.name.equals("canSubmit")&&m.desc.equals(desc)),"LocationTask canSubmit target shape changed");
  ClassNode mix=node(args[1],"org/gtnhmigration/compat/LegacyDimensionLocationTaskMixin.class");
  MethodNode handler=null;boolean marker=false;int shadows=0;
  for(FieldNode f:mix.fields){
   if(f.visibleAnnotations!=null && f.visibleAnnotations.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;"))){
    require(target.fields.stream().anyMatch(t->t.name.equals(f.name)&&t.desc.equals(f.desc)),"Missing shadow field: "+f.name);shadows++;
   }
  }
  for(MethodNode m:mix.methods){
   if(m.name.equals("gtnhMigration$dimensionOnly"))handler=m;
   for(AbstractInsnNode i:m.instructions)if(i instanceof LdcInsnNode l && "gtnh_legacy_dimension_only".equals(l.cst))marker=true;
  }
  require(shadows==1&&handler!=null&&marker,"Missing location adapter handler/shadow/marker");
  AnnotationNode inject=handler.visibleAnnotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
  Map<String,Object> v=params(inject);require(Integer.valueOf(1).equals(v.get("require"))&&Boolean.FALSE.equals(v.get("remap"))&&Boolean.TRUE.equals(v.get("cancellable")),"Unsafe location injection flags");
  require(List.of("canSubmit").equals(v.get("method")),"Unexpected location target method");
  AnnotationNode at=(AnnotationNode)((List<?>)v.get("at")).getFirst();require("HEAD".equals(params(at).get("value")),"Location adapter is not HEAD scoped");
  System.out.println("{\"test\":\"compiled_dimension_location_adapter_shape\",\"passed\":true,\"actual_mixin_transformation_tested\":false,\"game_runtime_verified\":false}");
 }
}
