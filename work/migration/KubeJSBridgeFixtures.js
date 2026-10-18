// Isolated fixtures; never installed into the Foundation pack.
ServerEvents.recipes(event => {
  var r = event.recipes.gtceu.assembler('gtnh_audit:directions')
    .inputItems('2x minecraft:iron_ingot')
    .notConsumableItem('minecraft:stick')
    .outputItems('3x minecraft:gold_ingot')
    .inputFluids(Fluid.of('minecraft:water', 250))
    .outputFluids(Fluid.of('minecraft:lava', 500))
    .chancedOutput('2x minecraft:diamond', 2500, 750)
    .chancedFluidOutput(Fluid.of('minecraft:water', 100), 4000, 200)
    .duration(123).EUt(32, 2).dimension('minecraft:overworld');
  r.perTick(true);
  r.inputItems('minecraft:redstone').outputItems('2x minecraft:glowstone_dust');
  r.inputFluids(Fluid.of('minecraft:water', 10)).outputFluids(Fluid.of('minecraft:lava', 20));
  r.perTick(false);
  event.recipes.gtceu.assembler('gtnh_audit:generator')
    .inputItems('minecraft:coal').outputItems('minecraft:charcoal')
    .duration(40).EUt(-128, 2);
  event.recipes.gtceu.assembler('gtnh_audit:fraction')
    .inputItems('minecraft:iron_ingot')
    .chancedOutput('minecraft:diamond', '1/3', 300)
    .outputItems('minecraft:gold_ingot').duration(80).EUt(8, 1);
  // Replacement events intentionally target a pre-existing datapack recipe.
  if (!event.containsRecipe({id: 'gtnh_audit:assembler/replacement', input: 'minecraft:iron_ingot', output: 'minecraft:diamond'})) {
    throw new Error('Capability map input/output filters failed');
  }
  event.replaceOutput({id: 'gtnh_audit:assembler/replacement'}, 'minecraft:diamond', 'minecraft:emerald');
  event.replaceInput({id: 'gtnh_audit:assembler/replacement'}, 'minecraft:iron_ingot', 'minecraft:copper_ingot');
  event.recipes.gtceu.shaped('minecraft:iron_nugget', ['hI'], {I: 'minecraft:iron_ingot'})
    .noMirror().noShrink().id('gtnh_audit:tool_shaped');
});
