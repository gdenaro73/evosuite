/**
 * Copyright (C) 2011,2012 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 * 
 * This file is part of EvoSuite.
 * 
 * EvoSuite is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 * 
 * EvoSuite is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Public License for more details.
 * 
 * You should have received a copy of the GNU Public License along with
 * EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.coverage.pathcondition;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.evosuite.TestGenerationContext;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.testcase.execution.EvosuiteError;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;

/**
 * A single path condition coverage goal
 * 
 * @author G. Denaro
 */
public class PathConditionCoverageGoal implements Serializable {  /*SUSHI: Path condition fitness*/
	
	private static final long serialVersionUID = 7022535456018762227L;

	private final String className;
	private final String methodName;
	private final String evaluatorName;
	private final int pathConditionId;
	private final transient PcEvaluator evaluator;

	public static final boolean COLORED_FITNESS_ENABLED = true;
	private transient MabManager mabManager = new MabManager();
	
	/**
	 * @param className
	 *            a {@link java.lang.String} object.
	 * @param methodName
	 *            a {@link java.lang.String} object.
	 */
	public PathConditionCoverageGoal(int pathConditionId, String className, 
	        String methodName, String evaluatorName) {
		if (className == null || methodName == null)
			throw new IllegalArgumentException("null given");

		this.pathConditionId = pathConditionId;
		this.className = className;
		this.methodName = methodName;
		this.evaluatorName = evaluatorName;
		this.evaluator = new PcEvaluator(); 
	}

	private class PcEvaluator {
		/* Postpone actual construction at first actual access, to avoid early creation,
		 * when the ClassLoaderForSUT is not yet ready
		 */
		private Object evaluatorObject = null; 
		private Function<Object[], PcFitnessValue> evaluatorExecutor = null;	
		
		Object getEvaluatorAsRawObject() {
			checkExistenceOrInstantiateEvaluator();
			return evaluatorObject;
		}
		
		PcFitnessValue execute(Object[] args) {
			checkExistenceOrInstantiateEvaluator();
			return evaluatorExecutor.apply(args);
		}
		
		void checkExistenceOrInstantiateEvaluator() {
			if (evaluatorObject != null) {
				return;
			}
			try {
				InstrumentingClassLoader cl = TestGenerationContext.getInstance().getClassLoaderForSUT();
				Class<?> clazz = Class.forName(evaluatorName, true, cl);
				try {
					Constructor<?> cnstr = clazz.getConstructor(ClassLoader.class);
					evaluatorObject = cnstr.newInstance(cl);
				} catch (NoSuchMethodException e) {
					evaluatorObject = clazz.newInstance();							
				}

				if (evaluatorObject instanceof Function) { 
					Method applyMethod = clazz.getMethod("apply", Object[].class);
					Class<?> returnType = applyMethod.getReturnType();
					if (returnType.equals(Double.class)) { //The evaluator executes a standard fitness function that returns double
						evaluatorExecutor = new Function<Object[], PcFitnessValue> () {
							@Override
							public PcFitnessValue apply(Object[] paramValues) {
								@SuppressWarnings("unchecked")
								Double d = ((Function<Object[], Double>) evaluatorObject).apply(paramValues);
								
								return new PcFitnessValue(d);
							}
						};
					} else if (returnType.equals(Map.Entry.class)) {
						evaluatorExecutor = new Function<Object[], PcFitnessValue> () { //The evaluator returns a "colored" fitness
							@Override
							public PcFitnessValue apply(Object[] paramValues) {
								@SuppressWarnings("unchecked")
								Map.Entry<Object, Double> fitnessData = ((Function<Object[], Map.Entry<Object, Double>>) evaluatorObject).apply(paramValues);
								
								PcFitnessValue packedFitnessData = new PcFitnessValue(fitnessData.getKey(), fitnessData.getValue());
								return packedFitnessData;
							}
						};
					} else {
						throw new EvosuiteError("Cannot instantiate path condition evaluator: " + evaluatorName +
							" because the fitness function return the invalid type: " + returnType.getTypeName());
					}
				} else { //Legacy evaluator: the fitness function is method "test0" that returns Double
					evaluatorExecutor = new Function<Object[], PcFitnessValue> () {
						@Override
						public PcFitnessValue apply(Object[] paramValues) {
							//backward compatibility with legacy Evaluator implementations 
							Double d = PathConditionCoverageGoal.this.executeEvaluatorLegacy("test0", paramValues);
							
							return new PcFitnessValue(d);
						}
					};
				}			
			} catch (SecurityException | ClassNotFoundException | InstantiationException | IllegalAccessException | 
					IllegalArgumentException | InvocationTargetException | NoSuchMethodException e) {
				throw new EvosuiteError("Cannot instantiate path condition evaluator: " + evaluatorName +
						" because of: " + e + " ::: " + Arrays.toString(e.getStackTrace()));
			} 
		}
	}
	
	public class PcFitnessValue {
		private final double value;
		private final Object color;
		
		PcFitnessValue(Double fitnessValue) {
			// standard path condition fitness
			if (fitnessValue == null) {
				throw new EvosuiteError("Null fitness value while executing path condition evaluator: " + evaluatorName);
			}
			value = (double) fitnessValue;
			color = null;			
			//LoggingUtils.getEvoLogger().info("**computed d: " + value + ", pc = " + evaluator.toString());
		}

		PcFitnessValue(Object fitnessColor, Double fitnessValue) {
			// colored fitness
			if (fitnessValue == null) {
				throw new EvosuiteError("Null fitness value while executing path condition evaluator: " + evaluatorName);
			}
			if (fitnessColor == null) {
				throw new EvosuiteError("Null fitness color while executing path condition evaluator: " + evaluatorName);
			}
			color = fitnessColor;
			value = fitnessValue;
			//LoggingUtils.getEvoLogger().info("**computed d: " + value + ", pc = " + evaluator.toString());
		}
		
		public double value() {
			return value;
		}
		
		public Object color() {
			return color;
		}

		public boolean isFitnessWithColor() {
			return color != null;
		}
	}
	
	public PcFitnessValue wrapDistanceValueAsPcFitnessValue(double d) {
		return new PcFitnessValue(d);
	}

	public PcFitnessValue executeEvaluator(Object[] args) {
		return evaluator.execute(args);
	}
	
	@Deprecated
	public Double executeEvaluatorLegacy(String fitnessFunctionMethodName, Object[] paramValues) {
		//execute the evaluator
		Method evaluatorMethod = null;
		try {
			Method[] methods = evaluator.getEvaluatorAsRawObject().getClass().getDeclaredMethods();
			for (Method m : methods) {
				if (m.getName().startsWith(fitnessFunctionMethodName)) {
					evaluatorMethod = m;
					break;
				}
			}
		} catch (Throwable e) { 
			throw new EvosuiteError("Cannot execute path condition evaluator: " + evaluatorName +
					"\n\t path condition for method " + className + "." + methodName +
					"\n\t due to: " + e);
		}
		if (evaluatorMethod == null) {
			throw new EvosuiteError("Cannot execute path condition evaluator: " + evaluatorName +
					"\n\t path condition for method " + className + "." + methodName +
					"\n\t because there is no 'test0' method in the evaluator");
		}
		
		Double d = Double.MAX_VALUE;
		try {
			d = (Double) evaluatorMethod.invoke(evaluator.getEvaluatorAsRawObject(), paramValues);
			//LoggingUtils.getEvoLogger().info("**computed d: " + d + ", " + evaluatorMethod + ", pc = " + evaluator.toString());
		} catch (IllegalAccessException | IllegalArgumentException e) {
			//throw new EvosuiteError
			LoggingUtils.getEvoLogger().info("Cannot execute path condition evaluator: " + evaluatorName
			+ "\n\t path condition for method " + className + "." + methodName
			+ "\n\t called on objects " + Arrays.toString(paramValues)
			+ "\n\t failed because of: " + e);
		} catch (InvocationTargetException e) {
			StackTraceElement[] st = e.getCause().getStackTrace();
			LoggingUtils.getEvoLogger().info("Exception thrown within path condition evaluator: " + evaluatorName
			+ "\n\t path condition for method " + className + "." + methodName
			+ "\n\t called on objects " + Arrays.toString(paramValues)
			+ "\n\t failed because of: " + e.getCause()
			+ "\n\t stack trace is " + st.length + " items long: " + 
			(st.length <= 50 ? Arrays.toString(st) :
				Arrays.toString(Arrays.copyOfRange(st, 0, 25)) + 
				" ...... " +  Arrays.toString(Arrays.copyOfRange(st, st.length - 25, st.length))));
		} catch (Throwable e) {
			StackTraceElement[] st = e.getCause().getStackTrace();
			throw new EvosuiteError("Unexpected failure when executing path condition evaluator: " + evaluatorName
			+ "\n\t path condition for method " + className + "." + methodName
			+ "\n\t called on objects " + Arrays.toString(paramValues)
			+ "\n\t failed because of: " + e.getCause()
			+ "\n\t stack trace is " + st.length + " items long: " + 
			(st.length <= 50 ? Arrays.toString(st) :
				Arrays.toString(Arrays.copyOfRange(st, 0, 25)) + 
				" ...... " +  Arrays.toString(Arrays.copyOfRange(st, st.length - 25, st.length))));
		}
		return d;
	}


	/**
	 * @return the className that this path condition refers to
	 */
	public String getClassName() {
		return className;
	}

	/**
	 * @return the methodName that this path condition refers to
	 */
	public String getMethodName() {
		return methodName;
	}

	/**
	 * @return the path condition id
	 */
	public int getPathConditionId() {
		return pathConditionId;
	}

	/**
	 * @return the name of the evaluator of this path condition
	 */
	public String getEvaluatorName() {
		return evaluatorName;
	}
	
	/**
	 * @return the evaluator of this path condition
	 */
	public Object getEvaluator() {
		return evaluator.getEvaluatorAsRawObject();
	}
	
	/**
	 * <p>
	 * getDistance
	 * </p>
	 * 
	 * @param result
	 *            a {@link org.evosuite.testcase.ExecutionResult} object.
	 * @return a {@link org.evosuite.coverage.ControlFlowDistance} object.
	 */
	public double getDistance(ExecutionResult result) {

		PcFitnessValue fitnessData = result.getTrace().getPathConditionFitnessData().get(this);

		if (fitnessData == null) {
			return Double.MAX_VALUE; /*TODO: which is maximum distance for non-evaluated path conditions?*/
		} 
		
		double distance = fitnessData.value();
		
		if (distance == 0) {
			return 0;
		} 
		
		if (COLORED_FITNESS_ENABLED && fitnessData.isFitnessWithColor()) {
			mabManager.notifyNewFitnessObservation(fitnessData.color(), distance);
			double d = mabManager.normalizeDistanceByColorPriorityEgreedy(fitnessData.color(), distance);
			//LoggingUtils.getEvoLogger().info("**computed colored d: " + d + ", color = " + fitnessData.color() + ", pc = " + evaluator.toString());
			return d;
		} else {
			if (mabManager.usingColors()) {
				/* Sanity check: This path condition should yield color evaluations consistently, 
				 * either never or at all runs */  
				throw new EvosuiteError("Missing color data while evaluating path condition: " + evaluatorName);				
			}
			return distance;
		}
		
	}
	
	private class MabManager {
		// Hyper-parameters
		public static final double epsilon = Double.MIN_VALUE; //MBA: a small value to avoid divisions by zero 
		public static final double gamma = 0.90; //MBA: discount on past memory for arms that received priority 
		public static final double gammaUnprioritized = 0.98; //MBA: (smaller) discount on past memory for all other arms
		public static final int windowSize = 10; //MBA: window between updates on arms' priorities
		public static final double eGreedy_epsilon = 0.1; //MBA: window between updates on arms' priorities
		public static final double initalBonus = 0.1; //Inital reward bonus for newly born individuals

		HashMap<Object, StatsForAColor> seenColors; // color -> stats
		Object thePrioritizedColor;
		int window = windowSize;
				
		boolean usingColors() {
			return seenColors != null;
		}
		
		class StatsForAColor {
			final Object color;
			boolean isNew = true;
			double meanReward = 0; 
			double bestFitnessPrev = Double.MAX_VALUE;
			double bestFitnessCurr = Double.MAX_VALUE;
			
			public StatsForAColor(Object color) {
				this.color = color;
			}

			void notifyNewFitnessObservation(double distance) {
				if (distance < bestFitnessCurr) { 
					bestFitnessCurr = distance;
				}			
			}

			void mbaUpdatePriorityEgreedy() {
				double reward = 0.0;
				if (isNew) {
					isNew = false;
					meanReward = initalBonus; 
				} else {
					if (bestFitnessCurr < bestFitnessPrev) {
						reward = Math.log((1 - bestFitnessCurr + MabManager.epsilon) / (1 - bestFitnessPrev + MabManager.epsilon));
					}
					double discount = (color == thePrioritizedColor) ?  gamma : gammaUnprioritized;
					meanReward = discount * meanReward + reward; 
				}
				bestFitnessPrev = Math.min(bestFitnessPrev, bestFitnessCurr);
				bestFitnessCurr = Double.MAX_VALUE; //initialized to worst for monitoring increments during next iteration
			}
		}
		
		void notifyNewFitnessObservation(Object color, double distance) {
			if (seenColors == null) {
				seenColors = new HashMap<>();
			}
			if (!seenColors.containsKey(color)) {
				seenColors.put(color, new StatsForAColor(color));
				//LoggingUtils.getEvoLogger().info("***** Seen color first time: " + color + ", pc = " + evaluator.toString());
			}
			StatsForAColor stats = seenColors.get(color);
			stats.notifyNewFitnessObservation(distance);
		}
		
		double normalizeDistanceByColorPriorityEgreedy(Object color, double distance) {
			if (!color.equals(thePrioritizedColor)) {
				//fitness rescaled in (0.2, 1)
				return 0.2 + 0.8 * distance; 
			} else {
				/* The current best individual of priorityColor is necessarily preferred to all others,
				 * its fitness will be exactly 0.1.
				 * Individuals of priorityColor improving on current best are assigned even less than 0.1.
				 * Other individuals of priorityColor are just rescaled, accordingly.
				 */
				double bestFitnessPrev = seenColors.get(color).bestFitnessPrev;
				if (distance >= bestFitnessPrev) {
					//fitness rescaled as (best, 1) -> (0.1, 1) 
					double normDistance = 0.1 + 0.9 * (distance - bestFitnessPrev) / Math.abs(1 - bestFitnessPrev); //for safety, abs() makes it tolerant also to fitness values larger than 1
					if (normDistance <= 0) {
						throw new EvosuiteError("cannot be 0 a: " + distance + ", " + bestFitnessPrev + ", " + normDistance);
					}
					return normDistance; 
				} else {
					//fitness rescaled in (0, 0.1) 
					double normDistance = 0.1 * distance / bestFitnessPrev;
					if (normDistance <= 0) {
						throw new EvosuiteError("cannot be 0 a: " + distance + ", " + bestFitnessPrev + ", " + normDistance);
					}
					return normDistance;
				}
			}
		}

		void updatePriorities() {
			if (window > 0) { // wait on window
				window--;
				return;
			} else {
				window = windowSize;
			}
			if (seenColors != null) {
				// before updating, filter likely alive colors (the ones that notified at least a fitness observation)
				List<StatsForAColor> aliveColors = seenColors.values().stream().
						filter(stats -> stats.bestFitnessCurr < Double.MAX_VALUE).
						collect(Collectors.toList());
				
				//update statistics for all colors
				seenColors.values().stream().forEach(stats -> stats.mbaUpdatePriorityEgreedy());

				//choose a color to prioritize in the next round
				double topMeanReward = aliveColors.stream().map(stats -> stats.meanReward).max(Double::compareTo).orElse(0d);		
				thePrioritizedColor = aliveColors.stream().filter(stats -> stats.meanReward == topMeanReward).findAny().get().color;
				double p = Randomness.nextDouble(0.0, 1.0);
				if (p < eGreedy_epsilon) { //Exploration: choose a different color randomly
					List<StatsForAColor> theOtherColors = new ArrayList<StatsForAColor>(aliveColors);
					theOtherColors.remove(thePrioritizedColor);
					thePrioritizedColor = Randomness.choice(theOtherColors);
					LoggingUtils.getEvoLogger().info("----- EXPLORATION: ");
				} else { //Exploitation
					LoggingUtils.getEvoLogger().info("----- EXPLOITATION: ");
				}
				//TODO: reorder the population? Currently, we wait for the effect of new prioritized arm to manifest with delay of 1 iteration
				/*LoggingUtils.getEvoLogger().info("----- colors: " +
						seenColors.keySet().stream().map(c -> {
							String s = c + "[";
							StatsForAColor stats = seenColors.get(c);
							s += "f=" + stats.bestFitnessPrev + ", ";
							s += "r=" + stats.meanReward + "]\n";
							return s;
						}).collect(Collectors.joining()) + "  -> " + thePrioritizedColor);*/
				LoggingUtils.getEvoLogger().info("----- colors: " +
						aliveColors.stream().map(stats -> {
							String s = stats.color + "[";
							s += "f=" + stats.bestFitnessPrev + ", ";
							s += "r=" + stats.meanReward + "]\n";
							return s;
						}).collect(Collectors.joining()) + "  -> " + thePrioritizedColor);
			}
		}
	}

	/**
	 * Called by PathConditionManager at the end of each iteration for each uncovered goal
	 */
	public void iteration() {
		mabManager.updatePriorities();
	}

	// inherited from Object

	/**
	 * {@inheritDoc}
	 * 
	 * Readable representation
	 */
	@Override
	public String toString() {
		String name = className + "." + methodName + 
				(evaluator.evaluatorObject == null ? "" : "::" + evaluator.evaluatorObject) +
				" -- path condition " + evaluatorName + " (id = " + pathConditionId + ")";
		return name;
	}

	/** {@inheritDoc} */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + pathConditionId;
		//result = prime * result + className.hashCode();
		//result = prime * result + methodName.hashCode();
		//result = prime * result + evaluatorName.hashCode();
		return result;
	}

	/** {@inheritDoc} */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;

		PathConditionCoverageGoal other = (PathConditionCoverageGoal) obj;
		return this.pathConditionId == other.pathConditionId;
	}

}
