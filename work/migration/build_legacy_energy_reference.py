"""Extract unchanged GT5 energy expressions, bridging only item APIs to modern GT.

This compares the old energy phase against real modern capabilities; it does not
run IC2, legacy NBT updates, enchantments, world crafting or legacy item classes.
"""
from pathlib import Path
import hashlib
import json
import zipfile

R = Path(__file__).resolve().parents[2]


def method(source, signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def build(directory):
    pin = json.loads((R / 'locks/legacy-crafting-energy-source.json').read_text(encoding='utf-8'))
    archive_pin = json.loads((R / pin['source_lock']).read_text(encoding='utf-8'))
    archive = R / 'work/cache/exact-sources/GT5-Unofficial-5.09.54.205.zip'
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == archive_pin['archive_sha256']
    sources = {}
    with zipfile.ZipFile(archive) as z:
        prefix = 'GT5-Unofficial-' + archive_pin['commit'] + '/'
        for name, digest in pin['files'].items():
            data = z.read(prefix + name)
            assert hashlib.sha256(data).hexdigest() == digest, name
            sources[name] = data.decode('utf-8')
    shaped = sources['src/main/java/gregtech/api/util/GTShapedRecipe.java']
    energy = shaped[shaped.index('            // Charge Values'):shaped.index('            // Add Enchantments')]
    assert hashlib.sha256(energy.encode()).hexdigest() == pin['energy_fragment_sha256']
    handler = sources['src/main/java/gregtech/api/util/GTModHandler.java']
    methods = '\n'.join(method(handler, name) for name in pin['methods'])
    assert hashlib.sha256(methods.encode()).hexdigest() == pin['methods_sha256']
    target = directory / 'GT5CraftingEnergyReference.java'
    target.write_text('''package org.gtnhmigration.startupaudit;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.item.IGTTool;
import net.minecraft.world.item.crafting.CraftingInput;
/** Unchanged pinned GT5 expression bodies; only item/capability APIs are bridged. */
public final class GT5CraftingEnergyReference {
 public static net.minecraft.world.item.ItemStack apply(CraftingInput input, net.minecraft.world.item.ItemStack output) {
  InventoryCrafting aGrid = new InventoryCrafting(input);
  ItemStack rStack = new ItemStack(output.copy());
''' + energy + '''
  return rStack.value;
 }
 static final class InventoryCrafting {
  final ItemStack[] stacks;
  InventoryCrafting(CraftingInput input) { stacks=new ItemStack[input.size()];
   for(int i=0;i<stacks.length;i++) stacks[i]=new ItemStack(input.getItem(i).copy()); }
  int getSizeInventory(){return stacks.length;}
  ItemStack getStackInSlot(int i){return stacks[i];}
 }
 static final class ItemStack {
  final net.minecraft.world.item.ItemStack value;
  final com.gregtechceu.gtceu.api.capability.IElectricItem capability;
  final Object identity;
  ItemStack(net.minecraft.world.item.ItemStack value){this.value=value;
   capability=value.isEmpty()?null:GTCapabilityHelper.getElectricItem(value);
   identity=capability==null?new Object():value.getItem() instanceof IGTTool?new MetaGeneratedTool():new ElectricIdentity(); }
  Object getItem(){return identity;}
 }
 static class ElectricIdentity implements ic2.api.item.IElectricItem {
  public int getTier(ItemStack stack){return stack.capability.getTier();}
 }
 static final class MetaGeneratedTool extends ElectricIdentity {}
 static final class GTModHandler {
  // These arrays are never read: the source crafting phase ignores transfer limits.
  static final long[] V=new long[0],B=new long[0];
''' + methods + '''
 }
 static final class ic2 {static final class api {static final class item {
  interface IElectricItem {int getTier(ItemStack stack);}
  static final class ElectricItem {static final Manager manager=new Manager();}
  static final class Manager {
   double discharge(ItemStack stack,double amount,int tier,boolean ignore,boolean externally,boolean simulate){
    return stack.capability.discharge((long)amount,tier,ignore,externally,simulate);}
   double charge(ItemStack stack,double amount,int tier,boolean ignore,boolean simulate){
    return stack.capability.charge((long)amount,tier,ignore,simulate);}
  }
 }}}
}
''', encoding='utf-8', newline='\n')
    return target
