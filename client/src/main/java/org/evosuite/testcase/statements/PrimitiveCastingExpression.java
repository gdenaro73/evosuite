package org.evosuite.testcase.statements;

import java.io.PrintStream;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.EvosuiteError;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;

public class PrimitiveCastingExpression extends PrimitiveExpression {
    private static final long serialVersionUID = 1L;

	private Class<?> castType;
	
	public PrimitiveCastingExpression(TestCase testCase, VariableReference reference,
			VariableReference rightOperand, Class<?> castType) {
		/* Set rightOperand as both left- and right-operand to make 
		 * superclass methods happy */
		super(testCase, reference, rightOperand, Operator.CASTING, rightOperand);

		if (castType == null) {
			throw new EvosuiteError("castType cannot be null in PrimitiveCastingExpression");
		}
		this.castType = castType;
	}

	@Override
	public Statement copy(TestCase newTestCase, int offset) {
        VariableReference newRetVal = new VariableReferenceImpl(newTestCase,
                retval.getType());
        VariableReference newRightOperand = newTestCase.getStatement(getRightOperand().getStPosition()).getReturnValue();
        return new PrimitiveCastingExpression(newTestCase, newRetVal, newRightOperand, castType);
	}

	public Class<?> getCastType() {
		return castType;
	}
	
	@Override
	public Throwable execute(Scope scope, PrintStream out) 
			throws IllegalArgumentException {
		try {
			final Object value = getRightOperand().getObject(scope);
			Object ret;
			Number numValue;
			if (value instanceof Character) {
				char c = (char) value;
				if (castType == byte.class || castType == Byte.class) {
					numValue = (byte) c;
				} else if (castType == short.class || castType == Short.class) {
					numValue = (short) c;
				} else {
					numValue = (int) c; //so that larger cast cases handled later below
				}
			} else if (value instanceof Number) {
				numValue = (Number) value;
			} else {
                throw new UnsupportedOperationException("Casting to (" +  castType.getSimpleName() + ") not supported for variable: " + getRightOperand());
			}
			
			if (castType == char.class || castType == Character.class) {
				int i = numValue.intValue();
				ret = (char) i;
			} else if (castType == byte.class || castType == Byte.class) {
				ret = numValue.byteValue();
			} else if (castType == short.class || castType == Short.class) {
				ret = numValue.shortValue();
			} else if (castType == int.class || castType == Integer.class) {
				ret = numValue.intValue();
			} else if (castType == long.class || castType == Long.class) {
				ret = numValue.longValue();
			} else if (castType == float.class || castType == Float.class) {
				ret = numValue.floatValue();
			} else if (castType == double.class || castType == Double.class) {
				ret = numValue.doubleValue();
			} else {
                throw new UnsupportedOperationException("Casting to (" +  castType.getSimpleName() + ") not supported for variable: " + getRightOperand());
			}
			retval.setObject(scope, ret);
			return null;
		} catch (IllegalArgumentException e) {
			logger.error("Error casting value of type "
					+ getRightOperand().getSimpleClassName() + " defined at statement "
					+ tc.getStatement(getRightOperand().getStPosition()).getCode()
					+ ", casting statement: "
					+ tc.getStatement(retval.getStPosition()).getCode()
					+ "; SUT=" + Properties.TARGET_CLASS);
			// FIXXME: IllegalArgumentException may happen when we only have generators
			// for an abstract supertype and not the concrete type that we need!
			throw e;
		} catch (CodeUnderTestException e) {
			return e;
		} catch (Throwable e) {
			throw new EvosuiteError(e);
		}
	}

	@Override
	public String getCode() {
        String code = ((Class<?>) retval.getType()).getSimpleName() + " "
                + retval.getName() + " = (" + castType.getSimpleName() + ") "
                + getRightOperand().getName() + ";";
        return code;
	}
}
