"""Fixed GT5 NBT phases, with actual isolated Minecraft 1.7.10 NBT equality."""
from pathlib import Path
import hashlib
import json
import zipfile

R = Path(__file__).resolve().parents[2]


def build(directory, shapeless=False):
    pin = json.loads((R / 'locks/legacy-crafting-nbt-source.json').read_text(encoding='utf-8'))
    if shapeless:
        other = json.loads((R / 'locks/legacy-shapeless-source.json').read_text(encoding='utf-8'))['gt5']
        pin = {**pin, **other}
    source_pin = json.loads((R / pin['source_lock']).read_text(encoding='utf-8'))
    archive = R / 'work/cache/exact-sources/GT5-Unofficial-5.09.54.205.zip'
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == source_pin['archive_sha256']
    with zipfile.ZipFile(archive) as z:
        raw = z.read('GT5-Unofficial-' + source_pin['commit'] + '/' + pin['file'])
    assert hashlib.sha256(raw).hexdigest() == pin['source_sha256']
    source = raw.decode('utf-8')
    match = source[source.index('        if (!mInputValidator.test' if shapeless else '        if (mKeepingNBT) {'):source.index('        return super.matches')]
    keep = source[source.index('            // Keeping NBT'):source.index('            // Overwrite NBT' if shapeless else '            // Charge Values')]
    assert hashlib.sha256(match.encode()).hexdigest() == pin['match_fragment_sha256']
    assert hashlib.sha256(keep.encode()).hexdigest() == pin['keep_fragment_sha256']
    legacy = R / 'work/cache/legacy-platform/minecraft-server-1.7.10.jar'
    assert hashlib.sha256(legacy.read_bytes()).hexdigest() == pin['legacy_server']['sha256']
    with zipfile.ZipFile(legacy) as z:
        for name, digest in pin['legacy_server']['classes'].items():
            assert hashlib.sha256(z.read(name)).hexdigest() == digest
    name = 'GT5ShapelessNbtReference' if shapeless else 'GT5CraftingNbtReference'
    target = directory / (name + '.java')
    text = '''package org.gtnhmigration.startupaudit;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import net.minecraft.world.item.crafting.CraftingInput;
import org.gtnhmigration.compat.LegacyItemTag;
public final class GT5CraftingNbtReference {
 private static Method read;
 public static void initialize() throws Exception {
  URLClassLoader loader=new URLClassLoader(new URL[]{Path.of("legacy-nbt-reference.jar").toUri().toURL()},null);
  read=Class.forName("du",true,loader).getMethod("a",DataInputStream.class);
 }
 public static boolean matches(CraftingInput input) {
  boolean mKeepingNBT=true; InventoryCrafting aGrid=new InventoryCrafting(input);
  java.util.function.Predicate<InventoryCrafting> mInputValidator=grid->true;
''' + match + '''
  return true;
 }
 public static byte[] keep(CraftingInput input,byte[] template) {
  boolean mKeepingNBT=true; InventoryCrafting aGrid=new InventoryCrafting(input);
  ItemStack rStack=new ItemStack(template);
''' + keep + '''
  return rStack.tag==null?null:rStack.tag.raw.clone();
 }
 static final class InventoryCrafting {
  final ItemStack[] stacks;
  InventoryCrafting(CraftingInput input){stacks=new ItemStack[input.size()];for(int i=0;i<stacks.length;i++){
   var stack=input.getItem(i);var tag=stack.get(LegacyItemTag.TYPE);
   if(!stack.isEmpty())stacks[i]=new ItemStack(tag==null?null:tag.bytes());}}
  int getSizeInventory(){return stacks.length;}
  ItemStack getStackInSlot(int i){return stacks[i];}
 }
 static final class ItemStack {
  NBTTagCompound tag;
  ItemStack(byte[] raw){if(raw!=null)tag=new NBTTagCompound(raw);}
  boolean hasTagCompound(){return tag!=null;}
  NBTTagCompound getTagCompound(){return tag;}
  void setTagCompound(NBTTagCompound value){tag=value;}
 }
 static final class NBTTagCompound {
  final byte[] raw;final Object nativeTag;
  NBTTagCompound(byte[] raw){this.raw=raw.clone();try{nativeTag=read.invoke(null,new DataInputStream(new ByteArrayInputStream(raw)));}catch(Exception error){throw new IllegalStateException(error);}}
  Object copy(){return new NBTTagCompound(raw);}
  public boolean equals(Object other){return other instanceof NBTTagCompound tag && nativeTag.equals(tag.nativeTag);}
  public int hashCode(){return nativeTag.hashCode();}
 }
}
'''
    target.write_text(text.replace('GT5CraftingNbtReference',name), encoding='utf-8', newline='\n')
    return target, legacy
