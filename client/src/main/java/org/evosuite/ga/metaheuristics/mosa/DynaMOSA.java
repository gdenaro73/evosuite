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
package org.evosuite.ga.metaheuristics.mosa;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.evosuite.Properties.Criterion;
import org.evosuite.Properties.PathConditionTarget;
import org.evosuite.TestSuiteGenerator;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.pathcondition.PathConditionCoverageGoalFitness;
import org.evosuite.ga.metaheuristics.mosa.structural.PathConditionManager;
import org.evosuite.ga.metaheuristics.mosa.structural.SeepepManager;
import org.evosuite.ga.metaheuristics.mosa.structural.TestFitnessSerializationUtils;
import org.evosuite.ga.metaheuristics.mosa.structural.AidingPathConditionManager;
import org.evosuite.testcase.execution.EvosuiteError;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testcase.factories.importing.CodeTestVisitor;
import org.evosuite.testsuite.AbstractFitnessFactory;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteMinimizer;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.archive.Archive;
import org.evosuite.ga.comparators.OnlyCrowdingComparator;
import org.evosuite.ga.metaheuristics.mosa.structural.MultiCriteriaManager;
import org.evosuite.ga.operators.ranking.CrowdingDistance;
import org.evosuite.junit.writer.TestSuiteWriter;
import org.evosuite.rmi.ClientServices;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Implementation of the DynaMOSA (Many Objective Sorting Algorithm) described in the paper
 * "Automated Test Case Generation as a Many-Objective Optimisation Problem with Dynamic Selection
 * of the Targets".
 *
 * @author Annibale Panichella, Fitsum M. Kifetew, Paolo Tonella
 */
public class DynaMOSA extends AbstractMOSA {

	private static final long serialVersionUID = 146182080947267628L;

	private static final Logger logger = LoggerFactory.getLogger(DynaMOSA.class);

    /**
     * Manager to determine the test goals to consider at each generation
     */
	protected MultiCriteriaManager goalsManager = null;

	protected CrowdingDistance<TestChromosome> distance = new CrowdingDistance<>();

	private int unchangedPopulationIterations = 0; /*SUSHI: Reset*/
	private int unchangedFitnessIterations = 0; /*Import and export tests*/

	/**
	 * Constructor based on the abstract class {@link AbstractMOSA}.
	 *
	 * @param factory
	 */
	public DynaMOSA(ChromosomeFactory<TestChromosome> factory) {
		super(factory);
	}

    /**
     * {@inheritDoc}
     */
	@Override
	protected void evolve() {
		int goodCrosoversAtBegin = goodOffsprings; /*SUSHI: Reset*/

		// Generate offspring, compute their fitness, update the archive and coverage goals.
		List<TestChromosome> offspringPopulation = this.breedNextGeneration();
		
		// At given intervals, poll for new test cases waiting for being injected in the population and do it
		if (Properties.INJECTED_TESTS_CHECKING_RATE > 0 && 
				getAge() % Properties.INJECTED_TESTS_CHECKING_RATE == 1) { /*Import and export tests*/
			addNewlyInjectedTests(offspringPopulation);
		}	

		// Create the union of parents and offspring
		List<TestChromosome> union = new ArrayList<>(this.population.size() + offspringPopulation.size());
		union.addAll(this.population);
		union.addAll(offspringPopulation);
		union.addAll(population);

		// Ranking the union
		logger.debug("Union Size = {}", union.size());

		// Ranking the union using the best rank algorithm (modified version of the non dominated
		// sorting algorithm)
		this.rankingFunction.computeRankingAssignment(union, this.goalsManager.getCurrentGoals());

		// let's form the next population using "preference sorting and non-dominated sorting" on the
		// updated set of goals
		int remain = Math.max(Properties.POPULATION, this.rankingFunction.getSubfront(0).size());
		
		if(Properties.AVOID_REPLICAS_OF_INDIVIDUALS) { /*SUSHI: Prevent multiple copies of individuals*/
			remain = Math.min(remain, union.size());
		}

		int index = 0;
		population.clear();
		// Obtain the first front
		List<TestChromosome> front = this.rankingFunction.getSubfront(index);

		// Successively iterate through the fronts (starting with the first non-dominated front)
		// and insert their members into the population for the next generation. This is done until
		// all fronts have been processed or we hit a front that is too big to fit into the next
		// population as a whole.
		while ((remain > 0) && (remain >= front.size()) && !front.isEmpty()) {

			// Assign crowding distance to individuals
			this.distance.fastEpsilonDominanceAssignment(front, this.goalsManager.getCurrentGoals());

			// Add the individuals of this front
			addToPopulation(front); //GIO: population.addAll(front);

			// Decrement remain
			remain = remain - front.size();

			// Obtain the next front
			index++;

			if (remain > 0) {
				front = this.rankingFunction.getSubfront(index);
			} 
		} 

		// In case the population for the next generation has not been filled up completely yet,
		// we insert the best individuals from the current front (the one that was too big to fit
		// entirely) until there are no more free places left. To this end, and in an effort to
		// promote diversity, we consider those individuals with a higher crowding distance as
		// being better.
		if (remain > 0 && !front.isEmpty()) { // front contains individuals to insert
			this.distance.fastEpsilonDominanceAssignment(front, this.goalsManager.getCurrentGoals());
			front.sort(new OnlyCrowdingComparator<>());
			for (int k = 0; k < remain; k++) {
				addToPopulation(front.get(k)); //GIO: this.population.add(front.get(k));	
			}
		}

		if(Properties.NO_CHANGE_ITERATIONS_BEFORE_ASSUMING_STAGNATION > 0) { /*Import and export tests*/
			boolean someChanges = goodOffsprings > goodCrosoversAtBegin; //TODO: better if we check the global fitness
			handleStagnation(someChanges); 
		}
		
		if(Properties.NO_CHANGE_ITERATIONS_BEFORE_RESET > 0) { /*SUSHI: Reset*/ 
			boolean someChanges = goodOffsprings > goodCrosoversAtBegin;
			handleReset(someChanges); 
		}
		
		currentIteration++;
		
		// Console output each 50 iterations
		if (currentIteration % 50 == 0) {
			LoggingUtils.getEvoLogger().info("\n***ITERATION: {}", currentIteration);
			//LoggingUtils.getEvoLogger().info("Population size= {}", population.size());
			//LoggingUtils.getEvoLogger().info("N. fronts = {}", ranking.getNumberOfSubfronts());
			LoggingUtils.getEvoLogger().info("* Covered goals = {}", goalsManager.getCoveredGoals().size());
			LoggingUtils.getEvoLogger().info("* Current goals = {}", goalsManager.getCurrentGoals().size());
			int numOfPCGoals = 0;
			for (FitnessFunction<TestChromosome> g : goalsManager.getCurrentGoals()) {
				if (g instanceof PathConditionCoverageGoalFitness) ++numOfPCGoals;
			}
			LoggingUtils.getEvoLogger().info("* Current PC goals = {}", numOfPCGoals);
			if(ArrayUtil.contains(Properties.CRITERION, Criterion.BRANCH_WITH_AIDING_PATH_CONDITIONS)) {
				int satisfied = ((AidingPathConditionManager) goalsManager).getCoveredPathConditions().size();
				LoggingUtils.getEvoLogger().info("* Satisfied PC goals = {}", satisfied);
			}

			//LoggingUtils.getEvoLogger().info("Uncovered goals = {}", goalsManager.getUncoveredGoals().size());
			LoggingUtils.getEvoLogger().info("* {} no change iterations,  {} resets, {} good offsprings ({} mutation only)", unchangedPopulationIterations, resets, goodOffsprings, goodOffspringsMutationOnly);
			LoggingUtils.getEvoLogger().info("* Top front includes {} individuals:", rankingFunction.getSubfront(0).size());
			for (TestChromosome c : rankingFunction.getSubfront(0)) {
				printInfo(c);			
			}
			/*for (int f = 1; f < rankingFunction.getNumberOfSubfronts(); f++) {
				LoggingUtils.getEvoLogger().info("* {} front includes {} individuals:", f, rankingFunction.getSubfront(f).size());
				for (TestChromosome c : rankingFunction.getSubfront(f)) {
					printInfo(c);			
				}	
			}*/
		}
		//logger.debug("N. fronts = {}", ranking.getNumberOfSubfronts());
		//logger.debug("1* front size = {}", ranking.getSubfront(0).size());
		logger.debug("Covered goals = {}", goalsManager.getCoveredGoals().size());
		logger.debug("Current goals = {}", goalsManager.getCurrentGoals().size());
		logger.debug("Uncovered goals = {}", goalsManager.getUncoveredGoals().size());
	}

	private void addNewlyInjectedTests(List<TestChromosome> offspringPopulation) { /*Import and export tests*/
		String retrievedFromMaster = ClientServices.getInstance().getClientNode().retrieveInjectedTestCases();
		if (retrievedFromMaster != null) { //There exist new tests recently injected
			String[] pathsToTestClasses = retrievedFromMaster.split(File.pathSeparator);
			LoggingUtils.getEvoLogger().info("\n\n* ITERATION " + getAge() + ", RETRIEVED NEWLY INJECTED TESTS: ");
			List<TestChromosome> newTests = Arrays.stream(pathsToTestClasses)
					.map(path -> importTest(path))
					.flatMap(List::stream)
					.collect(Collectors.toList());
			offspringPopulation.addAll(newTests); 
		} //else LoggingUtils.getEvoLogger().info("\n\n* ITERATION " + getAge() + ", NO NEWLY INJECTED TESTS");
	}

	private List<TestChromosome> importTest(String pathToTestClass) { /*Import and export tests*/
		try {
			List<TestCase> testCases = CodeTestVisitor._I().getTestCases(pathToTestClass);
			List<TestChromosome> testChromosomes = new ArrayList<>();
			for (TestCase t: testCases) {
				TestChromosome individual = new TestChromosome(); 
				individual.setTestCase(t);
				testChromosomes.add(individual);
				LoggingUtils.getEvoLogger().info("Retrieved test case:\n" + individual);
				fitnessFunctions.forEach(individual::addFitness);
				calculateFitness(individual);
			}
			return testChromosomes;
		} catch (IOException e) {
			throw new EvosuiteError(e);	
			//throw new EvosuiteError("Unexpected error while importing tests from class " + testClassPath + " due to: " + e);
		} 
	}
	
	private boolean handleStagnation(boolean changed) { /*Import and export tests*/
		if (changed) unchangedFitnessIterations = 0;
		else unchangedFitnessIterations++;

		if (goalsManager.getCurrentGoals().isEmpty()) {
			return false; // search finished
		}

		if (unchangedFitnessIterations >  Properties.NO_CHANGE_ITERATIONS_BEFORE_ASSUMING_STAGNATION) {
			unchangedFitnessIterations = 0;
			
			/* Alternative solution:
			 * With this implementation, we minimize the test suite wrt both the covered goals and the
			 * yet-uncovered goals. Then we associate the yet-uncovered goals with their closer-to-cover test case.
			 * Currently we prefer the other solution (see below) in which each yet-uncovered goal G is 
			 * associated with a closer-to-cover test case specifically minimized wrt to goal G only.
			 * Our hypothesis is that a specifically minimized test case can be more informative for 
			 * external tools to investigate how to cover the missing goal G.
			TestSuiteChromosome currentTestSuite = generateSuite().clone();
			this.rankingFunction.getSubfront(0).forEach(ind -> currentTestSuite.addTest(ind.clone()));
			TestSuiteMinimizer minimizer = new TestSuiteMinimizer(new AbstractFitnessFactory<TestFitnessFunction>() {
				@Override
				public List<TestFitnessFunction> getCoverageGoals() {
					ArrayList<TestFitnessFunction> goals = new ArrayList<TestFitnessFunction>();
					goals.addAll(goalsManager.getCoveredGoals());
					goals.addAll(goalsManager.getCurrentGoals());
					return goals;
				}
			    @Override
			    public double getFitness(TestSuiteChromosome suite) {
			        ExecutionTracer.enableTraceCalls();
			        double covered = 0;
			        for (TestFitnessFunction goal : getCoverageGoals()) {
			        	double bestFitnessForGoal = Double.MAX_VALUE;
			            for (TestChromosome test : suite.getTestChromosomes()) {
			                if (goal.isCovered(test)) {
			                	bestFitnessForGoal = 0;
			                    break;
			                } else if (goal.getFitness(test) < bestFitnessForGoal) {
			                	bestFitnessForGoal = goal.getFitness(test);
			                }
			            }
			            covered += 1 - bestFitnessForGoal;
			        }
			        ExecutionTracer.disableTraceCalls();
			        return getCoverageGoals().size() - covered;
			    }
			});
			minimizer.setShallRemoveRedundantTests(false);
			minimizer.minimize(currentTestSuite, false);
			
			Map<TestFitnessFunction, Integer> serializedGoals = new HashMap<>();
			goalsManager.getCurrentGoals().forEach(g -> {
				int bestFitnessTestPosition = -1;
				double bestFitness = Double.MAX_VALUE;
				int i = 0;
				for (TestChromosome tc: currentTestSuite.getTestChromosomes()) {
					double fitness = tc.getFitness(g);
					if (fitness < bestFitness) {
						bestFitness = fitness;
						bestFitnessTestPosition = i;
					}
					i++;
				}
				if (bestFitnessTestPosition == -1) {
					throw new EvosuiteError("This should not happen: Uncovered frontier branch goal with no reaching test case");
				}
				serializedGoals.put(TestFitnessSerializationUtils.makeSerializableForNonEvosuiteClients(g), bestFitnessTestPosition);
			}); */

			// Currently preferred solution:
			// 1. we minimize the current archived test cases wrt the covered goals
			TestSuiteChromosome currentTestSuite = generateSuite().clone();
			TestSuiteMinimizer minimizer = new TestSuiteMinimizer(TestSuiteGenerator.getFitnessFactories());
			minimizer.minimize(currentTestSuite, false);

			// 2. We identify the closer-to-cover test case for each uncovered goal and minimize it
			Map<TestFitnessFunction, Integer> serializedGoals = new HashMap<>();
			goalsManager.getCurrentGoals().forEach(g -> {
				TestChromosome bestTest = null;
				double bestFitness = Double.MAX_VALUE;
				for (TestChromosome tc: this.rankingFunction.getSubfront(0)) {
					double fitness = tc.getFitness(g);
					if (fitness < bestFitness) {
						bestFitness = fitness;
						bestTest = tc;
					}
				}
				if (bestTest == null) {
					throw new EvosuiteError("This should not happen: Uncovered frontier branch goal with no reaching test case in top front");
				}
				TestSuiteChromosome tsuite = new TestSuiteChromosome();
				tsuite.addTestChromosome(bestTest.clone());
				TestSuiteMinimizer minimizerForGoal = new TestSuiteMinimizer(new AbstractFitnessFactory<TestFitnessFunction>() {
					@Override
					public List<TestFitnessFunction> getCoverageGoals() {
						ArrayList<TestFitnessFunction> goals = new ArrayList<TestFitnessFunction>();
						goals.add(g);
						return goals;
					}
				    @Override
				    public double getFitness(TestSuiteChromosome suite) {
				        ExecutionTracer.enableTraceCalls();
				        double fitness = g.getFitness(suite.getTestChromosome(0));
				        ExecutionTracer.disableTraceCalls();
				        return fitness;
				    }
				});
				minimizerForGoal.setShallRemoveRedundantTests(false); //target goal is not covered yet, test mis-identified as redundant
				minimizerForGoal.minimize(tsuite, false); //Set minimizePerTest=false, it keeps the test suite with 1 test only 
				if (tsuite.size() != 1) {
					throw new EvosuiteError("\n\n* UNEXPECTED - MINIMIZED TEST SUITE WITH MULTIPLE TESTS: " + tsuite);
				}
				currentTestSuite.addTest(tsuite.getTestChromosome(0));
				serializedGoals.put(TestFitnessSerializationUtils.makeSerializableForNonEvosuiteClients(g), currentTestSuite.size() - 1);
			});
	
			// Write the test suite to file
			TestSuiteWriter suiteWriter = new TestSuiteWriter();
			currentTestSuite.getTests().forEach(suiteWriter::insertTest);
			String testDir = Properties.TEST_DIR;
			String testName = Properties.TARGET_CLASS.substring(Properties.TARGET_CLASS.lastIndexOf(".") + 1) + "_" + getAge() + "_Test";
			suiteWriter.writeTestSuite(testName, testDir, new ArrayList<>());
			String testFile = testDir + (testDir.endsWith(File.separator) ? "" : File.separator) + testName;
						
			// Notify external tools
			ClientServices.getInstance().getClientNode().notifyRequestForExternalTests(serializedGoals, testFile);
			
			LoggingUtils.getEvoLogger().info("*******************************");
			LoggingUtils.getEvoLogger().info("* Likely stagnation at iteration {}, notifying external providers "
					+ "on current test cases {} and uncovered goals: {}", currentIteration, testFile, goalsManager.getCurrentGoals());
			LoggingUtils.getEvoLogger().info("******************************* ");
			
			return true;
		}
		return false;
	}

	private int resets = 0;
	private void printInfo(TestChromosome c) {
		String fits = "";
		for (FitnessFunction<TestChromosome> g : goalsManager.getCurrentGoals()) {
			if (!(g instanceof PathConditionCoverageGoalFitness)) continue;
			fits += "Pc" + ((PathConditionCoverageGoalFitness) g).getPathConditionId();
			fits += "=" + g.getFitness(c) + ",";
		} 
		fits += " from it " + c.getAge();
		LoggingUtils.getEvoLogger().info("* id = {}, PC fits = {}, fitness = {}", System.identityHashCode(c), fits, c.getFitness());			
		LoggingUtils.getEvoLogger().info("TEST CASE = {}", ((TestChromosome)c).getTestCase().toString());			
	}
	

	private void logFrontierBranches() {
		LoggingUtils.getEvoLogger().info("============= LOG FRONTIER BRANCHES INFO ===============");			

		Map<FitnessFunction<TestChromosome>, TestChromosome> bestIndividuals = new LinkedHashMap<>();

		Set<TestFitnessFunction> frontier = goalsManager.getCurrentGoals();		
		for (TestFitnessFunction goal : frontier) {
			if (!(goal instanceof BranchCoverageTestFitness)) continue;
			BranchCoverageTestFitness branch = (BranchCoverageTestFitness) goal;
			if (branch.getBranch() == null) continue;

			double maxFiteness = Double.MAX_VALUE;
			TestChromosome bestTest = null;
			for (TestChromosome c : rankingFunction.getSubfront(0)) {
				if (goal.getFitness(c) < maxFiteness) {
					maxFiteness = goal.getFitness(c) ;
					bestTest = c;
				}
			}
			bestIndividuals.put(goal, bestTest);
		}
		
		for (Entry<FitnessFunction<TestChromosome>, TestChromosome> entry : bestIndividuals.entrySet()) {
			FitnessFunction<TestChromosome> goal = entry.getKey();
			TestChromosome bestTest = entry.getValue();
			LoggingUtils.getEvoLogger().info("* Test for frontier branch: {}, during search: {} ", goal.toString(), System.identityHashCode(this));
			if (bestTest != null) {
				LoggingUtils.getEvoLogger().info("  Fitness: {} from iter {}", goal.getFitness(bestTest), bestTest.getAge());			
				LoggingUtils.getEvoLogger().info("  TEST CASE = {}", ((TestChromosome)bestTest).getTestCase().toString());
			} else {
				LoggingUtils.getEvoLogger().info("  NO BEST TEST, CHECK THIS");
			}
		}
		
		
		LoggingUtils.getEvoLogger().info("=== NUM. FRONTIER BRANCHES: {}", bestIndividuals.size());			
		LoggingUtils.getEvoLogger().info("* FRONTIER BRANCHES:");			
		for (FitnessFunction<TestChromosome> goal : bestIndividuals.keySet()) {
			BranchCoverageTestFitness branch = (BranchCoverageTestFitness) goal;
			LoggingUtils.getEvoLogger().info("frontier, {}, {}, {}, {}", branch.getBranchGoal().getId(), goal.getFitness(bestIndividuals.get(goal)), System.identityHashCode(this), goal.toString());
		}
		LoggingUtils.getEvoLogger().info("");

		for (TestFitnessFunction goal : goalsManager.getCoveredGoals()) {
			if (!(goal instanceof BranchCoverageTestFitness)) continue;
			BranchCoverageTestFitness branch = (BranchCoverageTestFitness) goal;
			if (branch.getBranch() == null) continue;
			LoggingUtils.getEvoLogger().info("covered, {}, {}, {}, {}", branch.getBranchGoal().getId(), 0.0, System.identityHashCode(this), goal.toString());
		}
		LoggingUtils.getEvoLogger().info("");

		for (TestFitnessFunction goal : goalsManager.getCoveredGoals()) {
			if (!(goal instanceof BranchCoverageTestFitness)) continue;
			BranchCoverageTestFitness branch = (BranchCoverageTestFitness) goal;
			if (branch.getBranch() == null) continue;
			LoggingUtils.getEvoLogger().info("* Test for covered branch: {}, during search: {}", branch.toString(), System.identityHashCode(this));
			LoggingUtils.getEvoLogger().info("  TEST CASE = {}", Archive.getArchiveInstance().getSolution(branch).getTestCase().toString());
		}
		LoggingUtils.getEvoLogger().info("");
		
	}

	
	private boolean handleReset(boolean changed) { /*SUSHI: Reset*/
		if (changed) unchangedPopulationIterations = 0;
		else unchangedPopulationIterations++;

		if (goalsManager.getCurrentGoals().isEmpty()) {
			return false; // search finished
		}

		if (unchangedPopulationIterations >  Properties.NO_CHANGE_ITERATIONS_BEFORE_RESET) {
			long time = System.currentTimeMillis();
			
			List<TestChromosome> newPopulation = new ArrayList<>();
			if (goalsManager.getCurrentGoals().size() == 1) {
				newPopulation.addAll(elitism());

			} else {
				newPopulation.addAll(rankingFunction.getSubfront(0));
			}
			
			//initializePopulation(); NB: prefer explicit setup to avoid reset of all search params
			population.clear();
			generateInitialPopulation(Properties.POPULATION - newPopulation.size());
		
			for (TestChromosome c : population) {
				c.updateAge(currentIteration);
				c.setChanged(true);
			}
			calculateFitness();
		
			population.addAll(0, newPopulation);
			
		
			// Calculate dominance ranks
			rankingFunction.computeRankingAssignment(population, goalsManager.getCurrentGoals());

			time = System.currentTimeMillis() - time;
			resets++;
			unchangedPopulationIterations = 0;
			
			LoggingUtils.getEvoLogger().info("*******************************");
			LoggingUtils.getEvoLogger().info("* Population reset at iteration " + currentIteration + " (took " + time + " msec)" + "elite size is {}", newPopulation.size());
			LoggingUtils.getEvoLogger().info("******************************* ");
			
			return true;
		}
		return false;
	}


	private void addToPopulation(List<TestChromosome> front) {
		for (TestChromosome individual : front) {
			addToPopulation(individual); 
		}
	}

	private void addToPopulation(TestChromosome t) {
		population.add(t);
		notifyInNextGeneration(t);
	}
	
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void generateSolution() {
		logger.debug("executing generateSolution function");

		// Set up the targets to cover, which are initially free of any control dependencies.
		// We are trying to optimize for multiple targets at the same time.
		if (ArrayUtil.contains(Properties.CRITERION, Criterion.PATHCONDITION)){
			goalsManager = new PathConditionManager(fitnessFunctions, this, Properties.CRITERION.length == 1, false /*TODO: set by an option*/);
		} else if (ArrayUtil.contains(Properties.CRITERION, Criterion.BRANCH_WITH_AIDING_PATH_CONDITIONS)) {
			Properties.PATH_CONDITION_TARGET = PathConditionTarget.LAST_ONLY; //This option is mandatory with the selected criterion
			goalsManager = new AidingPathConditionManager(fitnessFunctions, this);			
		} else if (ArrayUtil.contains(Properties.CRITERION, Criterion.SEEPEP)){ /*SEEPEP: DAG coverage*/
			ExecutionTracer.enableSeepepTracing();
			goalsManager = new SeepepManager(fitnessFunctions);
		} else {
			this.goalsManager = new MultiCriteriaManager(this.fitnessFunctions);
		}
		
		LoggingUtils.getEvoLogger().info("* Initial Number of Goals in DynaMOSA = " +
				this.goalsManager.getCurrentGoals().size() +" / "+ this.getUncoveredGoals().size());

		logger.debug("Initial Number of Goals = " + this.goalsManager.getCurrentGoals().size());

		if (this.population.isEmpty()) {
			// Initialize the population by creating solutions at random.
			this.initializePopulation();
		}

		// Compute the fitness for each population member, update the coverage information and the
		// set of goals to cover. Finally, update the archive.
		// this.calculateFitness(); // Not required, already done by this.initializePopulation();

		// Calculate dominance ranks and crowding distance. This is required to decide which
		// individuals should be used for mutation and crossover in the first iteration of the main
		// search loop.
		this.rankingFunction.computeRankingAssignment(this.population, this.goalsManager.getCurrentGoals());
		for (int i = 0; i < this.rankingFunction.getNumberOfSubfronts(); i++){
			this.distance.fastEpsilonDominanceAssignment(this.rankingFunction.getSubfront(i), this.goalsManager.getCurrentGoals());
		}

		// Evolve the population generation by generation until all gaols have been covered or the
		// search budget has been consumed.
		while (!isFinished() && goalsManager.getUncoveredGoals().size() > 0) {
			evolve();
			notifyIteration();
		}
		completeCalculateFitness();
		//logFrontierBranches();
		this.notifySearchFinished();
	}

	protected void completeCalculateFitness() {
		if (goalsManager instanceof PathConditionManager) {
			((PathConditionManager) goalsManager).restoreInstrumentationForAllGoals(); //Needed for path conditions, since covered ones were removed
		}
		logger.debug("Calculating fitness for " + population.size() + " individuals");
		for (TestFitnessFunction goal: goalsManager.getCoveredGoals()){
			TestChromosome c = Archive.getArchiveInstance().getSolution(goal);
			completeCalculateFitness(c);
		}
	}

	/**
	 * Calculates the fitness for the given individual. Also updates the list of targets to cover,
	 * as well as the population of best solutions in the archive.
	 *
	 * @param c the chromosome whose fitness to compute
	 */
	@Override
	protected void calculateFitness(TestChromosome c) {
		if (!isFinished()) {
			// this also updates the archive and the targets
			this.goalsManager.calculateFitness(c, this);
			this.notifyEvaluation(c);
		}
	}

	/** 
	 * This method computes the fitness scores for all (covered and uncovered) goals
	 * @param c chromosome
	 */
	protected void completeCalculateFitness(TestChromosome c) {
		for (TestFitnessFunction fitnessFunction : this.goalsManager.getCoveredGoals()) {
			if (!c.getFitnessValues().containsKey(fitnessFunction))
				c.getFitness(fitnessFunction);
			//notifyEvaluation(c);
		}
		for (TestFitnessFunction fitnessFunction : this.goalsManager.getCurrentGoals()) {
			if (!c.getFitnessValues().containsKey(fitnessFunction))
				c.getFitness(fitnessFunction);
			//notifyEvaluation(c);
		}
	}

	@Override
	public List<? extends FitnessFunction<TestChromosome>> getFitnessFunctions() {
		if (goalsManager == null) {
			return super.getFitnessFunctions();
		}
		List<TestFitnessFunction> testFitnessFunctions = new ArrayList<>(goalsManager.getCoveredGoals());
		testFitnessFunctions.addAll(goalsManager.getUncoveredGoals());
		return testFitnessFunctions;
	}

	@Override /*SUSHI: Prevent multiple copies of individuals*/
	protected boolean keepOffspring(TestChromosome offspring, TestChromosome parent1, TestChromosome parent2) {
		for (FitnessFunction<TestChromosome> g : goalsManager.getCurrentGoals()){
			double newFitness = g.getFitness(offspring);
			double p1Fitness = g.getFitness(parent1);
			double p2Fitness = g.getFitness(parent2);
			if (newFitness < p1Fitness && newFitness < p2Fitness) { 
				return true;
			}
		}
		return false;
	}

	@Override /*SUSHI: Prevent multiple copies of individuals*/
	protected boolean checkForRemove(TestChromosome parent, TestChromosome offspring1, TestChromosome offspring2) {
		for (FitnessFunction<TestChromosome> g : goalsManager.getCurrentGoals()){
			double newFitness1 = offspring1 != null ? g.getFitness(offspring1) : Double.MAX_VALUE;
			double newFitness2 = offspring2 != null ? g.getFitness(offspring2) : Double.MAX_VALUE;
			double pFitness = g.getFitness(parent);
			if (pFitness < newFitness1 && pFitness < newFitness2) { 
				return false; //do not remove
			}
		}
		return true;//population.remove(parent);
	}

}
