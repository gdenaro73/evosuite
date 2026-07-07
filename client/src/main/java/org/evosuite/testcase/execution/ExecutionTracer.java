/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.execution;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.Properties.Criterion;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.dataflow.DefUsePool;
import org.evosuite.coverage.dataflow.Definition;
import org.evosuite.coverage.dataflow.Use;
import org.evosuite.coverage.pathcondition.PathConditionCoverageGoal;
import org.evosuite.coverage.pathcondition.PathConditionCoverageGoal.PcFitnessValue;
import org.evosuite.coverage.seepep.SeepepTraceItem;
import org.evosuite.instrumentation.testability.BooleanHelper;
import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.testcase.execution.ExecutionTraceImpl.PathConditionEvaluationInfo;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class collects information about chosen branches/paths at runtime
 * 
 * @author Gordon Fraser
 */
public class ExecutionTracer {

	private static final Logger logger = LoggerFactory.getLogger(ExecutionTracer.class);

	private static ExecutionTracer instance = null;

	/**
	 * We need to disable the execution tracer sometimes, e.g. when calling
	 * equals in the branch distance function
	 */
	private boolean disabled = true;

    /**
     * Flag that is used to kill threads that are stuck in endless loops
     */
	private boolean killSwitch = false;

	private int num_statements = 0;

	private ExecutionTrace trace;


	private static boolean checkCallerThread = true;

	/**
	 * If a thread of a test case survives for some reason (e.g. long call to
	 * external library), then we don't want its data in the current trace
	 */
	private static volatile Thread currentThread = null;

	/**
	 * <p>
	 * setThread
	 * </p>
	 * 
     * @param thread a {@link java.lang.Thread} object.
	 */
	public static void setThread(Thread thread) {
		currentThread = thread;
	}

	/**
	 * <p>
	 * disable
	 * </p>
	 */
	public static void disable() {
		ExecutionTracer tracer = ExecutionTracer.getExecutionTracer();
		tracer.disabled = true;
	}

	/**
	 * <p>
	 * enable
	 * </p>
	 */
	public static void enable() {
		ExecutionTracer tracer = ExecutionTracer.getExecutionTracer();
		tracer.disabled = false;
	}

	/**
	 * <p>
	 * isEnabled
	 * </p>
	 * 
	 * @return a boolean.
	 */
	public static boolean isEnabled() {
		ExecutionTracer tracer = ExecutionTracer.getExecutionTracer();
		return !tracer.disabled;
	}

	/**
	 * <p>
	 * Setter for the field <code>killSwitch</code>.
	 * </p>
	 * 
     * @param value a boolean.
	 */
	public static void setKillSwitch(boolean value) {
		ExecutionTracer tracer = ExecutionTracer.getExecutionTracer();
		tracer.killSwitch = value;
	}

	/**
	 * <p>
	 * Setter for the field <code>checkCallerThread</code>.
	 * </p>
	 * 
     * @param checkCallerThread a boolean.
	 */
	public static void setCheckCallerThread(boolean checkCallerThread) {
		ExecutionTracer.checkCallerThread = checkCallerThread;
	}

	/**
	 * <p>
	 * enable context instrumentation
	 * </p>
	 */
	public static void enableContext(){
		logger.info("enable context and trace instrumentation");
		ExecutionTraceImpl.enableContext();
	}
	
	/**
	 * <p>
	 * disable context instrumentation
	 * </p>
	 */
	public static void disableContext(){
		logger.info("disable context and trace instrumentation");
		ExecutionTraceImpl.disableContext();
	}
	
	/**
	 * <p>
	 * disableTraceCalls
	 * </p>
	 */
	public static void disableTraceCalls() {
		ExecutionTraceImpl.disableTraceCalls();
	}

	/**
	 * <p>
	 * enableTraceCalls
	 * </p>
	 */
	public static void enableTraceCalls() {
		ExecutionTraceImpl.enableTraceCalls();
	}

	public static boolean isTraceCallsEnabled() {
		return ExecutionTraceImpl.isTraceCallsEnabled();
	}

	public static void enableSeepepTracing() { /*SEEPEP: DAG coverage*/
		ExecutionTraceImpl.enableSeepepTracing();
	}

	/**
	 * <p>
	 * getExecutionTracer
	 * </p>
	 * 
	 * @return a {@link org.evosuite.testcase.execution.ExecutionTracer} object.
	 */
	public static ExecutionTracer getExecutionTracer() {
		if (instance == null) {
			instance = new ExecutionTracer();
		}
		return instance;
	}

	/**
	 * Reset for new execution
	 */
	public void clear() {
		if (traceEventListener != null) { //SUSHI: Aiding path conditions
			trace = traceEventListener;
		} else
		trace = new ExecutionTraceProxy();
		BooleanHelper.clearStack();
		num_statements = 0;
	}

	/**
	 * Obviously more than one thread is executing during the creation of
	 * concurrent TestCases. #TODO steenbuck we should test if
	 * Thread.currentThread() is in the set of currently executing threads
	 * 
	 * @return
	 */
	public static boolean isThreadNeqCurrentThread() {
		if (!checkCallerThread) {
			return false;
		}
		if (currentThread == null) {
			logger.error("CurrentThread has not been set!");
			Map<Thread, StackTraceElement[]> map = Thread.getAllStackTraces();
			for (Thread t : map.keySet()) {
				String msg = "Thread: " + t+"\n";
				for (StackTraceElement e : map.get(t)) {
					msg += " -> " + e + "\n";
				}
				logger.error(msg);
			}
			currentThread = Thread.currentThread();
		}
		return Thread.currentThread() != currentThread;
	}

	/**
	 * Return trace of current execution
	 * 
	 * @return a {@link org.evosuite.testcase.execution.ExecutionTrace} object.
	 */
	public ExecutionTrace getTrace() {
		trace.finishCalls();
		return trace;

		// ExecutionTrace copy = trace.clone();
		// // copy.finishCalls();
		// return copy;
	}

	/**
	 * Return the last explicitly thrown exception
	 * 
	 * @return a {@link java.lang.Throwable} object.
	 */
	public Throwable getLastException() {
		return trace.getExplicitException();
	}

	/**
	 * Called by instrumented code whenever a new method is called
	 * 
     * @param classname  a {@link java.lang.String} object.
     * @param methodname a {@link java.lang.String} object.
     * @param caller     a {@link java.lang.Object} object.
     * @throws org.evosuite.testcase.execution.TestCaseExecutor$TimeoutExceeded if any.
	 */
	public static void enteredMethod(String classname, String methodname, Object caller)
	        throws TestCaseExecutor.TimeoutExceeded {
		ExecutionTracer tracer = getExecutionTracer();

		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		checkTimeout();

		//logger.trace("Entering method " + classname + "." + methodname);
		tracer.trace.enteredMethod(classname, methodname, caller);
	}

	/**
	 * Called by instrumented code whenever a return values is produced
	 * 
     * @param value      a int.
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
	 */
	public static void returnValue(int value, String className, String methodName) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		//logger.trace("Return value: " + value);
		tracer.trace.returnValue(className, methodName, value);
	}

	/**
	 * Called by instrumented code whenever a return values is produced
	 * 
     * @param value      a {@link java.lang.Object} object.
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
	 */
	public static void returnValue(Object value, String className, String methodName) {
		if (isThreadNeqCurrentThread())
			return;

		if (!ExecutionTracer.isEnabled())
			return;

		if (value == null) {
			returnValue(0, className, methodName);
			return;
		}
		StringBuilder tmp = null;
		try {
			// setLineCoverageDeactivated(true);
			// logger.warn("Disabling tracer: returnValue");
			ExecutionTracer.disable();
			tmp = new StringBuilder(value.toString());
		} catch (Throwable t) {
			return;
		} finally {
			ExecutionTracer.enable();
		}
		int index = 0;
		int position = 0;
		boolean found = false;
		boolean deleteAddresses = true;
		char c = ' ';
		// quite fast method to detect memory addresses in Strings.
		while ((position = tmp.indexOf("@", index)) > 0) {
			for (index = position + 1; index < position + 17 && index < tmp.length(); index++) {
				c = tmp.charAt(index);
				if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
				        || (c >= 'A' && c <= 'F')) {
					found = true;
				} else {
					break;
				}
			}
			if (deleteAddresses && found) {
				tmp.delete(position + 1, index);
			}
		}

		returnValue(tmp.toString().hashCode(), className, methodName);
	}

	/**
	 * Called by instrumented code whenever a method is left
	 * 
     * @param classname  a {@link java.lang.String} object.
     * @param methodname a {@link java.lang.String} object.
	 */
	public static void leftMethod(String classname, String methodname) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		tracer.trace.exitMethod(classname, methodname);
		// logger.trace("Left method " + classname + "." + methodname);
	}

	/**
	 * Called by the instrumented code each time a new source line is executed
	 */
	public static void checkTimeout() {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (tracer.killSwitch) {
			// logger.info("Raising TimeoutException as kill switch is active - passedLine");
			if(!isInStaticInit())
				throw new TestCaseExecutor.TimeoutExceeded();
		}
	}
	
	private static boolean isInStaticInit() {
		for(StackTraceElement elem : Thread.currentThread().getStackTrace()) {
			if(elem.getMethodName().equals("<clinit>"))
				return true;
		}
		return false;
	}

	/**
	 * Called by the instrumented code each time a new source line is executed
	 * 
     * @param line       a int.
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
	 */
	public static void passedLine(String className, String methodName, int line) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		checkTimeout();

		tracer.trace.linePassed(className, methodName, line);
	}

	/**
	 * Called by the instrumented code each time a new branch is taken
	 * 
     * @param val         a int.
     * @param opcode      a int.
     * @param branch      a int.
     * @param bytecode_id a int.
	 */
	public static void passedBranch(int val, int opcode, int branch, int bytecode_id) {

		ExecutionTracer tracer = getExecutionTracer();
		// logger.info("passedBranch val="+val+", opcode="+opcode+", branch="+branch+", bytecode_id="+bytecode_id);
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		checkTimeout();

		ConstantPoolManager.getInstance().addDynamicConstant(val);

		// logger.trace("Called passedBranch1 with opcode "+AbstractVisitor.OPCODES[opcode]+" and val "+val+" in branch "+branch);
		double distance_true = 0.0;
		double distance_false = 0.0;
		switch (opcode) {
		case Opcodes.IFEQ:
			distance_true = Math.abs((double) val); // The greater abs is, the
			// further away from 0
			distance_false = distance_true == 0 ? 1.0 : 0.0; // Anything but 0
			// is good
			break;
		case Opcodes.IFNE:
			distance_false = Math.abs((double) val); // The greater abs is, the
			// further away from 0
			distance_true = distance_false == 0 ? 1.0 : 0.0; // Anything but 0
			// leads to NE
			break;
		case Opcodes.IFLT:
			distance_true = val >= 0 ? val + 1.0 : 0.0; // The greater, the
			// further away from < 0
			distance_false = val < 0 ? 0.0 - val + 1.0 : 0.0; // The smaller,
			// the further
			// away from < 0
			break;
		case Opcodes.IFGT:
			distance_true = val <= 0 ? 0.0 - val + 1.0 : 0.0;
			distance_false = val > 0 ? val + 1.0 : 0.0;
			break;
		case Opcodes.IFGE:
			distance_true = val < 0 ? 0.0 - val + 1.0 : 0.0;
			distance_false = val >= 0 ? val + 1.0 : 0.0;
			break;
		case Opcodes.IFLE:
			distance_true = val > 0 ? val + 1.0 : 0.0; // The greater, the
			// further away from < 0
			distance_false = val <= 0 ? 0.0 - val + 1.0 : 0.0; // The smaller,
			// the further
			// away from < 0
			break;
		default:
			logger.error("Unknown opcode: " + opcode);

		}
		// logger.trace("1 Branch distance true : " + distance_true);
		// logger.trace("1 Branch distance false: " + distance_false);

		// Add current branch to control trace
		tracer.trace.branchPassed(branch, bytecode_id, distance_true, distance_false);
	}

	public static void passedPutStatic(String classNameWithDots, String fieldName) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		checkTimeout();
		
		tracer.trace.putStaticPassed(classNameWithDots, fieldName);
	}

	
	/**
	 * This method is added in the transformed bytecode
	 *
	 * @param className
	 */
	public static void exitClassInit(String className) {
		
		final String classNameWithDots = className.replace('/', '.');

		ExecutionTracer tracer = getExecutionTracer();
//		if (tracer.disabled)
//			return;
//
//		if (isThreadNeqCurrentThread())
//			return;
//
//		checkTimeout();
		
		tracer.trace.classInitialized(classNameWithDots);
		
	}
	
	/**
	 * @param classNameWithDots
	 * @param fieldName
	 */
	public static void passedGetStatic(String classNameWithDots, String fieldName) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		checkTimeout();

		tracer.trace.getStaticPassed(classNameWithDots, fieldName);
	}


	/**
	 * Called by the instrumented code each time a new branch is taken
	 * 
     * @param val1        a int.
     * @param val2        a int.
     * @param opcode      a int.
     * @param branch      a int.
     * @param bytecode_id a int.
	 */
	public static void passedBranch(int val1, int val2, int opcode, int branch,
	        int bytecode_id) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		checkTimeout();
		
		ConstantPoolManager.getInstance().addDynamicConstant(val1);
		ConstantPoolManager.getInstance().addDynamicConstant(val2);

		/* logger.trace("Called passedBranch2 with opcode "
		        + AbstractVisitor.OPCODES[opcode] + ", val1=" + val1 + ", val2=" + val2
		        + " in branch " + branch); */
		double distance_true = 0;
		double distance_false = 0;
		switch (opcode) {
		// Problem is that the JVM is a stack machine
		// x < 5 gets compiled to a val2 > val1,
		// because operators are on the stack in reverse order
		case Opcodes.IF_ICMPEQ:
			// The greater the difference, the further away
			distance_true = Math.abs((double) val1 - (double) val2);
			// Anything but 0 is good
			distance_false = distance_true == 0 ? 1.0 : 0.0;
			break;
		case Opcodes.IF_ICMPNE:
			// The greater abs is, the further away from 0
			distance_false = Math.abs((double) val1 - (double) val2);
			// Anything but 0 leads to NE
			distance_true = distance_false == 0 ? 1.0 : 0.0;
			break;
		case Opcodes.IF_ICMPLT:
			// val1 >= val2?
			distance_true = val1 >= val2 ? (double) val1 - (double) val2 + 1.0 : 0.0;
			distance_false = val1 < val2 ? (double) val2 - (double) val1 + 1.0 : 0.0;
			break;
		case Opcodes.IF_ICMPGE:
			// val1 < val2?
			distance_true = val1 < val2 ? (double) val2 - (double) val1 + 1.0 : 0.0;
			distance_false = val1 >= val2 ? (double) val1 - (double) val2 + 1.0 : 0.0;
			break;
		case Opcodes.IF_ICMPGT:
			// val1 <= val2?
			distance_true = val1 <= val2 ? (double) val2 - (double) val1 + 1.0 : 0.0;
			distance_false = val1 > val2 ? (double) val1 - (double) val2 + 1.0 : 0.0;
			break;
		case Opcodes.IF_ICMPLE:
			// val1 > val2?
			distance_true = val1 > val2 ? (double) val1 - (double) val2 + 1.0 : 0.0;
			distance_false = val1 <= val2 ? (double) val2 - (double) val1 + 1.0 : 0.0;
			break;
		default:
			logger.error("Unknown opcode: " + opcode);
		}
		// logger.trace("2 Branch distance true: " + distance_true);
		// logger.trace("2 Branch distance false: " + distance_false);

		// Add current branch to control trace
		tracer.trace.branchPassed(branch, bytecode_id, distance_true, distance_false);
		// tracer.trace.branchPassed(branch, distance_true, distance_false);

	}

	/**
	 * Called by the instrumented code each time a new branch is taken
	 * 
     * @param val1        a {@link java.lang.Object} object.
     * @param val2        a {@link java.lang.Object} object.
     * @param opcode      a int.
     * @param branch      a int.
     * @param bytecode_id a int.
	 */
	public static void passedBranch(Object val1, Object val2, int opcode, int branch,
	        int bytecode_id) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		checkTimeout();

		double distance_true = 0;
		double distance_false = 0;
		// logger.warn("Disabling tracer: passedBranch with 2 Objects");

		switch (opcode) {
		case Opcodes.IF_ACMPEQ:
			distance_true = val1 == val2 ? 0.0 : 1.0;
			break;
		case Opcodes.IF_ACMPNE:
			distance_true = val1 != val2 ? 0.0 : 1.0;
			break;
		}

		distance_false = distance_true == 0 ? 1.0 : 0.0;

		// Add current branch to control trace
		tracer.trace.branchPassed(branch, bytecode_id, distance_true, distance_false);
	}

	/**
	 * Called by the instrumented code each time a new branch is taken
	 * 
     * @param val         a {@link java.lang.Object} object.
     * @param opcode      a int.
     * @param branch      a int.
     * @param bytecode_id a int.
	 */
	public static void passedBranch(Object val, int opcode, int branch, int bytecode_id) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		checkTimeout();

		double distance_true = 0;
		double distance_false = 0;
		switch (opcode) {
		case Opcodes.IFNULL:
			distance_true = val == null ? 0.0 : 1.0;
			break;
		case Opcodes.IFNONNULL:
			distance_true = val == null ? 1.0 : 0.0;
			break;
		default:
			logger.error("Warning: encountered opcode " + opcode);
		}
		distance_false = distance_true == 0 ? 1.0 : 0.0;
		// enable();

		// logger.trace("Branch distance true: " + distance_true);
		// logger.trace("Branch distance false: " + distance_false);

		// Add current branch to control trace
		tracer.trace.branchPassed(branch, bytecode_id, distance_true, distance_false);
	}

	/**
	 * Called by instrumented code each time a variable gets written to (a
	 * Definition)
	 * 
     * @param caller a {@link java.lang.Object} object.
     * @param defID  a int.
	 */
	public static void passedDefinition(Object object, Object caller, int defID) {
		if (isThreadNeqCurrentThread())
			return;

		ExecutionTracer tracer = getExecutionTracer();
		if (!tracer.disabled)
			tracer.trace.definitionPassed(object, caller, defID);
	}

	/**
	 * Called by instrumented code each time a variable is read from (a Use)
	 * 
     * @param caller a {@link java.lang.Object} object.
     * @param useID  a int.
	 */
	public static void passedUse(Object object, Object caller, int useID) {

		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		tracer.trace.usePassed(object, caller, useID);
	}

	/**
	 * Called by instrumented code each time a field method call is passed
     * <p>
	 * Since it was not clear whether the field method call constitutes a
	 * definition or a use when the instrumentation was initially added this
	 * method will redirect the call accordingly
	 * 
	 * @param caller
	 * @param defuseId
	 */
	public static void passedFieldMethodCall(Object callee, Object caller, int defuseId) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		if (DefUsePool.isKnownAsDefinition(defuseId)) {
			Definition passedDef = DefUsePool.getDefinitionByDefUseId(defuseId);
			passedDefinition(callee, caller, passedDef.getDefId());
		} else if (DefUsePool.isKnownAsUse(defuseId)) {
			Use passedUse = DefUsePool.getUseByDefUseId(defuseId);
			passedUse(callee, caller, passedUse.getUseId());
		} else
			throw new EvosuiteError(
			        "instrumentation called passedFieldMethodCall with invalid defuseId: "
			                + defuseId + ", known IDs: " + DefUsePool.getDefUseCounter());
	}

	/**
	 * Called by instrumented code each time method is executed. This method
	 * receives the full set of parameters of the corresponding call
	 * 
	 * @param params
	 *            the parameters passed to the call
	 */
	public static void passedMethodCall(String className, String methodName, Object[] params) { /*SUSHI: Path condition fitness*/
		//logger.warn("CHECKING " + className + "." + methodName + Arrays.toString(Thread.currentThread().getStackTrace()));
		ExecutionTracer tracer = getExecutionTracer();

		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		checkTimeout();

		if (reentrantCall(tracer.trace)) {
			return;
		}

		if (ArrayUtil.contains(Properties.CRITERION, Criterion.SEEPEP)) { /*SEEPEP: DAG coverage*/
			if (methodName.equals(Properties.SEEPEP_ENTRY_METHOD)) {
				if (tracer.trace.checkSetSeepepDone(true)) {
					throw new RuntimeException("stop");
				}
				SeepepTraceItem startMarker = SeepepTraceItem.makeTraceStartMarker();
				tracer.trace.passedSeepepItem(startMarker);
				//LoggingUtils.getEvoLogger().info("Passing start trace: {}", startMarker);
				for (int i = 0; i < params.length - 1; i++) {
					SeepepTraceItem seepepItem = SeepepTraceItem.makeInputParameter(i, params[i + 1]);
					tracer.trace.passedSeepepItem(seepepItem);
					//LoggingUtils.getEvoLogger().info("Passing input: {}", seepepItem);
				}
			} else if (methodName.equals("notifyActionPassed(Ljava/lang/String;Ljava/lang/Object;)V")) {
				SeepepTraceItem seepepItem = SeepepTraceItem.makeAction((String) params[0], params[1]);
				tracer.trace.passedSeepepItem(seepepItem);
				//LoggingUtils.getEvoLogger().info("Passing action:{}", seepepItem);
			} else if (methodName.equals("notifyTransformationPassed(Ljava/lang/String;I)V")) {
				SeepepTraceItem seepepItem = SeepepTraceItem.makeTransformation((String) params[0], (int) params[1]);
				tracer.trace.passedSeepepItem(seepepItem);
				//LoggingUtils.getEvoLogger().info("Passing transformation: {}", seepepItem);
			} else if (methodName.equals("notifyOuterTransformationPassed(Ljava/lang/String;I)V")) {
				List<SeepepTraceItem> itemsSoFar = tracer.trace.getTraversedSeepepItems();
				SeepepTraceItem itemToReplace = itemsSoFar.remove(itemsSoFar.size() - 1);
				SeepepTraceItem seepepItem = SeepepTraceItem.makeTransformation((String) params[0], (int) params[1]);
				tracer.trace.passedSeepepItem(seepepItem);
				//LoggingUtils.getEvoLogger().info("Passing outer transformation: {} (replaces {})", seepepItem, itemToReplace);
			}
		}

		if (!mustCheckPathConditionsForThisCall(className, methodName)) {
			return;
		}

		//LoggingUtils.getEvoLogger().info("- ENTRY in:{} :: {} :: {}", className, methodName, Arrays.toString(params));

		tracer.trace.evaluatingPathConditionsBegin(className, methodName);

		try {
			Map<String, List<PathConditionCoverageGoal>> classEvaluators = tracer.pathConditions.get(className);
			if (classEvaluators == null) 
				return; // no evaluator for this class

			List<PathConditionCoverageGoal> methodEvaluators = classEvaluators.get(methodName);
			if (methodEvaluators == null) 
				return; // no evaluator for this method

			ClassLoader cl = TestGenerationContext.getInstance().getClassLoaderForSUT();
			resetEvaluationBackbone(cl);

			ArrayList<Object> feedback = null;
			boolean isEvaluatorWithFeedback = ArrayUtil.contains(Properties.CRITERION, Criterion.BRANCH_WITH_AIDING_PATH_CONDITIONS);
			if (isEvaluatorWithFeedback) {
				try {
					feedback = (ArrayList<Object>) cl.loadClass("java.util.ArrayList").newInstance(); //ArrayList to collect feedback
				} catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
					throw new EvosuiteError("Cannot load class java.util.ArrayList with ClassLoaderForSUT: because of: " + e + " ::: " + 
							Arrays.toString(e.getStackTrace()));	
				}
				params = Arrays.copyOf(params, params.length + 1);
				params[params.length - 1] = feedback;
			}
			for (PathConditionCoverageGoal goal : methodEvaluators) {
				PcFitnessValue fitnessData = goal.executeEvaluator(params);
				int relatedBranchId = -1;
				if (tracer.pathConditionRelatedBranch.containsKey(goal)) {
					relatedBranchId = tracer.pathConditionRelatedBranch.get(goal);
				}
				tracer.trace.passedPathCondition(goal, relatedBranchId, fitnessData,
						feedback == null ? null : (ArrayList<Object>) feedback.clone()); //need to clone the ArrayList, because we clean and reuse feedback (below)
				if (feedback != null) {
					feedback.clear();
				}
				//LoggingUtils.getEvoLogger().info("    Evaluator test0 on:{} = {} (params = {}, goal = {})", goal.getPathConditionId(), d, Arrays.toString(params), goal);
			}
		} catch (Throwable e) {
			throw new EvosuiteError("Unexpected exception within the instrumentation related to path-conditions: CHECK THIS: " + e
					+ "\n--- Exception stack trace is: " + Arrays.toString(e.getStackTrace()));			
		} finally {
			tracer.trace.evaluatingPathConditionsDone(className, methodName);
		}
	}
	
	public static void aastoreHelper(Object[] theArray, int theIndex, Object theItem) {
		theArray[theIndex] = theItem;
		//return theArray;
	}
	
	public static void passedExceptionPropagatedBackToTheTestCase(Throwable thrownException) { /*SUSHI: Path condition fitness*/
		//LoggingUtils.getEvoLogger().info(" **** Exception {} ({}) received in TEST CASE: Stack trace: {}", thrownException, thrownException.getClass(), Arrays.toString(thrownException.getStackTrace()));
		unwindMethodExitsUntraversedDueToThrownException(thrownException, null, null); //passing null.null as method unwinds all calls
	}
	
	public static void passedExceptionHandler(Throwable thrownException, String className, String methodName) { /*SUSHI: Path condition fitness*/
		//LoggingUtils.getEvoLogger().info(" **** Exception {} ({}) received in METHOD {}.{}: Stack trace: {}", thrownException, thrownException.getClass(), className, methodName, Arrays.toString(thrownException.getStackTrace()));
		unwindMethodExitsUntraversedDueToThrownException(thrownException, className, methodName); //passing null.null as method unwinds all calls
	}
	
	private static void unwindMethodExitsUntraversedDueToThrownException(Throwable thrownException, String className, String methodName) {
		// here we unwind methods exited silently after the throw statement
		ExecutionTracer tracer = getExecutionTracer();

		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;
		
		if (reentrantCall(tracer.trace)) {
			return;
		}

		boolean exceptionInTestCase = (methodName == null);
		
		
		if (!exceptionInTestCase && !mustCheckPathConditionsForThisCall(className, methodName)) {
			return; // if this method call does not require checking, we assume that the nested call did not either 
		}

		//LoggingUtils.getEvoLogger().info(" **** Exception {} received in METHOD {}.{}: Stack trace: {}", thrownException.getClass(), className, methodName, Arrays.toString(thrownException.getStackTrace()));

		List<PathConditionEvaluationInfo> evalautedPathConditions = tracer.trace.getPathConditionEvaluationStack();
		boolean currentMethodBelongToStack = false;
		for (PathConditionEvaluationInfo evalPC: evalautedPathConditions) {
			if (evalPC.methodName.equals(methodName) && evalPC.className.equals(className)) {
				currentMethodBelongToStack = true;
				break; // we can stop unwinding because this is the method that intercepted the exception, and it will exit naturally eventually. 
			}
			if (evalPC.methodName.startsWith("<init>")) {
				passedMethodExit(thrownException, evalPC.className, evalPC.methodName, new Object[0], true); 			
			} else {
				throw new EvosuiteError("Unexpected sequence when evaluating pre- and post-condition, "
						+ "while we are unwinding methods that exited silently due a thrown exception."
						+ "However we expect this to happen only for trailing prefixes of constructors"
						+ "(the calls to 'super'), but in fact we are unwinding method " 
						+ evalPC.className + "." + evalPC.methodName
						+ "\n--- Current caller is: " + className + "." + methodName
						+ "\n--- Current stack of evalauted path conds is: " + evalautedPathConditions
						+ "\n--- Current stack trace is: " + Arrays.toString(Thread.currentThread().getStackTrace())
						+ "\n--- Current exception is: " + thrownException + " -- " + Arrays.toString(thrownException.getStackTrace()));			
			}
		}	
		if (!exceptionInTestCase && !currentMethodBelongToStack) {
			throw new EvosuiteError("Unexpected sequence when evaluating pre- and post-condition, "
					+ "while we are unwinding methods that exited silently due a thrown exception."
					+ "The method that intercepted the exception " + className + "." + methodName
					+ " is not in the stack." 
					+ "\n--- Current caller is: " + className + "." + methodName
					+ "\n--- Current stack of evalauted path conds is: " + evalautedPathConditions
					+ "\n--- Current stack trace is: " + Arrays.toString(Thread.currentThread().getStackTrace()) 
					+ "\n--- Current exception is: " + thrownException + " -- " + Arrays.toString(thrownException.getStackTrace()));			
		}
	}

	/**
	 * Called by instrumented code each time method is executed at the exit. This method
	 * receives both the return value and full set of parameters of the corresponding call
	 * 
	 * @param retVal
	 *            the return value of the call
	 * @param params
	 *            the parameters passed to the call
	 */
	public static void passedMethodExit(Object retVal, String className, String methodName, Object[] params, boolean enforcePathConditionCheckForThisCall) { /*SUSHI: Path condition fitness*/
		ExecutionTracer tracer = getExecutionTracer();

		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		if (reentrantCall(tracer.trace)) {
			return;
		}

		if (!enforcePathConditionCheckForThisCall && !mustCheckPathConditionsForThisCall(className, methodName)) {
			return;
		}

		//LoggingUtils.getEvoLogger().info("-- EXIT on:{} :: {} :: {}", className, methodName, retVal!=null?retVal.getClass():null);

		tracer.trace.evaluatingPostConditionsBegin(className, methodName);

		try {
			Map<String, List<PathConditionCoverageGoal>> classEvaluators = tracer.pathConditions.get(className);
			if (classEvaluators == null) 
				return; // no evaluator for this class

			List<PathConditionCoverageGoal> methodEvaluators = classEvaluators.get(methodName);
			if (methodEvaluators == null) 
				return; // no evaluator for this method

			ClassLoader cl = TestGenerationContext.getInstance().getClassLoaderForSUT();
			resetEvaluationBackbone(cl);

			for (PathConditionCoverageGoal goal : methodEvaluators) {
				Object [] paramValues = new Object[params.length + 1]; //expected equal to (params.length + 1)
				paramValues[0] = retVal;
				for (int i = 0; i < params.length; i++) {
					paramValues[i + 1] = params[i];
				}
				double d = goal.executeEvaluatorLegacy("test1", paramValues);
				tracer.trace.passedPostCondition(goal, goal.wrapDistanceValueAsPcFitnessValue(d));
				//LoggingUtils.getEvoLogger().info("    * Evaluator test1 on:{} = {} (params = {}, goal = {})", goal.getPathConditionId(), d, Arrays.toString(params), goal);
			}
		} catch (Throwable e) {
			throw new EvosuiteError("Unexpected exception within the instrumentation related to post-conditions: CHECK THIS: " + e.getClass());
		} finally {
			tracer.trace.evaluatingPostConditionsDone(className, methodName);
		}
	}
	
	private static boolean reentrantCall(ExecutionTrace trace) {
		return trace.isEvaluatingPathConditions();
		/*
		StackTraceElement[] strace = Thread.currentThread().getStackTrace();
		/ *
		 * the stack trace includes for sure: 0) getStackTrace, 1) this method,
		 * 2) ExecutionTracer.passedMethod..., and 3) the method that executed the instrumentation.
		 * Then, if the 4th method belongs to sun.reflect.*, it means that
		 * the instrumented method was called directly from within the test case, otherwise
		 * it was reached indirectly call when the test case called another method.
		 * /
		int i = 1;
		while (i < strace.length && strace[i].toString().startsWith("org.evosuite.testcase.execution.ExecutionTracer")) {
			++i; //this traverses the current calls within ExecutionTracer...
		}
		++i;
		while (i < strace.length) {
			if (strace[i].toString().startsWith("org.evosuite.testcase.execution.ExecutionTracer")) {
				return true; //...once again in ExecutionTracer: Then it is a reentrant call!
			}
			++i; 
		}
		return false; */
	}

	private static boolean mustCheckPathConditionsForThisCall(String className, String methodName) {
		Map<String, List<PathConditionCoverageGoal>> classEvaluators = getExecutionTracer().pathConditions.get(className);
		if (classEvaluators == null) 
			return false; // no evaluator for this class

		List<PathConditionCoverageGoal> methodEvaluators = classEvaluators.get(methodName);
		if (methodEvaluators == null) 
			return false; // no evaluator for this method
		
		if (Properties.CHECK_PATH_CONDITIONS_ONLY_FOR_DIRECT_CALLS) {
			StackTraceElement[] strace = Thread.currentThread().getStackTrace();
			/* The stack trace includes for sure: 0) getStackTrace, 1) this method,
			 * 2..i) ExecutionTracer.passedMethod..., and then i+1) the method that executed the instrumentation.
			 * Then, if the subsequence method (i+2) belongs to sun.reflect.*, it means that
			 * the instrumented method was called directly from within the test case, otherwise
			 * it was reached indirectly call when the test case called another method.
			 */
			if (strace.length <= 4) {
				//defensive check: should never happen by construction, as for cases 1) 2) and 3) in comment above
				throw new EvosuiteError("Stack trace with unexpected shape: CHECK THIS: " + Arrays.toString(strace));
			}

			int i = 3; //we start at 3, as 0) getStackTrace, 1) this method, 2) ExecutionTracer.passedMethod

			// we accept any other ExecutionTracer.* method, as the code in can be organized across multiple methods
			while (i < strace.length && strace[i].toString().startsWith(ExecutionTracer.class.getName())) {
				++i; //We account for (and skip) multiple calls within ExecutionTracer...
			}
			if (i >= strace.length) {
				//defensive check: should never happen by construction, as ExecutionTracer.passedMethod is called from instrumented SUT
				throw new EvosuiteError("Stack trace with unexpected shape: CHECK THIS: " + Arrays.toString(strace));
			}

			if (!strace[i + 1].toString().startsWith("sun.reflect.")) {
				return false; //strace[i] is not a direct call from SUT
			} else {
				return true;  //strace[i] is a direct call from SUT via reflection
			}

			/* we count the method calls that come from within the SUT, and accept indirect calls of overriding methods. 
			 * NB 02.05.2024: Unfortunately, this implementation does not work, because getStackTrace reports method names without
			 * signatures, and thus we end up with selecting indirect calls also from overloading-methods. At the moment,
			 * we are sticking to direct calls only (see above).
			 *
			int count = 0;
			while (i + count < strace.length && !strace[i + count].toString().startsWith("sun.reflect.")) {
				++count;
				break; //
			}
			if (count == 0 || i + count >= strace.length) {
				//defensive check: should never happen by construction as we expect SUT methods called from test cases via reflection
				throw new EvosuiteError("Stack trace with unexpected shape: CHECK THIS: " + Arrays.toString(strace));
			}
			String methodName = strace[i + count - 1].getMethodName(); // the method called directly from the SUT
			for (int j = i; j < i + count - 1; ++j) {
				if (!strace[i].getMethodName().equals(methodName)) { //TODO: should check also for classes to be in inheritance relation
					return false;
				}
			}
			/* here if either 1) count = 1 (i.e., we skipped the loop above) --> only one call belongs to the SUT
			 * --> it is a direct call from test case, or 2) all calls refer to the same method, which we assume 
			 * as a special case: indirect calls from overridden methods. * /
			return true; 
			*/
		}
		
		return true; // none of above exclusion reasons --> then default case is checking the calls 
	}

	private static void resetEvaluationBackbone(ClassLoader cl) {
		try {
			Class.forName("sushi.compile.path_condition_distance.CandidateBackbone", false, cl).
			getMethod("resetAndReuseUntilReset", null).invoke(null, null);
		} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException
				| SecurityException e) {
			LoggingUtils.getEvoLogger().info("FAILED ATTEMPT TO RESET BACKBONE");
		} catch (ClassNotFoundException e) {
			//This means that either Evaluators are designed not to use sushi-lib, or we have an higher level problem.
			//Thus, we do not log the problem to support the case in which indeed users do not care of sushi-lib
		}		
	}

	private Map<String, Map<String, List<PathConditionCoverageGoal>>> pathConditions = new HashMap<>(); //classname --> methodname --> PathCondWrapper /*SUSHI: Path condition fitness*/
	private Map<PathConditionCoverageGoal, Integer> pathConditionRelatedBranch =  new HashMap<>(); //pc --> branchId
	public static void addEvaluatorForPathCondition(PathConditionCoverageGoal g) { /*SUSHI: Path condition fitness*/
		ExecutionTracer tracer = getExecutionTracer();
		Map<String, List<PathConditionCoverageGoal>> classEvaluators = tracer.pathConditions.get(g.getClassName());
		if (classEvaluators == null) {
			classEvaluators = new HashMap<String, List<PathConditionCoverageGoal>>();
			tracer.pathConditions.put(g.getClassName(), classEvaluators);
		}
		List<PathConditionCoverageGoal> methodEvaluators = classEvaluators.get(g.getMethodName());
		if (methodEvaluators == null) {
			methodEvaluators = new ArrayList<PathConditionCoverageGoal>();
			classEvaluators.put(g.getMethodName(), methodEvaluators);
		}
		methodEvaluators.add(g);
	}
	
	public static void addEvaluatorForPathCondition(PathConditionCoverageGoal g, BranchCoverageTestFitness relatedBracnhGoal) { /*SUSHI: Path condition fitness*/
		addEvaluatorForPathCondition(g);
		if (relatedBracnhGoal.getBranch() != null) {
			getExecutionTracer().pathConditionRelatedBranch.put(g, relatedBracnhGoal.getBranch().getActualBranchId());
		}
	}
	
	public static void logEvaluatorsForPathConditions() {
		ExecutionTracer tracer = getExecutionTracer();
		LoggingUtils.getEvoLogger().info("GOALS: " + tracer.pathConditions.toString());		
	}
	
	public static void removeEvaluatorForPathCondition(PathConditionCoverageGoal g) { /*SUSHI: Path condition fitness*/
		ExecutionTracer tracer = getExecutionTracer();
		Map<String, List<PathConditionCoverageGoal>> classEvaluators = tracer.pathConditions.get(g.getClassName());
		if (classEvaluators == null) return;
		List<PathConditionCoverageGoal> methodEvaluators = classEvaluators.get(g.getMethodName());
		if (methodEvaluators == null) return;
		methodEvaluators.remove(g);
		if (methodEvaluators.isEmpty()) {
			classEvaluators.remove(g.getMethodName());
			if (classEvaluators.isEmpty()) {
				tracer.pathConditions.remove(g.getClassName());
			}
		}
		LoggingUtils.getEvoLogger().info("PC GOALS: " + tracer.pathConditions.toString());
	}
	public static void removeAllEvaluatorsForPathConditions() {	/*SUSHI: Path condition fitness*/
		ExecutionTracer tracer = getExecutionTracer();
		tracer.pathConditions.clear();
	}
	/**
	 * <p>
	 * passedMutation
	 * </p>
	 * 
     * @param distance   a double.
     * @param mutationId a int.
	 */
	public static void passedMutation(double distance, int mutationId) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		checkTimeout();

		tracer.trace.mutationPassed(mutationId, distance);
	}

	/**
	 * <p>
	 * exceptionThrown
	 * </p>
	 * 
     * @param exception  a {@link java.lang.Object} object.
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
	 */
	public static void exceptionThrown(Object exception, String className,
	        String methodName) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		checkTimeout();

		tracer.trace.setExplicitException((Throwable) exception);

	}

	/**
	 * <p>
	 * statementExecuted
	 * </p>
	 */
	public static void statementExecuted() {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		checkTimeout();

		tracer.num_statements++;
	}

	/**
	 * <p>
	 * getNumStatementsExecuted
	 * </p>
	 * 
	 * @return a int.
	 */
	public int getNumStatementsExecuted() {
		return num_statements;
	}

	private ExecutionTracer() {
		trace = new ExecutionTraceProxy();
	}

	private static ExecutionTrace traceEventListener = null;//SUSHI: Aiding path conditions
	public static void setTraceEventListener(ExecutionTrace executionTraceEventListener) {
		traceEventListener = executionTraceEventListener;
	}

}
