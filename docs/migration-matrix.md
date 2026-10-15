# Full-scope migration matrix — NOT COMPLETE

| Area | Concrete implementation | Not proven / remaining |
|---|---|---|
| Runtime | Java21, NeoForge21.1.262, 19 JARs, 57 dependency checks | Full initialization/world/client |
| Original core items | 558 definitions/behaviors, 31 animation assets | Nine specialized items, four hazardous item behaviors, cross-mod semantics |
| Fuel blocks | 16 actual block definitions, original traits/assets/fuel, 28 compression/hammer recipes | Two charcoal base mappings; in-game harvesting, machines, save/sync |
| Railcraft | Actual modern port; original coke identity, two core machine recipes, two craft bypasses removed | Coking balance, GTNH tracks/signals/boilers/carts and progression |
| EnderStorage | Real artifact, original supported config, compiled 256-bucket adapter | Mixin application, storage behavior, ownership/frequencies, Thaumcraft gates, visuals |
| Core recipes | 167 machine +3 early-game definitions | All remaining GTNH recipe systems and progression |
| Quests | 3814/46 lossless IR, 479 stack identities mapped | 7909 identities, task/reward logic; active trial quests 2; not runtime verified |
| Graphics/audio | Authored core assets, 3 preview packs | Full atlas/models/render/audio testing |
| Tests | Static source checks, real serialization codecs, script/function contracts | End-to-end game, save/load, multi-user, full feature fidelity |

No claim of full migration or feature parity follows from passing a subset of tests.

Material phase: 249 additional static stack identities; 8 tag-preserving source bridges; explicit 9:1 tin/antimony adapter with 2 recipes. Registry/runtime/material properties and 204 RGB differences remain unverified. No full feature parity claimed.

Material phase: 249 additional static stack identities; 8 tag-preserving source bridges; explicit 9:1 tin/antimony adapter with 2 recipes. Registry/runtime/material properties and 204 RGB differences remain unverified. No full feature parity claimed.

Quest checkpoint: 2 of 3814 dependency-closed trial quests; 4 real-parser-checked SNBT files; scoped retrieval adapter, 10000 reducer observations and 4 negative fixtures. Full quest/runtime parity remains false.
