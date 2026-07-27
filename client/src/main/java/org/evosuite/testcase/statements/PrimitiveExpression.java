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
package org.evosuite.testcase.statements;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.generic.GenericAccessibleObject;

import java.io.PrintStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

// TODO-JRO Implement methods of PrimitiveExpression as needed

/**
 * Represents a primitive expression of the form {@code lhs op rhs} where {@code op} is a binary
 * operator, and {@code lhs} and {@code rhs} are the left-hand side and right-hand side of {@code
 * op}, respectively.
 */
public class PrimitiveExpression extends AbstractStatement {

    public enum Operator {
        TIMES("*"), //
        DIVIDE("/"), //
        REMAINDER("%"), //
        PLUS("+"), //
        MINUS("-"), //
        LEFT_SHIFT("<<"), //
        RIGHT_SHIFT_SIGNED(">>"), //
        RIGHT_SHIFT_UNSIGNED(">>>"), //
        LESS("<"), //
        GREATER(">"), //
        LESS_EQUALS("<="), //
        GREATER_EQUALS(">="), //
        EQUALS("=="), //
        NOT_EQUALS("!="), //
        XOR("^"), //
        AND("&"), //
        OR("|"), //
        CONDITIONAL_AND("&&"), //
        CONDITIONAL_OR("||"),
        CASTING("(<type>)") //supported in subclass
        ; 

        public static Operator toOperator(String code) {
            for (Operator operator : values()) {
                if (operator.code.equals(code)) {
                    return operator;
                }
            }
            throw new RuntimeException("No operator for " + code);
        }

        private final String code;

        Operator(String code) {
            this.code = code;
        }

        public String toCode() {
            return code;
        }
    }

    private static final long serialVersionUID = 1L;

    private VariableReference leftOperand;
    private final Operator operator;
    private VariableReference rightOperand;

    /**
     * <p>
     * Constructor for PrimitiveExpression.
     * </p>
     *
     * @param testCase     a {@link org.evosuite.testcase.TestCase} object.
     * @param reference    a {@link org.evosuite.testcase.variable.VariableReference} object.
     * @param leftOperand  a {@link org.evosuite.testcase.variable.VariableReference} object.
     * @param operator     a {@link org.evosuite.testcase.statements.PrimitiveExpression.Operator}
     *                     object.
     * @param rightOperand a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public PrimitiveExpression(TestCase testCase, VariableReference reference,
                               VariableReference leftOperand, Operator operator,
                               VariableReference rightOperand) {
        super(testCase, reference);
        this.leftOperand = leftOperand;
        this.operator = operator;
        this.rightOperand = rightOperand;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Statement copy(TestCase newTestCase, int offset) {
        VariableReference newRetVal = new VariableReferenceImpl(newTestCase,
                retval.getType());
        VariableReference newLeftOperand = newTestCase.getStatement(leftOperand.getStPosition()).getReturnValue();
        VariableReference newRightOperand = newTestCase.getStatement(rightOperand.getStPosition()).getReturnValue();
        return new PrimitiveExpression(newTestCase, newRetVal, newLeftOperand, operator,
                newRightOperand);
        //		return new PrimitiveExpression(newTestCase, retval, leftOperand, operator, rightOperand);
    }

    enum PromotionType {
    	INT_PROMOTION {
    		Number PLUS(Number a, Number b) {
    			return a.intValue() + b.intValue();
    		}
    		Number MINUS(Number a, Number b) {
    			return a.intValue() - b.intValue();
    		}
    		Number TIMES(Number a, Number b) {
    			return a.intValue() * b.intValue();
    		}
    		Number DIVIDE(Number a, Number b) {
    			return a.intValue() / b.intValue();
    		}
    		Number REMAINDER(Number a, Number b) {
    			return a.intValue() % b.intValue();
    		}
    		Number AND(Number a, Number b) {
    			return a.intValue() & b.intValue();
    		}
    		Number OR(Number a, Number b) {
    			return a.intValue() | b.intValue();
    		}
    		Number XOR(Number a, Number b) {
    			return a.intValue() ^ b.intValue();
    		}
    		Number LEFT_SHIFT(Number a, Number b) {
    			return a.intValue() << b.intValue();
    		}
    		Number RIGHT_SHIFT_SIGNED(Number a, Number b) {
    			return a.intValue() >> b.intValue();
    		}
    		Number RIGHT_SHIFT_UNSIGNED(Number a, Number b) {
    			return a.intValue() >>> b.intValue();
    		}
			boolean LESS(Number a, Number b) {
    			return a.intValue() < b.intValue();
			}
			boolean GREATER(Number a, Number b) {
    			return a.intValue() > b.intValue();
			}
			boolean LESS_EQUALS(Number a, Number b) {
    			return a.intValue() <= b.intValue();
			}
			boolean GREATER_EQUALS(Number a, Number b) {
    			return a.intValue() >= b.intValue();
			}
    	},
    	LONG_PROMOTION {
    		Number PLUS(Number a, Number b) {
    			return a.longValue() + b.longValue();
    		}
    		Number MINUS(Number a, Number b) {
    			return a.longValue() - b.longValue();
    		}
    		Number TIMES(Number a, Number b) {
    			return a.longValue() * b.longValue();
    		}
    		Number DIVIDE(Number a, Number b) {
    			return a.longValue() / b.longValue();
    		}
    		Number REMAINDER(Number a, Number b) {
    			return a.longValue() % b.longValue();
    		}
    		Number AND(Number a, Number b) {
    			return a.longValue() & b.longValue();
    		}
    		Number OR(Number a, Number b) {
    			return a.longValue() | b.longValue();
    		}
    		Number XOR(Number a, Number b) {
    			return a.longValue() ^ b.longValue();
    		}
    		Number LEFT_SHIFT(Number a, Number b) {
    			return a.longValue() << b.intValue();
    		}
    		Number RIGHT_SHIFT_SIGNED(Number a, Number b) {
    			return a.longValue() >> b.intValue();
    		}
    		Number RIGHT_SHIFT_UNSIGNED(Number a, Number b) {
    			return a.longValue() >>> b.intValue();
    		}
			boolean LESS(Number a, Number b) {
    			return a.longValue() < b.longValue();
			}
			boolean GREATER(Number a, Number b) {
    			return a.longValue() > b.longValue();
			}
			boolean LESS_EQUALS(Number a, Number b) {
    			return a.longValue() <= b.longValue();
			}
			boolean GREATER_EQUALS(Number a, Number b) {
    			return a.longValue() >= b.longValue();
			}
    	},
    	FLOAT_PROMOTION {
    		Number PLUS(Number a, Number b) {
    			return a.floatValue() + b.floatValue();
    		}
    		Number MINUS(Number a, Number b) {
    			return a.floatValue() - b.floatValue();
    		}
    		Number TIMES(Number a, Number b) {
    			return a.floatValue() * b.floatValue();
    		}
    		Number DIVIDE(Number a, Number b) {
    			return a.floatValue() / b.floatValue();
    		}
    		Number REMAINDER(Number a, Number b) {
    			return a.floatValue() % b.floatValue();
    		}
    		Number AND(Number a, Number b) {
                throw new UnsupportedOperationException("Binary operator & not supported for floats");
    		}
    		Number OR(Number a, Number b) {
                throw new UnsupportedOperationException("Binary operator | not supported for floats");
    		}
    		Number XOR(Number a, Number b) {
    			throw new UnsupportedOperationException("Binary operator ^ not supported for floats");
    		}
    		Number LEFT_SHIFT(Number a, Number b) {
                throw new UnsupportedOperationException("Binary operator << not supported for floats");
    		}
    		Number RIGHT_SHIFT_SIGNED(Number a, Number b) {
                throw new UnsupportedOperationException("Binary operator >> not supported for floats");
    		}
    		Number RIGHT_SHIFT_UNSIGNED(Number a, Number b) {
                throw new UnsupportedOperationException("Binary operator >>> not supported for floats");
    		}
			boolean LESS(Number a, Number b) {
    			return a.floatValue() < b.floatValue();
			}
			boolean GREATER(Number a, Number b) {
    			return a.floatValue() > b.floatValue();
			}
			boolean LESS_EQUALS(Number a, Number b) {
    			return a.floatValue() <= b.floatValue();
			}
			boolean GREATER_EQUALS(Number a, Number b) {
    			return a.floatValue() >= b.floatValue();
			}
    	},
    	DOUBLE_PROMOTION {
    		Number PLUS(Number a, Number b) {
    			return a.doubleValue() + b.doubleValue();
    		}
    		Number MINUS(Number a, Number b) {
    			return a.doubleValue() - b.doubleValue();
    		}
    		Number TIMES(Number a, Number b) {
    			return a.doubleValue() * b.doubleValue();
    		}
    		Number DIVIDE(Number a, Number b) {
    			return a.doubleValue() / b.doubleValue();
    		}
    		Number REMAINDER(Number a, Number b) {
    			return a.doubleValue() % b.doubleValue();
    		}
    		Number AND(Number a, Number b) {
                throw new UnsupportedOperationException("Binary operator & not supported for doubles");
    		}
    		Number OR(Number a, Number b) {
                throw new UnsupportedOperationException("Binary operator | not supported for doubles");
    		}
    		Number XOR(Number a, Number b) {
    			throw new UnsupportedOperationException("Binary operator ^ not supported for doubles");
    		}
    		Number LEFT_SHIFT(Number a, Number b) {
                throw new UnsupportedOperationException("Binary operator << not supported for doubles");
    		}
    		Number RIGHT_SHIFT_SIGNED(Number a, Number b) {
                throw new UnsupportedOperationException("Binary operator >> not supported for doubles");
    		}
    		Number RIGHT_SHIFT_UNSIGNED(Number a, Number b) {
                throw new UnsupportedOperationException("Binary operator >>> not supported for doubles");
    		}
			boolean LESS(Number a, Number b) {
    			return a.doubleValue() < b.doubleValue();
			}
			boolean GREATER(Number a, Number b) {
    			return a.doubleValue() > b.doubleValue();
			}
			boolean LESS_EQUALS(Number a, Number b) {
    			return a.doubleValue() <= b.doubleValue();
			}
			boolean GREATER_EQUALS(Number a, Number b) {
    			return a.doubleValue() >= b.doubleValue();
			}
    	};
		
    	abstract Number PLUS(Number a, Number b);
		abstract Number MINUS(Number a, Number b);
		abstract Number TIMES(Number a, Number b);
		abstract Number DIVIDE(Number a, Number b);
		abstract Number REMAINDER(Number a, Number b);
		abstract Number AND(Number a, Number b);
		abstract Number OR(Number a, Number b);
		abstract Number XOR(Number a, Number b);
		abstract Number LEFT_SHIFT(Number a, Number b);
		abstract Number RIGHT_SHIFT_SIGNED(Number a, Number b);
		abstract Number RIGHT_SHIFT_UNSIGNED(Number a, Number b);
		abstract boolean LESS(Number a, Number b);
		abstract boolean GREATER(Number a, Number b);
		abstract boolean LESS_EQUALS(Number a, Number b);
		abstract boolean GREATER_EQUALS(Number a, Number b);

		public static PromotionType resolve(Object o1, Object o2) {
			if (!(o1 instanceof Number) || !(o2 instanceof Number)) {
                throw new UnsupportedOperationException("Binary operator supported only for Numbers, cannot be applied to operators " + o1 + " and " + o2);
			}
            if (o1 instanceof Double || o2 instanceof Double) {
            	return PromotionType.DOUBLE_PROMOTION;
            } else if (o1 instanceof Float || o2 instanceof Float) {
            	return PromotionType.FLOAT_PROMOTION;
            } else if (o1 instanceof Long || o2 instanceof Long) {
            	return PromotionType.LONG_PROMOTION;
            } else {
            	return PromotionType.INT_PROMOTION;
            }  
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Throwable execute(Scope scope, PrintStream out)
            throws IllegalArgumentException {
        try {
            Object o1 = leftOperand.getObject(scope);
            Object o2 = rightOperand.getObject(scope);
            
            Object ret;
        	switch (operator) {
        	case PLUS:
            	ret = PromotionType.resolve(o1, o2).PLUS((Number) o1, (Number) o2);
            	break;
        	case MINUS:
            	ret = PromotionType.resolve(o1, o2).MINUS((Number) o1, (Number) o2);
            	break;
            case TIMES:
            	ret = PromotionType.resolve(o1, o2).TIMES((Number) o1, (Number) o2);
           	break;
            case DIVIDE:
            	ret = PromotionType.resolve(o1, o2).DIVIDE((Number) o1, (Number) o2);
            	break;
            case REMAINDER:
            	ret = PromotionType.resolve(o1, o2).REMAINDER((Number) o1, (Number) o2);
            	break;
            case AND:
            	if (o1 instanceof Boolean && o2 instanceof Boolean) {
            		ret = (boolean) o1 & (boolean) o2;
            	} else {
            		ret = PromotionType.resolve(o1, o2).AND((Number) o1, (Number) o2);
            	}
            	break;
            case OR:
            	if (o1 instanceof Boolean && o2 instanceof Boolean) {
            		ret = (boolean) o1 | (boolean) o2;
            	} else {
            		ret = PromotionType.resolve(o1, o2).OR((Number) o1, (Number) o2);
            	}
            	break;
            case XOR:
            	if (o1 instanceof Boolean && o2 instanceof Boolean) {
            		ret = (boolean) o1 ^ (boolean) o2;
            	} else {
            		ret = PromotionType.resolve(o1, o2).XOR((Number) o1, (Number) o2);
            	}
            	break;
            case LEFT_SHIFT:
            	ret = PromotionType.resolve(o1, o2).LEFT_SHIFT((Number) o1, (Number) o2);
            	break;
            case RIGHT_SHIFT_SIGNED:
            	ret = PromotionType.resolve(o1, o2).RIGHT_SHIFT_SIGNED((Number) o1, (Number) o2);
            	break;
            case RIGHT_SHIFT_UNSIGNED:
            	ret = PromotionType.resolve(o1, o2).RIGHT_SHIFT_UNSIGNED((Number) o1, (Number) o2);
            	break;
            case LESS:
            	ret = PromotionType.resolve(o1, o2).LESS((Number) o1, (Number) o2);
            	break;
            case GREATER:
            	ret = PromotionType.resolve(o1, o2).GREATER((Number) o1, (Number) o2);
            	break;
            case LESS_EQUALS:
            	ret = PromotionType.resolve(o1, o2).LESS_EQUALS((Number) o1, (Number) o2);
            	break;
            case GREATER_EQUALS:
            	ret = PromotionType.resolve(o1, o2).GREATER_EQUALS((Number) o1, (Number) o2);
            	break;
            case EQUALS:
            	ret = Objects.equals(o1, o2);
            	break;
            case NOT_EQUALS:
            	ret = !Objects.equals(o1, o2);
            	break;
            case CONDITIONAL_AND:
            	ret = (boolean) o1 && (boolean) o2;
            	break;
            case CONDITIONAL_OR:
            	ret = (boolean) o1 || (boolean) o2;
            	break;
            default:
                throw new UnsupportedOperationException("Binary operator not supported: " + operator);
        	}
   
        	scope.setObject(retval, ret);                	
            return null;
        } catch (CodeUnderTestException e) {
            return e;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GenericAccessibleObject<?> getAccessibleObject() {
        throw new UnsupportedOperationException(
                "Method getAccessibleObject not implemented!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getCode() {
        String code = ((Class<?>) retval.getType()).getSimpleName() + " "
                + retval.getName() + " = " + leftOperand.getName() + " "
                + operator.toCode() + " " + rightOperand.getName() + ";";
        return code;
    }

    /**
     * <p>
     * Getter for the field <code>leftOperand</code>.
     * </p>
     *
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference getLeftOperand() {
        return leftOperand;
    }

    /**
     * <p>
     * Getter for the field <code>operator</code>.
     * </p>
     *
     * @return a {@link org.evosuite.testcase.statements.PrimitiveExpression.Operator}
     * object.
     */
    public Operator getOperator() {
        return operator;
    }

    /**
     * <p>
     * Getter for the field <code>rightOperand</code>.
     * </p>
     *
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference getRightOperand() {
        return rightOperand;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<VariableReference> getUniqueVariableReferences() {
        throw new UnsupportedOperationException(
                "Method getUniqueVariableReferences not implemented!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<VariableReference> getVariableReferences() {
        Set<VariableReference> result = new LinkedHashSet<>();
        result.add(retval);
        result.add(leftOperand);
        result.add(rightOperand);
        result.addAll(getAssertionReferences());
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAssignmentStatement() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void replace(VariableReference oldVar, VariableReference newVar) {
        if (leftOperand.equals(oldVar)) {
            leftOperand = newVar;
        }
        if (rightOperand.equals(oldVar)) {
            rightOperand = newVar;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean same(Statement s) {
        if (this == s)
            return true;
        if (s == null)
            return false;
        if (getClass() != s.getClass())
            return false;

        PrimitiveExpression ps = (PrimitiveExpression) s;

        return operator.equals(ps.operator) && leftOperand.same(ps.leftOperand)
                && rightOperand.same(ps.rightOperand);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return getCode();
    }
}
