import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;
import java.lang.reflect.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
public class CapacityPatchCheck {
  static byte[] classBytes(String jar,String path) throws Exception {try(JarFile j=new JarFile(jar)){return j.getInputStream(j.getJarEntry(path)).readAllBytes();}}
  static ClassNode node(byte[] b){ClassNode n=new ClassNode();new ClassReader(b).accept(n,0);return n;}
  public static void main(String[] args)throws Exception{
    ClassNode target=node(classBytes(args[0],"codechicken/enderstorage/storage/EnderLiquidStorage.class"));
    MethodNode init=target.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow();
    int count=0;boolean finalCapacity=false,bucketMultiply=false;
    for(AbstractInsnNode i:init.instructions){
      if(i instanceof IntInsnNode n && n.operand==16){count++;AbstractInsnNode next=i.getNext();while(next!=null&&next.getOpcode()<0)next=next.getNext();bucketMultiply=next instanceof FieldInsnNode f&&f.owner.equals("codechicken/lib/fluid/FluidUtils")&&f.name.equals("B");}
    }
    for(FieldNode f:target.fields)if(f.name.equals("CAPACITY")) finalCapacity=f.desc.equals("I")&&(f.access&Opcodes.ACC_STATIC)!=0&&(f.access&Opcodes.ACC_FINAL)!=0;
    if(count!=1||!finalCapacity||!bucketMultiply)throw new AssertionError("Target injection shape changed");
    ClassNode mixin=node(classBytes(args[1],"org/gtnhmigration/compat/mixin/EnderTankCapacityMixin.class"));
    MethodNode method=mixin.methods.stream().filter(m->m.name.equals("gtnhMigration$restoreBucketCount")).findFirst().orElseThrow();
    AnnotationNode inject=method.visibleAnnotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyConstant;")).findFirst().orElseThrow();
    Map<String,Object> params=new HashMap<>();for(int i=0;i<inject.values.size();i+=2)params.put((String)inject.values.get(i),inject.values.get(i+1));
    if(!Integer.valueOf(1).equals(params.get("require"))||!Integer.valueOf(1).equals(params.get("allow"))||!Boolean.FALSE.equals(params.get("remap")))throw new AssertionError("Injection bounds/remap missing");
    if(!((List<?>)params.get("method")).equals(List.of("<clinit>")))throw new AssertionError("Wrong injection target");
    List<?> consts=(List<?>)params.get("constant");AnnotationNode constant=(AnnotationNode)consts.getFirst();
    if(!constant.values.contains("intValue")||!constant.values.contains(16))throw new AssertionError("Wrong source constant");
    Class<?> handler=Class.forName("org.gtnhmigration.compat.mixin.EnderTankCapacityMixin");Method fn=handler.getDeclaredMethod("gtnhMigration$restoreBucketCount",int.class);fn.setAccessible(true);
    if(!Integer.valueOf(256).equals(fn.invoke(null,16)))throw new AssertionError("Wrong target bucket count");
    for(int wrong:new int[]{0,15,17,256}){boolean rejected=false;try{fn.invoke(null,wrong);}catch(InvocationTargetException e){rejected=e.getCause() instanceof IllegalStateException;}if(!rejected)throw new AssertionError("Unexpected target accepted "+wrong);}
    System.out.println("{\"test\":\"compiled_capacity_adapter_contract\",\"passed\":true,\"actual_target_matches\":1,\"actual_handler_output_buckets\":256,\"unexpected_originals_rejected\":4,\"actual_mixin_transformation_tested\":false,\"game_storage_verified\":false}");
  }
}
