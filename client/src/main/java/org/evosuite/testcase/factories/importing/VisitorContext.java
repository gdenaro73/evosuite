package org.evosuite.testcase.factories.importing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.evosuite.symbolic.TestCaseBuilder;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.variable.VariableReference;

public class VisitorContext {
	private boolean enabled = false;
	private TestCaseBuilder builder = null;
	private HashMap<String, VariableReference> tracker = null;
	private List<TestCase> evoSuiteTestCases = new ArrayList<>();
	private List<VariableReference> newlyAddedReferenceStack = new ArrayList<>();

	public void enable() {
		enabled = true;
	}
	
	public void disable() {
		enabled = false;
	}
	
	public boolean isEnabled() {
		return enabled;
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

	public VariableReference popNewlyAddedReference() {
		if (newlyAddedReferenceStack.isEmpty()) {
			throw new ArrayIndexOutOfBoundsException("No newly added reference is available");
		}
		return this.newlyAddedReferenceStack.remove(0);
	}

	public void pushNewlyAddedReference(VariableReference vr) {
		this.newlyAddedReferenceStack.add(0, vr);
	}
}
