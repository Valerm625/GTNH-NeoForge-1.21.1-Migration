# Full-scope migration matrix — NOT COMPLETE

| Area | Concrete implementation | Not proven / remaining |
| --- | --- | --- |
| Runtime | Java21, NeoForge21.1.262, 19 JARs, 58 dependency checks | Full initialization/world/client |
| Original core items | 558 definitions/behaviors, 31 animation assets | Nine specialized items, four hazardous item behaviors, cross-mod semantics |
| Fuel blocks | 16 actual block definitions, original traits/assets/fuel, 28 compression/hammer recipes | In-game harvesting, machines, save/sync |
| Railcraft | Modern artifact, original coke identity, two core machine recipes, two craft bypasses removed | Coking balance, GTNH tracks/signals/boilers/carts and progression |
| EnderStorage | Real artifact, original supported config, compiled 256-bucket adapter | Mixin application, storage behavior, ownership/frequencies, Thaumcraft gates, visuals |
| Core recipes | 167 machine +3 early-game definitions | All remaining GTNH recipe systems and progression |
| Item identities | 720/8388 mapped; 241 new explicit vanilla identities in this phase | 7668 identities, NBT/ore-dictionary/potion/entity semantics |
| Quests | 3814/46 lossless IR, 43 structural candidates (2 crafting-task candidates), 2 trial quests exported | 3812 quests, task/reward logic, runtime initialization |
| Graphics/audio | Authored core assets, 3 preview packs | Full atlas/models/render/audio testing |
| Tests | Static source checks, real serialization codecs, parser and script/function contracts | End-to-end game, save/load, multi-user, full feature fidelity |

No claim of full migration or feature parity follows from passing a subset of tests.

Vanilla identity checkpoint: 241 explicit source identities (4905 occurrences) resolve against 1333 actual 1.21.1 Items fields; NBT, ore-dictionary, potion and entity-specific identities remain blocked.

Quest checkpoint: 2 of 3814 dependency-closed trial quests; 4 real-parser-checked SNBT files; scoped retrieval adapter, 10000 reducer observations and 4 negative fixtures. Full quest/runtime parity remains false.
