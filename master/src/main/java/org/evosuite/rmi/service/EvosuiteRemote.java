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

/**
 * EvoSuite Master Node view in an external applications that is using EvoSuite.  
 *
 * @author giovanni
 */
public interface EvosuiteRemote extends Remote {
    boolean evosuite_injectFitnessFunction(String classCanonicalName, String methodNameAndDescriptor, String evaluatorName) throws RemoteException;

    boolean evosuite_injectTestCase(String testClassPath) throws RemoteException;
}
