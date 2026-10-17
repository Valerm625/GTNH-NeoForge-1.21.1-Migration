/** Isolated test service for the real pinned Sponge Mixin transformer.
 * Not NeoForge's ModLauncher service and never installed in the game mod. */
import java.io.*;
import java.net.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.service.*;
import org.spongepowered.asm.launch.platform.container.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.*;

public final class OfflineMixinService extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider {
 public String getName() { return "GTNH-isolated-bytecode-test"; }
 public boolean isValid() { return true; }
 public MixinEnvironment.Phase getInitialPhase() { return MixinEnvironment.Phase.DEFAULT; }
 public IClassProvider getClassProvider() { return this; }
 public IClassBytecodeProvider getBytecodeProvider() { return this; }
 public ITransformerProvider getTransformerProvider() { return null; }
 public IClassTracker getClassTracker() { return null; }
 public IMixinAuditTrail getAuditTrail() { return null; }
 public Collection<String> getPlatformAgents() { return List.of(); }
 public IContainerHandle getPrimaryContainer() { return new ContainerHandleVirtual("offline-test"); }
 public InputStream getResourceAsStream(String name) { return getClass().getClassLoader().getResourceAsStream(name); }
 public URL[] getClassPath() { return new URL[0]; }
 public Class<?> findClass(String name) throws ClassNotFoundException { return findClass(name,false); }
 public Class<?> findClass(String name,boolean initialize) throws ClassNotFoundException {
  // Game and compatibility classes are bytecode inputs, never executable test fixtures.
  if(name.startsWith("net.minecraft.") || name.startsWith("net.neoforged.")
    || name.startsWith("dev.ftb.") || name.startsWith("codechicken.") || name.startsWith("org.gtnhmigration.compat.")) {
   throw new ClassNotFoundException("Game class definition is disabled in the isolated Mixin test: "+name);
  }
  return Class.forName(name,initialize,getClass().getClassLoader());
 }
 public Class<?> findAgentClass(String name,boolean initialize) throws ClassNotFoundException { return findClass(name,initialize); }
 public ClassNode getClassNode(String name) throws ClassNotFoundException,IOException { return getClassNode(name,false); }
 public ClassNode getClassNode(String name,boolean transform) throws ClassNotFoundException,IOException { return getClassNode(name,transform,ClassReader.EXPAND_FRAMES); }
 public ClassNode getClassNode(String name,boolean transform,int flags) throws ClassNotFoundException,IOException {
  try(InputStream in=getResourceAsStream(name.replace('.','/')+".class")) {
   if(in==null) throw new ClassNotFoundException(name);
   ClassNode node=new ClassNode(); new ClassReader(in).accept(node,flags); return node;
  }
 }
 public IMixinTransformer transformer() { return getInternal(IMixinTransformerFactory.class).createTransformer(); }
}
