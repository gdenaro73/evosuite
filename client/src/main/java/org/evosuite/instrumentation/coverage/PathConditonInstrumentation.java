/**
 * Copyright (C) 2010-2015 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser Public License as published by the
 * Free Software Foundation, either version 3.0 of the License, or (at your
 * option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License along
 * with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
/**
 *
 */
package org.evosuite.instrumentation.coverage;

import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.RawControlFlowGraph;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * BranchInstrumentation class.
 * </p>
 *
 * @author Copied from CFGMethodAdapter
 */
public class PathConditonInstrumentation implements MethodInstrumentation {  /*SUSHI: Path condition fitness*/

	/** Constant <code>logger</code> */
	protected static final Logger logger = LoggerFactory.getLogger(PathConditonInstrumentation.class);

    private static final String EXECUTION_TRACER = Type.getInternalName(ExecutionTracer.class);
    
	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.evosuite.cfg.MethodInstrumentation#analyze(org.objectweb
	 * .asm.tree.MethodNode, org.jgrapht.Graph, java.lang.String,
	 * java.lang.String, int)
	 */
	/** {@inheritDoc} */
	@SuppressWarnings("unchecked")
	@Override
	public void analyze(ClassLoader classLoader, MethodNode mn, String className,
			String methodName, int access) {
		RawControlFlowGraph completeCFG = GraphPool.getInstance(classLoader).getRawCFG(className, methodName);
	
		if (ArrayUtil.contains(Properties.CRITERION, Criterion.BRANCH_WITH_AIDING_PATH_CONDITIONS)) {
			if (!(className.startsWith(Properties.TARGET_CLASS))) {
				return;
			}
		}
		LoggingUtils.getEvoLogger().info("Instrumenting {}::{}", className, methodName);
		
		AbstractInsnNode firstInstr = mn.instructions.getFirst();
		for (BytecodeInstruction v : completeCFG.vertexSet()) {
			if (firstInstr.equals(v.getASMNode())) {
				InsnList instrumentation = getInstrumentation(v, mn, className, methodName, access);

				if (instrumentation == null)
					throw new IllegalStateException("error instrumenting node "
							+ v.toString());
				if (Properties.PATH_CONDITION_CHECK_AT_METHOD_EXIT) {
					//THE FOLLOWING IS WRONG, IT MEANS INSERT AFTER THE FIRST INSTRUCTION
					//	mn.instructions.insert(v.getASMNode(), instrumentation);
					//FIXED AS FOLLOWS:
					Set<BytecodeInstruction> exitPoints = completeCFG.determineExitPoints();
					for (BytecodeInstruction exitP: exitPoints) {
						mn.instructions.insertBefore(exitP.getASMNode(), instrumentation);					
					}
					//LoggingUtils.getEvoLogger().info("Instrumentation set to check path condition at method exit ");
				} else {
					mn.instructions.insertBefore(v.getASMNode(), instrumentation); /* this should be the default behavior */					
				}
				mn.maxStack += 7;
				break;
			}
		}
	}

	/**
	 * <p>
	 * getInstrumentation
	 * </p>
	 *
	 * @param instruction
	 *            a {@link org.evosuite.graphs.cfg.BytecodeInstruction} object.
	 * @return a {@link org.objectweb.asm.tree.InsnList} object.
	 */
	protected InsnList getInstrumentation(BytecodeInstruction instruction, MethodNode mn, String className,
	        String methodName, int access) {
		if (instruction == null)
			throw new IllegalArgumentException("null given");

		InsnList instrumentation = new InsnList();
		
		instrumentation.add(new LdcInsnNode(className));
		instrumentation.add(new LdcInsnNode(methodName));
				
		//count parameters out of the signature
		/*int numOfParams = 0;
		String sig = mn.desc;
		sig = sig.substring(1, sig.lastIndexOf(')'));
		if (sig.contains("["))
			sig = sig.replaceAll("\\[", "");
		String[] params = sig.split(";");
		for (String p : params) {
			int indexL = p.indexOf('L');
			if (indexL < 0)
				numOfParams += p.length();
			else 
				numOfParams += indexL + 1;
		}
		if ((mn.access & Opcodes.ACC_STATIC) <= 0) { //non static: receiver object is part of parameters
			numOfParams++;
		}*/
		
		try {
			addArrayOfParams(instrumentation, mn.desc, (mn.access & Opcodes.ACC_STATIC) > 0, methodName.startsWith("<init>"));
		} catch(IllegalStateException e) {
			throw new IllegalStateException("Method " + className + "." + mn.name + ":" + e.getMessage());
		}
		
		instrumentation.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				EXECUTION_TRACER, "passedMethodCall", "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V", false));
		logger.debug("Adding passedMethodCall");
		
		return instrumentation;
	}

	/*private LocalVariableNode getVariableByIndex(List<LocalVariableNode> paramList, int varIndex) {
		List<LocalVariableNode> toSort = new ArrayList<>();
		for (LocalVariableNode var : paramList) {
			toSort.add(var);
			/*if (var.index == varIndex) {
				return var;
			}* /
		}
		
		Collections.sort(toSort, new Comparator<LocalVariableNode>() {
			@Override
			public int compare(LocalVariableNode o1, LocalVariableNode o2) {
				if (o1.index < o2.index) return -1;
				else if (o1.index == o2.index) return 0;
				else return 1;
			}
		});
		
		try {
			return toSort.get(varIndex);
		} catch(Exception e) {
			String msg = "";
			for (LocalVariableNode var : paramList) {
				msg += " var " + var.name + "(index is " + var.index + ")";
			}
			throw new IllegalStateException("error instrumenting node "
				+ "MISS" + " since no local var has index " + varIndex + " that should corresponds to an input parameter: " + msg + ", cause is: " + e);
		}
	}*/

	private void addArrayOfParams(InsnList instrumentation, String methodDescr, boolean isStatic, boolean isConstructor) {

		String simplifiedDescriptor = isStatic ? "" : "L"; //simplified descriptor, with a single char for each parameter: both L and [ refer to non primitive params
		int nextParamInDescriptor = 1;
		while (methodDescr.charAt(nextParamInDescriptor) != ')') {
			char paramType = methodDescr.charAt(nextParamInDescriptor++);
			simplifiedDescriptor += paramType;
			if (paramType == '[') {
				while (methodDescr.charAt(nextParamInDescriptor) == '[') {
					++nextParamInDescriptor;
				}
			} 
			if (paramType == '[' && methodDescr.charAt(nextParamInDescriptor) != 'L') {
				++nextParamInDescriptor;
			} else if (paramType == 'L' || (paramType == '[' &&  methodDescr.charAt(nextParamInDescriptor) == 'L')) {
				nextParamInDescriptor = methodDescr.indexOf(';', nextParamInDescriptor) + 1;
			}		
		}

		// Create array with length equal to paramLength 
		instrumentation.add(new IntInsnNode(Opcodes.BIPUSH, simplifiedDescriptor.length()));
		instrumentation.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));

		// Fill the created array with method parameters
		for (int paramIndex = 0, paramByteCodeIndex = 0; paramIndex < simplifiedDescriptor.length(); paramIndex++, paramByteCodeIndex++)  {
			char paramType = simplifiedDescriptor.charAt(paramIndex);

			instrumentation.add(new InsnNode(Opcodes.DUP));////instrumentation.add(new VarInsnNode(Opcodes.ALOAD, paramList.size()));
			instrumentation.add(new IntInsnNode(Opcodes.BIPUSH, paramIndex));

			if (isConstructor && paramIndex == 0) {
				instrumentation.add(new InsnNode(Opcodes.ACONST_NULL));
			} else if (paramType == 'Z') {
				instrumentation.add(new VarInsnNode(Opcodes.ILOAD, paramByteCodeIndex));
				instrumentation.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;"));
			} else if (paramType == 'B') {
				instrumentation.add(new VarInsnNode(Opcodes.ILOAD, paramByteCodeIndex));
				instrumentation.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;"));
			} else if (paramType == 'C') {
				instrumentation.add(new VarInsnNode(Opcodes.ILOAD, paramByteCodeIndex));
				instrumentation.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;"));
			} else if (paramType == 'S') {
				instrumentation.add(new VarInsnNode(Opcodes.ILOAD, paramByteCodeIndex));
				instrumentation.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;"));
			} else if (paramType == 'I') {
				instrumentation.add(new VarInsnNode(Opcodes.ILOAD, paramByteCodeIndex));
				instrumentation.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"));
			} else if (paramType == 'J') {
				instrumentation.add(new VarInsnNode(Opcodes.LLOAD, paramByteCodeIndex));
				instrumentation.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;"));
				paramByteCodeIndex++;
			} else if (paramType == 'F') {
				instrumentation.add(new VarInsnNode(Opcodes.FLOAD, paramByteCodeIndex));
				instrumentation.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;"));
			} else if (paramType == 'D') {
				instrumentation.add(new VarInsnNode(Opcodes.DLOAD, paramByteCodeIndex));
				instrumentation.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;"));
				paramByteCodeIndex++;
			} else {
				instrumentation.add(new VarInsnNode(Opcodes.ALOAD, paramByteCodeIndex));
			}

			instrumentation.add(new InsnNode(Opcodes.AASTORE));
		}

	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.evosuite.cfg.MethodInstrumentation#executeOnExcludedMethods
	 * ()
	 */
	/** {@inheritDoc} */
	@Override
	public boolean executeOnExcludedMethods() {
		return false;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.evosuite.cfg.MethodInstrumentation#executeOnMainMethod()
	 */
	/** {@inheritDoc} */
	@Override
	public boolean executeOnMainMethod() {
		return false;
	}

}
