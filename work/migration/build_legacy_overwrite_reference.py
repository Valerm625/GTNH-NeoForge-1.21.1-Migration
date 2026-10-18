"""Unchanged fixed GT5 overwrite/identity expressions using real old NBT objects."""
from pathlib import Path
import hashlib
import json
import zipfile

R = Path(__file__).resolve().parents[2]


def build(directory):
    pin = json.loads((R / 'locks/legacy-crafting-overwrite-source.json').read_text(encoding='utf-8'))
    source = json.loads((R / pin['source_lock']).read_text(encoding='utf-8'))
    archive = R / 'work/cache/exact-sources/GT5-Unofficial-5.09.54.205.zip'
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == source['archive_sha256']
    values = {}
    with zipfile.ZipFile(archive) as z:
        for key in ('shapeless', 'utility', 'builder'):
            raw = z.read('GT5-Unofficial-' + source['commit'] + '/' + pin[key + '_file'])
            assert hashlib.sha256(raw).hexdigest() == pin[key + '_sha256']
            values[key] = raw.decode('utf-8')
    s, u = values['shapeless'], values['utility']
    assert 'public static final int WILDCARD = 32767;' in values['builder']
    overwrite = s[s.index('            // Overwrite NBT'):s.index('            // Charge Values')]
    keep = s[s.index('            // Keeping NBT'):s.index('            // Overwrite NBT')]
    start = u.index('    public static boolean areStacksEqual(ItemStack aStack1, ItemStack aStack2, boolean aIgnoreNBT)')
    identity = u[start:u.index('\n    public static boolean areStacksEqualOrNull', start)]
    assert hashlib.sha256(overwrite.encode()).hexdigest() == pin['overwrite_fragment_sha256']
    assert hashlib.sha256(identity.encode()).hexdigest() == pin['identity_fragment_sha256']
    legacy = R / 'work/cache/legacy-platform/minecraft-server-1.7.10.jar'
    assert hashlib.sha256(legacy.read_bytes()).hexdigest() == pin['legacy_server']['sha256']
    with zipfile.ZipFile(legacy) as z:
        for name, digest in pin['legacy_server']['classes'].items():
            assert hashlib.sha256(z.read(name)).hexdigest() == digest
    target = directory / 'GT5OverwriteReference.java'
    text = '''package org.gtnhmigration.startupaudit;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
public final class GT5OverwriteReference {
 private static Method read,copy,set,type,writePayload;
 private static Field tagMap,listValues,listType;
 public record Fixture(String item,int damage,byte[] tag) {}
 public static void initialize() throws Exception {
  URLClassLoader loader=new URLClassLoader(new URL[]{Path.of("legacy-nbt-reference.jar").toUri().toURL()},null);
  Class<?> compound=Class.forName("dh",true,loader),base=Class.forName("dy",true,loader),list=Class.forName("dq",true,loader);
  read=Class.forName("du",true,loader).getMethod("a",DataInputStream.class);
  copy=base.getMethod("b");type=base.getMethod("a");
  set=compound.getMethod("a",String.class,base);
  tagMap=compound.getDeclaredField("c");tagMap.setAccessible(true);
  listValues=list.getDeclaredField("b");listValues.setAccessible(true);
  listType=list.getDeclaredField("c");listType.setAccessible(true);
  writePayload=base.getDeclaredMethod("a",DataOutput.class);writePayload.setAccessible(true);
 }
 private static Object decode(byte[] raw){try{return read.invoke(null,new DataInputStream(new ByteArrayInputStream(raw)));}catch(Exception e){throw new IllegalStateException(e);}}
 public static byte[] canonical(byte[] raw){return raw==null?null:canonicalNative(decode(raw));}
 private static byte[] canonicalNative(Object tag){try{var bytes=new ByteArrayOutputStream();canonicalNode(tag,new DataOutputStream(bytes));return bytes.toByteArray();}catch(Exception e){throw new IllegalStateException(e);}}
 @SuppressWarnings("unchecked")
 private static void canonicalNode(Object tag,DataOutputStream out)throws Exception {
  int id=((Number)type.invoke(tag)).intValue();out.writeByte(id);
  if(id==10){Map<String,Object> map=(Map<String,Object>)tagMap.get(tag);out.writeInt(map.size());for(String key:new TreeSet<>(map.keySet())){out.writeUTF(key);canonicalNode(map.get(key),out);}}
  else if(id==9){out.writeByte(listType.getByte(tag));List<?> values=(List<?>)listValues.get(tag);out.writeInt(values.size());for(Object value:values)canonicalNode(value,out);}
  else writePayload.invoke(tag,out);
 }
 public static byte[] apply(List<Fixture> inputs,Fixture output,boolean mKeepingNBT) {
  InventoryCrafting aGrid=new InventoryCrafting(inputs);ItemStack rStack=new ItemStack(output);boolean overwriteNBT=true;
''' + keep + overwrite + '''
  return rStack.tag==null?null:canonicalNative(rStack.tag.nativeTag);
 }
 public static boolean sameIdentity(Fixture a,Fixture b){return GTUtility.areStacksEqual(a==null?null:new ItemStack(a),b==null?null:new ItemStack(b),true);}
 static final class GTUtility {
  private static final int WILDCARD=32767;
''' + identity + '''
 }
 static final class Items {static final DamageReader feather=new DamageReader();}
 static final class DamageReader {int getDamage(ItemStack stack){return stack.damage;}}
 static final class InventoryCrafting {
  final ItemStack[] stacks;
  InventoryCrafting(List<Fixture> values){stacks=new ItemStack[values.size()];for(int i=0;i<stacks.length;i++)if(values.get(i)!=null)stacks[i]=new ItemStack(values.get(i));}
  int getSizeInventory(){return stacks.length;}
  ItemStack getStackInSlot(int slot){return stacks[slot];}
 }
 static final class ItemStack {
  final String item;final int damage;NBTTagCompound tag;
  ItemStack(Fixture value){item=value.item().intern();damage=value.damage();if(value.tag()!=null)tag=new NBTTagCompound(decode(value.tag()));}
  Object getItem(){return item;}
  boolean hasTagCompound(){return tag!=null;}
  NBTTagCompound getTagCompound(){return tag;}
  void setTagCompound(NBTTagCompound value){tag=value;}
 }
 static class NBTBase {final Object nativeTag;NBTBase(Object value){nativeTag=value;}}
 static final class NBTTagCompound extends NBTBase {
  final Map<String,NBTBase> tagMap=new HashMap<>();
  @SuppressWarnings("unchecked") NBTTagCompound(Object value){super(value);try{for(var entry:((Map<String,Object>)GT5OverwriteReference.tagMap.get(value)).entrySet())tagMap.put(entry.getKey(),new NBTBase(entry.getValue()));}catch(Exception e){throw new IllegalStateException(e);}}
  Object copy(){try{return new NBTTagCompound(GT5OverwriteReference.copy.invoke(nativeTag));}catch(Exception e){throw new IllegalStateException(e);}}
  void setTag(String key,NBTBase value){try{set.invoke(nativeTag,key,value.nativeTag);tagMap.put(key,value);}catch(Exception e){throw new IllegalStateException(e);}}
  public boolean equals(Object other){return other instanceof NBTTagCompound tag&&nativeTag.equals(tag.nativeTag);}
  public int hashCode(){return nativeTag.hashCode();}
 }
}
'''
    target.write_text(text, encoding='utf-8', newline='\n')
    return target
