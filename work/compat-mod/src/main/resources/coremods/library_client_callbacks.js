// Pinned LDLib 1.0.35.a / GTCEu 7.0.2 client callbacks used from shared classes.
// Add missing physical-side annotations without changing callback instructions.
// Keep shared scripting methods and guard only LDLib's client annotation check.
function initializeCoreMod() {
    var Op = Java.type('org.objectweb.asm.Opcodes');
    var Field = Java.type('org.objectweb.asm.tree.FieldInsnNode');
    var Call = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var Jump = Java.type('org.objectweb.asm.tree.JumpInsnNode');
    var Instructions = Java.type('org.objectweb.asm.tree.InsnList');
    var Label = Java.type('org.objectweb.asm.tree.LabelNode');
    var onlyIn = 'Lnet/neoforged/api/distmarker/OnlyIn;';
    var callbacks = {
        'com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup': ['drawOverlay'],
        'com.lowdragmc.lowdraglib.gui.widget.PhantomFluidWidget': ['drawInBackground'],
        'com.lowdragmc.lowdraglib.gui.widget.SceneWidget': ['lambda$setBeforeWorldRender$0'],
        'com.gregtechceu.gtceu.api.fluid.FluidBuilder': ['lambda$makeFluidType$14', 'lambda$build$10', 'lambda$build$5'],
        'com.gregtechceu.gtceu.data.block.GTMaterialBlocks': ['lambda$registerOreBlock$12', 'lambda$registerMaterialBlock$5'],
        'com.gregtechceu.gtceu.data.item.GTMaterialItems': ['lambda$generateArmor$14', 'lambda$generateArmor$12', 'lambda$generateMaterialItem$4'],
        'com.gregtechceu.gtceu.data.item.GTItems': ['lambda$addArmorClientExtensions$177', 'lambda$modelPredicate$174', 'lambda$static$105', 'lambda$static$104']
    };
    function one(items, message) {
        if (items.length !== 1) throw new Error('GTNH library client callbacks: ' + message + ' (' + items.length + ')');
        return items[0];
    }
    var transforms = {};
    Object.keys(callbacks).forEach(function(target) {
        transforms[target] = {
            target: {type: 'CLASS', name: target},
            transformer: function(node) {
                callbacks[target].forEach(function(name) {
                    var m = one(Java.from(node.methods).filter(function(m) { return m.name === name; }), name);
                    if (m.desc.indexOf('/client/') === -1) throw new Error('GTNH library client callbacks: unexpected descriptor ' + name);
                    if (m.visibleAnnotations !== null && Java.from(m.visibleAnnotations).some(function(a) { return a.desc === onlyIn; }))
                        throw new Error('GTNH library client callbacks: annotation already present ' + name);
                    var a = m.visitAnnotation(onlyIn, true);
                    a.visitEnum('value', 'Lnet/neoforged/api/distmarker/Dist;', 'CLIENT'); a.visitEnd();
                });
                if (target === 'com.gregtechceu.gtceu.api.fluid.FluidBuilder') {
                    // The client event never fires on the dedicated server. Guard
                    // creation of its consumer too, before resolving its handle.
                    var m = one(Java.from(node.methods).filter(function(m) { return m.name === 'makeFluidType'; }), 'fluid type factory');
                    var call = one(Java.from(m.instructions.toArray()).filter(function(i) {
                        return i.getType() === 5 && i.owner === 'com/tterrag/registrate/util/OneTimeEventReceiver' && i.name === 'addModListener';
                    }), 'fluid client event listener');
                    var seq = [], i = call;
                    for (var count = 0; count < 8; count++) { seq.unshift(i); i = i.getPrevious(); }
                    if (seq[0].getOpcode() !== Op.ALOAD || seq[0]['var'] !== 1 ||
                        seq[1].getOpcode() !== Op.LDC || String(seq[1].cst) !== 'Lnet/neoforged/neoforge/client/extensions/common/RegisterClientExtensionsEvent;' ||
                        seq[2].getOpcode() !== Op.ALOAD || seq[2]['var'] !== 0 ||
                        seq[3].getOpcode() !== Op.ALOAD || seq[3]['var'] !== 3 ||
                        seq[4].getOpcode() !== Op.ALOAD || seq[4]['var'] !== 4 ||
                        seq[5].getOpcode() !== Op.ALOAD || seq[5]['var'] !== 6 ||
                        seq[6].getOpcode() !== Op.INVOKEDYNAMIC || seq[6].bsmArgs[1].getName() !== 'lambda$makeFluidType$14')
                        throw new Error('GTNH library client callbacks: changed fluid listener layout');
                    var end = new Label(), guard = new Instructions();
                    guard.add(new Field(Op.GETSTATIC, 'net/neoforged/fml/loading/FMLEnvironment', 'dist', 'Lnet/neoforged/api/distmarker/Dist;'));
                    guard.add(new Call(Op.INVOKEVIRTUAL, 'net/neoforged/api/distmarker/Dist', 'isClient', '()Z', false));
                    guard.add(new Jump(Op.IFEQ, end));
                    m.instructions.insertBefore(seq[0], guard);
                    m.instructions.insert(call, end);
                }
                return node;
            }
        };
    });
    transforms['ldlib_client_annotation_guard'] = {
        target: {type: 'CLASS', name: 'com.lowdragmc.lowdraglib.gui.editor.runtime.AnnotationDetector'},
        transformer: function(node) {
            var m = one(Java.from(node.methods).filter(function(m) {
                return m.name === 'checkNoArgsConstructor' && m.desc === '(Ljava/lang/Object;Ljava/lang/Class;)Z';
            }), 'shared constructor predicate');
            var check = one(Java.from(m.instructions.toArray()).filter(function(i) {
                return i.getOpcode() === Op.INSTANCEOF && i.desc === 'com/lowdragmc/lowdraglib/gui/editor/annotation/LDLRegisterClient';
            }), 'client annotation instanceof');
            var load = check.getPrevious(), branch = check.getNext();
            if (load.getOpcode() !== Op.ALOAD || load['var'] !== 0 || branch.getOpcode() !== Op.IFEQ)
                throw new Error('GTNH library client callbacks: changed annotation predicate layout');
            if (Java.from(m.instructions.toArray()).some(function(i) { return i.getType() === 5 && i.name === 'isClient'; }))
                throw new Error('GTNH library client callbacks: duplicate side guard');
            var guard = new Instructions();
            guard.add(new Field(Op.GETSTATIC, 'net/neoforged/fml/loading/FMLEnvironment', 'dist', 'Lnet/neoforged/api/distmarker/Dist;'));
            guard.add(new Call(Op.INVOKEVIRTUAL, 'net/neoforged/api/distmarker/Dist', 'isClient', '()Z', false));
            guard.add(new Jump(Op.IFEQ, branch.label));
            m.instructions.insertBefore(load, guard);
            return node;
        }
    };
    return transforms;
}
