package org.evosuite.coverage.epa;

import org.evosuite.Properties;

/**
 * This fitness function counts the degree of covered transitions. It is a
 * minimization function (less is better). The value 0.0 means all transitions
 * were covered.
 * 
 * @author galeotti
 *
 */
public class EPATransitionCoverageSuiteFitness extends EPASuiteFitness {

	public EPATransitionCoverageSuiteFitness(String epaXMLFilename) {
		super(epaXMLFilename);
	}

	@Override
	protected EPAFitnessFactory getGoalFactory(EPA epa) {
		return new EPATransitionCoverageFactory(Properties.TARGET_CLASS, epa);
	}

}