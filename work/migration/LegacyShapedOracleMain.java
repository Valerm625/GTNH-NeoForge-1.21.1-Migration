package org.gtnhmigration.audit.legacy;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.ShapedOreRecipe;

/** Runs the unedited pinned Forge source with geometry-only inventory/item stubs. */
public final class LegacyShapedOracleMain {
    private record Case(String name, List<String> rows, boolean mirror) {}
    public static void main(String[] args) throws Exception {
        List<Case> cases = List.of(
            new Case("center", List.of("   ", " A ", "   "), false),
            new Case("right", List.of("  A"), false),
            new Case("right_mirror", List.of("  A"), true),
            new Case("leading", List.of(" A"), false),
            new Case("leading_mirror", List.of(" A"), true),
            new Case("vertical", List.of("A", " ", "B"), false),
            new Case("pair", List.of("AB"), false),
            new Case("pair_mirror", List.of("AB"), true),
            new Case("offset", List.of(" A ", " B "), false)
        );
        JsonArray output = new JsonArray();
        int count = 0;
        for (Case row : cases) {
            boolean usesB = row.rows.stream().anyMatch(s -> s.contains("B"));
            List<Object> definition = new ArrayList<>();
            definition.add(row.rows.toArray(String[]::new));
            definition.add('A'); definition.add(new ItemStack("A"));
            if (usesB) { definition.add('B'); definition.add(new ItemStack("B")); }
            ShapedOreRecipe recipe = new ShapedOreRecipe(new ItemStack("result"), definition.toArray()).setMirrored(row.mirror);
            JsonObject entry = new JsonObject();
            entry.addProperty("name", row.name); entry.addProperty("mirror", row.mirror);
            JsonArray pattern = new JsonArray(); row.rows.forEach(pattern::add); entry.add("pattern", pattern);
            JsonArray grids = new JsonArray();
            for (int size : new int[]{2,3,4}) {
                int slots = size*size;
                for (int a=-1; a<slots; a++) {
                    for (int b=-1; b<slots; b++) {
                        if (a>=0 && a==b) continue;
                        List<String> items = new ArrayList<>(Collections.nCopies(slots, ""));
                        if(a>=0) items.set(a,"A");
                        if(b>=0) items.set(b,"B");
                        JsonObject grid = new JsonObject(); grid.addProperty("size",size);
                        JsonArray content = new JsonArray(); items.forEach(content::add); grid.add("items",content);
                        grid.addProperty("matches", recipe.func_77569_a(new InventoryCrafting(size,size,items), null));
                        grids.add(grid); count++;
                    }
                }
            }
            entry.add("grids",grids); output.add(entry);
        }
        JsonObject report = new JsonObject(); report.add("cases",output); report.addProperty("comparisons",count);
        Files.writeString(Path.of(args[0]),new Gson().toJson(report));
        System.out.println("LEGACY_SHAPED_ORACLE="+count);
    }
}
