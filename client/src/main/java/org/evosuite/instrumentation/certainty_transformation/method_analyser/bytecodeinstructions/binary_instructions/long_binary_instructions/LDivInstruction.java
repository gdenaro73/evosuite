package org.evosuite.instrumentation.certainty_transformation.method_analyser.bytecodeinstructions.binary_instructions.long_binary_instructions;

import org.objectweb.asm.Opcodes;

public class LDivInstruction extends LongBinaryInstruction {
    public LDivInstruction(String className, String methodName, int lineNumber,String methodDescriptor,
                           int instructionNumber) {
        super(LONG_BINARY_INSTRUCTION.LDIV, className, methodName, lineNumber,methodDescriptor, instructionNumber, Opcodes.LDIV);
    }
}