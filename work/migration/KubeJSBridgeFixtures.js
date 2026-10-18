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

// Actual GT items and the explicitly opted-in legacy energy phase.
ServerEvents.recipes(event => {
  var inputs = ['gtceu:lv_lithium_battery', 'gtceu:mv_lithium_battery', 'gtceu:hv_lithium_battery', 'gtceu:lv_steel_drill', 'minecraft:iron_ingot'].map(id => ({item:id}));
  ['legacy', 'disabled', 'plain'].forEach(name => {
    var result = name === 'plain' ? {id:'minecraft:diamond',count:1} : {
      id:'gtceu:lv_lithium_battery', count:1, components:{
        'gtceu:energy_content':{max_charge:200000,charge:83,infinite:false,discharge_mode:true}
      }
    };
    event.custom({type:'gtnh_migration:legacy_shaped', category:'misc', pattern:['ABC'],
      key:{A:inputs,B:inputs,C:inputs},result:result,'kubejs:shrink':false,
      'kubejs:mirror':false,'gtnh:legacy_energy':name!=='disabled'}).id('gtnh_audit:energy_'+name);
  });
});

// Ordered legacy shapeless matching and explicitly selected GT5 phases.
ServerEvents.recipes(event => {
  event.custom({type:'gtnh_migration:legacy_shapeless',category:'misc',
    ingredients:[[{item:'minecraft:iron_ingot'},{item:'minecraft:gold_ingot'}],{item:'minecraft:iron_ingot'}],
    result:{id:'minecraft:diamond',count:1}}).id('gtnh_audit:shapeless_overlap');
  event.custom({type:'gtnh_migration:legacy_shapeless',category:'misc',
    ingredients:[{item:'minecraft:iron_ingot'},{item:'minecraft:iron_ingot'}],
    result:{id:'minecraft:diamond',count:2,components:{'gtnh_migration:legacy_item_tag':'CgAAAQAIdGVtcGxhdGUHAA==','minecraft:custom_name':'"template"'}},
    'gtnh:keep_legacy_nbt':true}).id('gtnh_audit:shapeless_nbt');
  var batteries=[{item:'gtceu:lv_lithium_battery'},{item:'gtceu:mv_lithium_battery'},{item:'gtceu:lv_steel_drill'}];
  event.custom({type:'gtnh_migration:legacy_shapeless',category:'misc',ingredients:[batteries,batteries,batteries],
    result:{id:'gtceu:lv_lithium_battery',count:1,components:{'gtceu:energy_content':{max_charge:200000,charge:83,infinite:false,discharge_mode:true}}},
    'gtnh:legacy_energy':true}).id('gtnh_audit:shapeless_energy');
  event.custom({type:'gtnh_migration:legacy_shapeless',category:'misc',ingredients:[{item:'minecraft:milk_bucket'},{item:'minecraft:iron_ingot'}],
    result:{id:'minecraft:clay_ball',count:1}}).id('gtnh_audit:shapeless_bucket');
});

// The expected positions come from unedited Forge 10.13.4.1614 source.
ServerEvents.recipes(event => {
  ['keep','disabled'].forEach(name => {
    event.custom({type:'gtnh_migration:legacy_shaped',category:'misc',pattern:['AB'],
      key:{A:{item:'minecraft:iron_ingot'},B:{item:'minecraft:iron_ingot'}},
      result:{id:'minecraft:diamond',count:1,components:{'gtnh_migration:legacy_item_tag':'CgAAAQAIdGVtcGxhdGUHAA==','minecraft:custom_name':'"template"'}},
      'kubejs:shrink':false,'kubejs:mirror':false,'gtnh:keep_legacy_nbt':name==='keep'}).id('gtnh_audit:nbt_'+name);
  });
});

ServerEvents.recipes(event => {
  var cases = [
    ['center', ['   ', ' A ', '   '], false], ['right', ['  A'], false],
    ['right_mirror', ['  A'], true], ['leading', [' A'], false],
    ['leading_mirror', [' A'], true], ['vertical', ['A', ' ', 'B'], false],
    ['pair', ['AB'], false], ['pair_mirror', ['AB'], true], ['offset', [' A ', ' B '], false]
  ];
  cases.forEach(c => {
    var keys = {A: 'minecraft:iron_ingot'};
    if (c[1].join('').indexOf('B') >= 0) keys.B = 'minecraft:gold_ingot';
    var r = event.recipes.gtceu.shaped('minecraft:diamond', c[1], keys).kjsMirror(c[2]).kjsCategory('misc');
    if (c[0] === 'right') r.kjsShrink(false); else r.noShrink();
    r.id('gtnh_audit:shape_' + c[0]);
  });
  event.recipes.gtceu.shaped('minecraft:diamond', ['   ', ' A ', '   '], {A:'minecraft:iron_ingot'})
    .noMirror().id('gtnh_audit:shape_default');
  event.recipes.gtceu.shaped('minecraft:diamond', ['   ', ' A ', '   '], {A:'minecraft:iron_ingot'})
    .noMirror().noShrink().kjsShrink(true).id('gtnh_audit:shape_reset');
  event.recipes.gtceu.shaped('minecraft:clay_ball', ['   ', ' M ', '   '], {M:'minecraft:milk_bucket'})
    .noShrink().noMirror().id('gtnh_audit:shape_bucket');
  event.recipes.gtceu.shaped('minecraft:clay_ball', ['   ', ' M ', '   '], {M:'minecraft:milk_bucket'})
    .noShrink().noMirror().keepIngredient('minecraft:milk_bucket').id('gtnh_audit:shape_keep');
});
