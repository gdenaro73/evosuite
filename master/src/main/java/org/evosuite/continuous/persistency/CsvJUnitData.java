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
package org.evosuite.continuous.persistency;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.statistics.RuntimeVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.opencsv.CSVReader;


/**
 * This class is used to read the CSV files generated by EvoSuite.
 * 
 * <p>
 * Note: as this class is used only for CTG, it assumes the
 * CSV contains only one single data row.
 * 
 * @author arcuri
 *
 */
public class CsvJUnitData {

	private static final Logger logger = LoggerFactory.getLogger(CsvJUnitData.class);

	private String targetClass;	
	private Map<String, Double> coverageValues = new LinkedHashMap<>();
	private Map<String, String> coverageBitString = new LinkedHashMap<>();
	private int totalNumberOfStatements;	
	private int numberOfTests;	
	private int totalNumberOfFailures;
	private int durationInSeconds;
	private int configurationId;
	
	/**
	 * Apart from testing, shouldn't be allowed to instantiate
	 * this class directly
	 */
	protected CsvJUnitData(){		
	}

	/**
	 * Open and extract all data from the given csv file.
	 * 
	 * @param file the csv file, having 1 line of header, and 1 line of data
	 * @return <code>null</code> in case of any problem in reading the file
	 */
	public static CsvJUnitData openFile(File file){
		if(!file.getName().endsWith("csv")){
			logger.error("Not a csv file: "+file.getAbsolutePath());
			return null;
		}

		List<String[]> rows = null;
		try {
			CSVReader reader = new CSVReader(new FileReader(file));
			rows = reader.readAll();
			reader.close();
		} catch (Exception e) {
			logger.error("Exception while parsing CSV file "+file.getAbsolutePath()+" , "+e.getMessage(),e);
			return null;
		}

		if(rows.size() != 2){
			logger.error("Cannot parse "+file.getAbsolutePath()+" as it has "+rows.size()+" rows");
			return null;
		}

		CsvJUnitData data = new CsvJUnitData();
		try{
			data.targetClass = getValue(rows, "TARGET_CLASS").trim();		
			data.configurationId = 0; //TODO. note: it has nothing to do with configuration_id, need refactoring			

			// get coverage (value and bitstring)
			for (String columnName : rows.get(0)) {
				// this is assuming that all coverage/score runtime variables
				// end with "Coverage" or "Score", e.g., BranchCoverage, WeakMutationScore, etc
				if (!columnName.equals(RuntimeVariable.Coverage.name()) &&
						(columnName.endsWith("Coverage") || columnName.endsWith("Score")) ) {
					data.coverageValues.put(columnName, Double.parseDouble(getValue(rows, columnName)));
				}

				// this is assuming that all coverage/score runtime variables
				// end with "CoverageBitString", e.g., BranchCoverageBitString, WeakMutationCoverageBitString, etc
				if (!columnName.equals(RuntimeVariable.CoverageBitString.name()) &&
						columnName.endsWith("CoverageBitString") ) {
					String coverageColumn = columnName.replace("BitString", "");
					if (coverageColumn.contains("Mutation")) {
						coverageColumn = coverageColumn.replace("Coverage", "Score");
					}
					data.coverageBitString.put(coverageColumn, getValue(rows, columnName));
				}
			}

			data.totalNumberOfStatements = Integer.parseInt(getValue(rows,RuntimeVariable.Length.toString()));
			data.durationInSeconds = Integer.parseInt(getValue(rows,RuntimeVariable.Total_Time.toString())) / 1000;
			data.numberOfTests = Integer.parseInt(getValue(rows,RuntimeVariable.Size.toString()));

			data.totalNumberOfFailures = 0; //TODO
		} catch(Exception e){
			logger.error("Error while parsing CSV file: "+e,e);
			return null; 
		}
		
		return data; 
	}

	public static String getValue(List<String[]> rows, String columnName){
		String[] names = rows.get(0);
		String[] values = rows.get(1);

		for(int i=0; i<names.length; i++){
			if(names[i].trim().equalsIgnoreCase(columnName.trim())){
				return values[i].trim();
			}
		}
		return null;
	}

	public static List<String> getValues(List<String[]> rows, String columnName) {
		String[] names = rows.get(0);
		List<String> values = new ArrayList<>();

		for (int i = 0; i < names.length; i++) {
			if (names[i].trim().equalsIgnoreCase(columnName.trim())) {
				for (int j = 1; j < rows.size(); j++) {
					values.add(rows.get(j)[i].trim());
				}
			}
		}

		return values;
	}

	public String getTargetClass(){
		return targetClass; 
	}

	public Set<String> getCoverageVariables() {
		return this.coverageValues.keySet(); 
	}

	public double getCoverage(String coverageVariable) {
		return this.coverageValues.get(coverageVariable);
	}

	public boolean hasCoverage(String coverageVariable) {
		return this.coverageValues.containsKey(coverageVariable);
	}

	public int getNumberOfCoverageValues() {
		return this.coverageValues.size();
	}

	public Set<String> getCoverageBitStringVariables() {
		return this.coverageBitString.keySet();
	}

	public String getCoverageBitString(String coverageVariable) {
		return this.coverageBitString.get(coverageVariable);
	}

	public int getTotalNumberOfStatements(){
		return totalNumberOfStatements; 
	}

	public int getNumberOfTests(){
		return numberOfTests; 
	}

	public int getTotalNumberOfFailures(){
		return totalNumberOfFailures; 
	}

	public int getDurationInSeconds(){
		return durationInSeconds; 
	}

	public int getConfigurationId() {
		return configurationId;
	}
}
