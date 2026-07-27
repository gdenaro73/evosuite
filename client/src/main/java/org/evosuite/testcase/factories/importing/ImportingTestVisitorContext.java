package org.evosuite.testcase.factories.importing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.evosuite.symbolic.TestCaseBuilder;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.variable.VariableReference;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.resolution.types.ResolvedType;

public class ImportingTestVisitorContext {
	private class ImportingTestVisitorProtocolException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		public ImportingTestVisitorProtocolException(String message) {
			super(message);
		}
	}

	private boolean enabled = false;
	private TestCaseBuilder builder = null;
	private HashMap<String, VariableReference> tracker = null;
	private List<TestCase> evoSuiteTestCases = new ArrayList<>();

	private int enabledExprVisitIndex = -1;
	private List<Expression> expressions = new ArrayList<>();
	private List<ResolvedType> typeInCaseOfNullLiterals = new ArrayList<>();
	private List<Integer> signMultiplier = new ArrayList<>();
	private List<VariableReference> newlyAddedReference = new ArrayList<>();

	public void enable() {
		enabled = true;
	}
	
	public void disable() {
		enabled = false;
	}
	
	public boolean isEnabled() {
		return enabled;
	}

	public void enableExpressionVisit(Expression expr, ResolvedType knownTypeInCaseOfNullLiterals) {
		enabledExprVisitIndex++;
		expressions.add(expr);
		typeInCaseOfNullLiterals.add(knownTypeInCaseOfNullLiterals);
		signMultiplier.add(1);
		newlyAddedReference.add(null);
	}
	
	public void disableExpressionVisit(Expression expr) {
		if (enabledExprVisitIndex < 0 || expressions.size() != enabledExprVisitIndex + 1) {
			throw new ImportingTestVisitorProtocolException("While visiting " + expr + 
					"... Requires to enable expression visit first");
		}
		if (!expressions.get(enabledExprVisitIndex).equals(expr)) {
			throw new ImportingTestVisitorProtocolException("While visiting " + expr + 
					"... Wrong match with latest visited expression " + 
					expressions.get(enabledExprVisitIndex));			
		}
		expressions.remove(enabledExprVisitIndex);
		typeInCaseOfNullLiterals.remove(enabledExprVisitIndex);
		signMultiplier.remove(enabledExprVisitIndex);
		newlyAddedReference.remove(enabledExprVisitIndex);
		enabledExprVisitIndex--;
	}
	
	public void endOfWorkSanityCheckOnExpressionVisit() {
		if (enabledExprVisitIndex >= 0) {
			throw new ImportingTestVisitorProtocolException("Overall visit completed " + 
					"while some expressions are still being visited: " + expressions);
		}		
	}

	public boolean isExpressionVisitEnabled() {
		return enabledExprVisitIndex >= 0;
	}

	public int getSignMultiplier() {
		if (!isExpressionVisitEnabled()) {
			throw new ImportingTestVisitorProtocolException("Requires to enable expression visit first");
		}
		return signMultiplier.get(enabledExprVisitIndex);
	}

	public void invertSignMultiplier() {
		if (!isExpressionVisitEnabled()) {
			throw new ImportingTestVisitorProtocolException("Requires to enable expression visit first");
		}
		signMultiplier.set(enabledExprVisitIndex, signMultiplier.get(enabledExprVisitIndex) * -1);
	}

	public ResolvedType getTypeInCaseOfNullLiterals() {
		if (!isExpressionVisitEnabled()) {
			throw new ImportingTestVisitorProtocolException("Requires to enable expression visit first");
		}
		return typeInCaseOfNullLiterals.get(enabledExprVisitIndex);
	}

	public VariableReference consumeNewlyAddedReference() {
		if (!isExpressionVisitEnabled()) {
			throw new ImportingTestVisitorProtocolException("Requires to enable expression visit first");
		}
		if (newlyAddedReference.get(enabledExprVisitIndex) == null) {
			throw new ImportingTestVisitorProtocolException("While visiting expression " 
					+ expressions.get(enabledExprVisitIndex) + "..." +
					"No newly added reference is available");
		}
		VariableReference vr = newlyAddedReference.get(enabledExprVisitIndex);
		newlyAddedReference.set(enabledExprVisitIndex, null);
		return vr;
	}

	public void setNewlyAddedReference(VariableReference vr) {
		if (!isExpressionVisitEnabled()) {
			throw new ImportingTestVisitorProtocolException("Requires to enable expression visit first");
		}
		if (newlyAddedReference.get(enabledExprVisitIndex) != null) {
			throw new ImportingTestVisitorProtocolException("While visiting expression " 
					+ expressions.get(enabledExprVisitIndex) + "..." +
					"Newly added reference already set as " +
					newlyAddedReference.get(enabledExprVisitIndex) + " -- Cannot now re-set as " + vr);
		}
		newlyAddedReference.set(enabledExprVisitIndex, vr);
	}

	public void add(TestCase tc) {
		this.evoSuiteTestCases.add(tc);
	}

	public List<TestCase> getTestCases() {
		return this.evoSuiteTestCases;
	}

	public TestCaseBuilder getBuilder() {
		return builder;
	}

	public void setBuilder(TestCaseBuilder builder) {
		this.builder = builder;
	}

	public HashMap<String, VariableReference> getTracker() {
		return tracker;
	}

	public void setTracker(HashMap<String, VariableReference> tracker) {
		this.tracker = tracker;
	}
}
