"""Compile unedited Forge 1614 shaped matching with explicit geometry-only stubs."""
from pathlib import Path
import hashlib
import json
import subprocess
import urllib.request
import uuid
import zipfile

R = Path(__file__).resolve().parents[2]


def build():
    lock = json.loads((R / 'locks/legacy-shaped-source.json').read_text(encoding='utf-8'))
    archive = R / 'work/cache' / lock['filename']
    if not archive.exists():
        content = urllib.request.urlopen(lock['url'], timeout=60).read()
        assert hashlib.sha256(content).hexdigest() == lock['sha256']
        archive.write_bytes(content)
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == lock['sha256']
    run = R / 'work/migration/shaped-behavior-check' / uuid.uuid4().hex
    sources, classes = run / 'sources', run / 'classes'
    classes.mkdir(parents=True)
    with zipfile.ZipFile(archive) as z:
        value = z.read(lock['entry'])
    assert hashlib.sha256(value).hexdigest() == lock['source_sha256']
    files = {'net/minecraftforge/oredict/ShapedOreRecipe.java': value.decode('utf-8')}
    files.update({
        'net/minecraft/item/Item.java': 'package net.minecraft.item; public class Item {}',
        'net/minecraft/block/Block.java': 'package net.minecraft.block; public class Block {}',
        'net/minecraft/world/World.java': 'package net.minecraft.world; public class World {}',
        'net/minecraft/item/ItemStack.java': '''package net.minecraft.item;
public class ItemStack {
 public final String token;
 public ItemStack(String token){this.token=token;}
 public ItemStack(Item item){this("unused Item constructor");}
 public ItemStack(net.minecraft.block.Block block){this("unused Block constructor");}
 public ItemStack(net.minecraft.block.Block block,int count,int meta){this(block);}
 public ItemStack func_77946_l(){return new ItemStack(token);}
}''',
        'net/minecraft/inventory/InventoryCrafting.java': '''package net.minecraft.inventory;
import java.util.List;import net.minecraft.item.ItemStack;
public class InventoryCrafting {
 private final int width,height;private final List<String> slots;
 public InventoryCrafting(int width,int height,List<String> slots){this.width=width;this.height=height;this.slots=slots;}
 public ItemStack func_70463_b(int x,int y){if(x<0||x>=width||y<0||y>=height)return null;String s=slots.get(x+y*width);return s.isEmpty()?null:new ItemStack(s);}
}''',
        'net/minecraft/item/crafting/IRecipe.java': '''package net.minecraft.item.crafting;
import net.minecraft.inventory.InventoryCrafting;import net.minecraft.item.ItemStack;import net.minecraft.world.World;
public interface IRecipe {ItemStack func_77572_b(InventoryCrafting input);int func_77570_a();ItemStack func_77571_b();boolean func_77569_a(InventoryCrafting input,World world);}''',
        'net/minecraft/item/crafting/ShapedRecipes.java': '''package net.minecraft.item.crafting;
import net.minecraft.item.ItemStack;public class ShapedRecipes {public int field_77576_b,field_77577_c;public ItemStack[] field_77574_d;public ItemStack func_77571_b(){throw new AssertionError("Unused conversion constructor");}}''',
        'net/minecraftforge/oredict/OreDictionary.java': '''package net.minecraftforge.oredict;
import java.util.ArrayList;import net.minecraft.item.ItemStack;
public class OreDictionary {public static final int WILDCARD_VALUE=32767;
 public static ArrayList<ItemStack> getOres(String name){throw new AssertionError("Geometry oracle does not model ore membership");}
 public static boolean itemMatches(ItemStack expected,ItemStack actual,boolean strict){return expected!=null&&actual!=null&&expected.token.equals(actual.token);}}
'''
    })
    paths = []
    for name, text in files.items():
        path = sources / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8', newline='\n')
        paths.append(path)
    main = R / 'work/migration/LegacyShapedOracleMain.java'
    gson = next((R / 'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation/libraries/com/google/code/gson/gson').glob('*/*.jar'))
    java = Path(json.loads((R / 'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
    result = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(gson),
                             '-d', str(classes), *map(str, paths), str(main)], capture_output=True, timeout=60)
    (run / 'compile.log').write_bytes(result.stdout + result.stderr)
    assert result.returncode == 0, result.stderr.decode(errors='replace')
    output = run / 'oracle.json'
    result = subprocess.run([str(java), '-cp', str(classes)+';'+str(gson),
                             'org.gtnhmigration.audit.legacy.LegacyShapedOracleMain', str(output)],
                            capture_output=True, timeout=60)
    assert result.returncode == 0, result.stderr.decode(errors='replace')
    print(result.stdout.decode().strip(), flush=True)
    return output


if __name__ == '__main__':
    print(build())
