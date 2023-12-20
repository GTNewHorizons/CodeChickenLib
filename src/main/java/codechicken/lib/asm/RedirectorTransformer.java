package codechicken.lib.asm;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class RedirectorTransformer implements IClassTransformer {
    private static final String RenderStateClass = "codechicken/lib/render/CCRenderState";
    private static final Set<String> redirectedFields = new HashSet<>();
    private static final Set<String> redirectedMethods = new HashSet<>();

    static {
        Collections.addAll(redirectedFields, "lightMatrix");
        Collections.addAll(
                redirectedMethods,
                "reset",
                "setPipeline",
                "bindModel",
                "setModel",
                "setVertexRange",
                "render",
                "runPipeline",
                "writeVert",
                "setNormal",
                "setColour",
                "setBrightness",
                "pullLightmap",
                "pushLightmap",
                "setDynamic",
                "startDrawing",
                "draw");
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;

        final ClassReader cr = new ClassReader(basicClass);
        final ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode node : mn.instructions.toArray()) {
                if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode fNode) {
                    if(redirectedFields.contains(fNode.name) && fNode.owner.equals(RenderStateClass)) {
                        mn.instructions.insertBefore(fNode, new MethodInsnNode(Opcodes.INVOKESTATIC, fNode.owner, "instance", "()Lcodechicken/lib/render/CCRenderState;"));
                        fNode.setOpcode(Opcodes.GETFIELD);
                        changed = true;
                    }
                }
//                else if(node.getOpcode() == Opcodes.INVOKESTATIC && node instanceof MethodInsnNode mNode) {
//                    if(redirectedMethods.contains(mNode.name) && mNode.owner.equals(RenderStateClass)) {
//                        mn.instructions.insertBefore(mNode, new MethodInsnNode(Opcodes.INVOKESTATIC, mNode.owner, "instance", "()Lcodechicken/lib/render/CCRenderState;"));
////                        mn.instructions.set(mNode, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RenderStateClass, mNode.name, mNode.desc));
//                        mNode.setOpcode(Opcodes.INVOKEVIRTUAL);
//                        changed = true;
//                    }
//                }
            }
        }

        if (changed) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        }
        return basicClass;
    }
}
