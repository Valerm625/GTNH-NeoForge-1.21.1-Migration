/** Isolated test service for the real pinned Sponge Mixin transformer.
 * Not NeoForge's ModLauncher service and never installed in the game mod. */
import java.util.*;
import org.spongepowered.asm.service.*;
public final class OfflineProperties implements IGlobalPropertyService {
 record Key(String name) implements IPropertyKey {}
 final Map<IPropertyKey,Object> values=new HashMap<>();
 public IPropertyKey resolveKey(String name) { return new Key(name); }
 @SuppressWarnings("unchecked") public <T>T getProperty(IPropertyKey key) { return (T)values.get(key); }
 public void setProperty(IPropertyKey key,Object value) { values.put(key,value); }
 public <T>T getProperty(IPropertyKey key,T fallback) { T value=getProperty(key); return value==null?fallback:value; }
 public String getPropertyString(IPropertyKey key,String fallback) { Object value=values.get(key); return value==null?fallback:value.toString(); }
}
