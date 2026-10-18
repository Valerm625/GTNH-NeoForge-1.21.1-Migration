"""Unchanged Forge 1614 matching body; fixture item sets explicitly supplied."""
from pathlib import Path
import hashlib
import json
import zipfile
from build_legacy_energy_reference import method

R = Path(__file__).resolve().parents[2]


def build(directory):
    pin = json.loads((R / 'locks/legacy-shapeless-source.json').read_text(encoding='utf-8'))['forge']
    jar = R / 'work/cache' / pin['filename']
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == pin['sha256']
    with zipfile.ZipFile(jar) as z:
        raw = z.read(pin['entry'])
    assert hashlib.sha256(raw).hexdigest() == pin['source_sha256']
    body = method(raw.decode('utf-8'), 'public boolean func_77569_a(')
    assert hashlib.sha256(body.encode()).hexdigest() == pin['matcher_sha256']
    target = directory / 'ForgeShapelessReference.java'
    target.write_text('''package org.gtnhmigration.startupaudit;
import java.util.*;
public final class ForgeShapelessReference {
 private final ArrayList<Object> input=new ArrayList<>();
 private ForgeShapelessReference(int[] choices){for(int mask:choices){
  ArrayList<ItemStack> options=new ArrayList<>();for(int i=0;i<3;i++)if((mask&(1<<i))!=0)options.add(new ItemStack(i));
  input.add(options.size()==1?options.getFirst():options);
 }}
 public static boolean matches(int[] choices,int[] slots){return new ForgeShapelessReference(choices).func_77569_a(new InventoryCrafting(slots),null);}
''' + body + '''
 private record ItemStack(int item) {}
 private static final class InventoryCrafting {
  final int[] slots; InventoryCrafting(int[] values){slots=values.clone();}
  int func_70302_i_(){return slots.length;}
  ItemStack func_70301_a(int i){return slots[i]<0?null:new ItemStack(slots[i]);}
 }
 private static final class World {}
 private static final class OreDictionary {
  static boolean itemMatches(ItemStack expected,ItemStack actual,boolean strict){return expected!=null&&actual!=null&&expected.item==actual.item;}
 }
}
''', encoding='utf-8', newline='\n')
    return target
