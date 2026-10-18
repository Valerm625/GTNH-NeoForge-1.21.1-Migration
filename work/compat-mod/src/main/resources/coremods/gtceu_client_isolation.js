// GTCEu 7.0.2 (1.21.1): move existing client instructions into @OnlyIn helpers.
// The original scene and lamp rendering instructions are retained, not replaced.
// RuntimeDistCleaner runs AFTER coremods and removes only the client helpers on
// a dedicated server. Input artifact/class hashes are checked by the build.
function initializeCoreMod() {
    var Op = Java.type('org.objectweb.asm.Opcodes');
    var Method = Java.type('org.objectweb.asm.tree.MethodNode');
    var Insn = Java.type('org.objectweb.asm.tree.InsnNode');
    var Var = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var Call = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var Field = Java.type('org.objectweb.asm.tree.FieldInsnNode');
    var Jump = Java.type('org.objectweb.asm.tree.JumpInsnNode');
    var Label = Java.type('org.objectweb.asm.tree.LabelNode');
    var List = Java.type('org.objectweb.asm.tree.InsnList');
    var group = 'Lcom/lowdragmc/lowdraglib/gui/widget/WidgetGroup;';

    function requireOne(items, message) {
        if (items.length !== 1) throw new Error('GTNH client isolation: ' + message + ' (' + items.length + ')');
        return items[0];
    }
    function findMethod(node, name, desc) {
        return requireOne(Java.from(node.methods).filter(function(m) {
            return m.name === name && m.desc === desc;
        }), name + desc);
    }
    function calls(method, owner, name) {
        return Java.from(method.instructions.toArray()).filter(function(i) {
            return i.getType() === 5 && i.owner === owner && i.name === name;
        });
    }
    function helper(node, name, desc, access, method, first, end) {
        if (Java.from(node.methods).some(function(m) { return m.name === name; }))
            throw new Error('GTNH client isolation: duplicate helper');
        if (method.tryCatchBlocks.size() !== 0) throw new Error('GTNH client isolation: unexpected exception region');
        var added = new Method(Op.ASM9, access, name, desc, null, null);
        var annotation = added.visitAnnotation('Lnet/neoforged/api/distmarker/OnlyIn;', true);
        annotation.visitEnum('value', 'Lnet/neoforged/api/distmarker/Dist;', 'CLIENT');
        annotation.visitEnd();
        // Transfer the original instruction objects, preserving order and branch labels.
        var cursor = first;
        while (cursor !== end) {
            if (cursor === null) throw new Error('GTNH client isolation: missing region end');
            var next = cursor.getNext();
            method.instructions.remove(cursor);
            if (cursor.getType() !== 14) added.instructions.add(cursor); // frames recalculated
            cursor = next;
        }
        added.instructions.add(new Insn(Op.RETURN));
        added.maxStack = method.maxStack;
        added.maxLocals = method.maxLocals;
        // Debug local ranges can span the extracted region; do not retain dangling labels.
        if (method.localVariables !== null) method.localVariables.clear();
        Java.from(method.instructions.toArray()).forEach(function(i) {
            if (i.getType() === 14) method.instructions.remove(i);
        });
        node.methods.add(added);
        return added;
    }
    return {
        'gtnh_fancy_scene_client_helper': {
            target: {type: 'CLASS', name: 'com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine'},
            transformer: function(node) {
                var method = findMethod(node, 'createUIWidget', '()Lcom/lowdragmc/lowdraglib/gui/widget/Widget;');
                var remote = requireOne(calls(method, node.name, 'isRemote'), 'scene side guard');
                var branch = remote.getNext();
                if (branch.getOpcode() !== Op.IFEQ) throw new Error('GTNH client isolation: scene guard shape');
                requireOne(calls(method, 'com/lowdragmc/lowdraglib/utils/TrackedDummyWorld', '<init>'), 'scene world');
                requireOne(calls(method, node.name + '$1', '<init>'), 'original scene widget');
                var name = 'gtnhMigration$addClientScene';
                helper(node, name, '('+group+')V', Op.ACC_PUBLIC, method, branch.getNext(), branch.label);
                var invoke = new List();
                invoke.add(new Var(Op.ALOAD, 0)); invoke.add(new Var(Op.ALOAD, 1));
                invoke.add(new Call(Op.INVOKEINTERFACE, node.name, name, '('+group+')V', true));
                method.instructions.insert(branch, invoke);
                return node;
            }
        },
        'gtnh_lamp_renderers_client_helper': {
            target: {type: 'CLASS', name: 'com.gregtechceu.gtceu.common.block.LampBlock'},
            transformer: function(node) {
                var method = findMethod(node, '<init>', '(Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;Lnet/minecraft/world/item/DyeColor;Z)V');
                var init = requireOne(calls(method, node.name, 'registerDefaultState'), 'lamp default state');
                requireOne(calls(method, 'com/gregtechceu/gtceu/client/renderer/block/LampRenderer', '<init>'), 'lamp renderer');
                var end = requireOne(Java.from(method.instructions.toArray()).filter(function(i) { return i.getOpcode() === Op.RETURN; }), 'lamp return');
                var name = 'gtnhMigration$initializeRenderers';
                helper(node, name, '()V', Op.ACC_PRIVATE, method, init.getNext(), end);
                var invoke = new List(), skip = new Label();
                invoke.add(new Field(Op.GETSTATIC, 'net/neoforged/fml/loading/FMLEnvironment', 'dist', 'Lnet/neoforged/api/distmarker/Dist;'));
                invoke.add(new Call(Op.INVOKEVIRTUAL, 'net/neoforged/api/distmarker/Dist', 'isClient', '()Z', false));
                invoke.add(new Jump(Op.IFEQ, skip)); invoke.add(new Var(Op.ALOAD, 0));
                invoke.add(new Call(Op.INVOKESPECIAL, node.name, name, '()V', false)); invoke.add(skip);
                method.instructions.insert(init, invoke);
                return node;
            }
        }
    };
}
