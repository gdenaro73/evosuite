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
package org.evosuite.rmi.service;


import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.Map;

import org.evosuite.ga.FitnessFunction;
import org.evosuite.testcase.TestFitnessFunction;
/**
 * EvoSuite Master Node view in an external applications that runs evosuite.  
 *
 * @author giovanni
 */
public interface TestListenerRemote extends Remote {
	
    void evosuiteServerReady(String evosuiteServerRmiIdentifier) throws RemoteException;

    void evosuiteServerShutdown(String evosuiteServerRmiIdentifier) throws RemoteException;

    void generatedTest(String evosuiteServerRmiIdentifier, FitnessFunction<?> goal, String testFileName) throws RemoteException;

    void dismissedFitnessGoal(String evosuiteServerRmiIdentifier, FitnessFunction<?> goal, int iteration, double bestValue, int[] updateIterations) throws RemoteException;

	void requestForExternalTests(String rmiServiceName, Map<TestFitnessFunction, Integer> uncoveredGoalTestNum, String currentEvosuiteTestFileName) throws RemoteException;;    
}
