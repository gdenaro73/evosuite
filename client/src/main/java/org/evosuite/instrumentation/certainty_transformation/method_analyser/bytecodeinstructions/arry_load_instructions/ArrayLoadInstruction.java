package org.evosuite.instrumentation.certainty_transformation.method_analyser.bytecodeinstructions.arry_load_instructions;

import org.evosuite.instrumentation.certainty_transformation.method_analyser.bytecodeinstructions.ByteCodeInstruction;
import org.evosuite.instrumentation.certainty_transformation.method_analyser.stack_manipulations.StackTypeSet;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public abstract class ArrayLoadInstruction extends ByteCodeInstruction {
    private final StackTypeSet type;

    public ArrayLoadInstruction(String className, String methodName, int lineNumber, String methodDescriptor,String label,
                                int instructionNumber, StackTypeSet type, int opcode) {
        super(className, methodName, lineNumber, methodDescriptor, label, instructionNumber, opcode);
        this.type = new StackTypeSet(type);
    }

    @Override
    public List<StackTypeSet> consumedFromStack() {
        return Arrays.asList(StackTypeSet.ARRAY, StackTypeSet.TWO_COMPLEMENT);
    }

    @Override
    public StackTypeSet pushedToStack() {
        return new StackTypeSet(type);
    }

    @Override
    public boolean writesVariable(int index){
        return false;
    }

    @Override
    public Set<Integer> readsVariables(){
        return Collections.emptySet();
    }
    @Override
    public Set<Integer> writesVariables(){
        return Collections.emptySet();
    }
}