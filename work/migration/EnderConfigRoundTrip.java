import java.nio.file.*;
import codechicken.lib.config.*;
/** Use actual CodeChickenLib serializer and parser, without loading a game world. */
public class EnderConfigRoundTrip {
  public static void main(String[] args)throws Exception{
    Path file=Path.of(args[0]);
    ConfigCategory c=new ConfigFile("enderstorage").path(file).load();
    if (args.length < 2 || !args[1].equals("--read-only")) {
    c.getValue("personalItem").setDefaultString("minecraft:diamond").setString("minecraft:diamond");
    c.getValue("anarchyMode").setDefaultBoolean(false).setBoolean(false);
    c.getValue("item_storage_size").setDefaultInt(1).setInt(1);
    c.getValue("disableCreatorVisuals").setDefaultBoolean(false).setBoolean(false);
    c.getValue("useVanillaEnderChestsSounds").setDefaultBoolean(false).setBoolean(false);
    c.save();
    }
    ConfigCategory r=new ConfigFile("enderstorage").path(file).load();
    if(!r.getValue("personalItem").getString().equals("minecraft:diamond")||r.getValue("anarchyMode").getBoolean()||r.getValue("item_storage_size").getInt()!=1)throw new AssertionError("Config roundtrip mismatch");
    System.out.println("{\"test\":\"actual_codechicken_config_roundtrip\",\"passed\":true,\"personal_item\":\"minecraft:diamond\",\"anarchy\":false,\"storage_size\":1,\"game_mod_initialization_tested\":false}");
  }
}
