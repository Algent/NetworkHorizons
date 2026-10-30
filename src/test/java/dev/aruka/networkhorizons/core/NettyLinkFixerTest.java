package dev.aruka.networkhorizons.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class NettyLinkFixerTest {

    private static final String CTX = "io/netty/channel/ChannelHandlerContext";

    /** A class like FML's NetworkDispatcher and Forestry's user, compiled against Netty 4.0.10. */
    private static byte[] oldStyleClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "test/Old", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "read", "(L" + CTX + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CTX, "read", "()Lio/netty/channel/ChannelOutboundInvoker;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "io/netty/util/internal/EmptyArrays", "EMPTY_INTS", "[I");
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "io/netty/util/internal/EmptyArrays", "EMPTY_BYTES", "[B");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> refs(byte[] bytes) {
        List<String> out = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {

            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM5) {

                    @Override
                    public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                        out.add(owner + "." + name + desc);
                    }

                    @Override
                    public void visitFieldInsn(int op, String owner, String name, String desc) {
                        out.add(owner + "." + name + ":" + desc);
                    }
                };
            }
        }, 0);
        return out;
    }

    @Test
    void relinksRemovedInvokerReturnTypesAndEmptyArrays() {
        byte[] fixed = new NettyLinkFixer().transform("test.Old", "test.Old", oldStyleClass());
        List<String> refs = refs(fixed);
        assertEquals(CTX + ".read()L" + CTX + ";", refs.get(0));
        assertEquals("dev/aruka/networkhorizons/core/NettyCompat.EMPTY_INTS:[I", refs.get(1));
        // still present in newer Netty: untouched
        assertEquals("io/netty/util/internal/EmptyArrays.EMPTY_BYTES:[B", refs.get(2));
    }

    @Test
    void leavesUnrelatedClassesUntouched() {
        byte[] bytes = new ClassWriter(0).toByteArray();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "test/Plain", null, "java/lang/Object", null);
        cw.visitEnd();
        bytes = cw.toByteArray();
        assertSame(bytes, new NettyLinkFixer().transform("test.Plain", "test.Plain", bytes));
        assertNull(new NettyLinkFixer().transform("test.Missing", "test.Missing", null));
    }

    @Test
    void skipsItsOwnClasses() {
        // they contain the marker strings; transforming them would recurse into the transformer's own loading
        byte[] bytes = oldStyleClass();
        assertSame(
            bytes,
            new NettyLinkFixer()
                .transform("dev.aruka.networkhorizons.core.X", "dev.aruka.networkhorizons.core.X", bytes));
    }

    @Test
    void onlyInvokerReturnTypesAreRewritten() {
        assertEquals(
            "(Ljava/lang/Object;)Lio/netty/channel/ChannelPipeline;",
            NettyLinkFixer.fixInvokerReturn(
                "io/netty/channel/ChannelPipeline",
                "(Ljava/lang/Object;)Lio/netty/channel/ChannelInboundInvoker;"));
        assertNull(NettyLinkFixer.fixInvokerReturn(CTX, "(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;"));
        assertNull(NettyLinkFixer.fixInvokerReturn("some/Other", "()Lio/netty/channel/ChannelOutboundInvoker;"));
    }
}
