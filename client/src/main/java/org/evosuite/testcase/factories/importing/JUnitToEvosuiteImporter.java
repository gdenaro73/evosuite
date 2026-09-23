package org.evosuite.testcase.factories.importing;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.UnparsableStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.types.ResolvedArrayType;
import com.github.javaparser.resolution.types.ResolvedPrimitiveType;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.runtime.testdata.EvoSuiteFile;
import org.evosuite.symbolic.TestCaseBuilder;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.PrimitiveExpression.Operator;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.LoggingUtils;

public class JUnitToEvosuiteImporter {
	private final ClassLoader classLoaderForSUT;
	private TestCaseBuilder testBuilder = null;
	private List<TestCase> testCases = new ArrayList<>();

	public JUnitToEvosuiteImporter(ClassLoader classLoaderForSUT) { 
		this.classLoaderForSUT = classLoaderForSUT;
		// Parser configuration
		ParserConfiguration parserConfiguration = new ParserConfiguration();
		CombinedTypeSolver typeSolver = new CombinedTypeSolver();
		typeSolver.add(new ClassLoaderTypeSolver(classLoaderForSUT));
		parserConfiguration.setSymbolResolver(new JavaSymbolSolver(typeSolver));
		StaticJavaParser.setConfiguration(parserConfiguration);		
	}

	public void importTestCases(String testClassPath) throws IOException {
		try {
			CompilationUnit cu = StaticJavaParser.parse(Files.newInputStream(Paths.get(testClassPath)));
			new JUnitTestVisitor().visit(cu, new JUnitTestVisitorContext());
		} catch (Throwable e) {
			LoggingUtils.getEvoLogger().info("\n\n* Issue while importing test case: " + e + " ::: " + Arrays.toString(e.getStackTrace()));
			throw e;
		}
	}

	public List<TestCase> getTestCases() {
		return new ArrayList<>(testCases);
	}

	public void clearTestCases() {
		testCases.clear();
	}

	public class TestImportException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private List<String> alreadyParsed = null;
		private String partiallyParsed = null;
		private String unparsable = null;
		
		public TestImportException(Node currentlyParsedNode, String message) {
			super(message);
			setLocalData(currentlyParsedNode);
		}
		public TestImportException(Node currentlyParsedNode, String message, Throwable cause) {
			super(message, cause);
			setLocalData(currentlyParsedNode);
		}
		private void setLocalData(Node currentlyParsedNode) {
			unparsable = currentlyParsedNode != null ? currentlyParsedNode.toString() : "";
			List<TestCase> tests = getTestCases();
			alreadyParsed = new ArrayList<String>();
			for (TestCase t: tests) {
				alreadyParsed.add(t.toCode());
			}
			if (testBuilder != null && !testBuilder.getDefaultTestCase().isEmpty()) {
				partiallyParsed = testBuilder.getDefaultTestCase().toCode();
			} else {
				partiallyParsed = "";
			}
		}
		public String getUnparsable() {
			return unparsable;
		}
		public String getPartiallyParsed() {
			return partiallyParsed;
		}
		public String[] getAlreadyParsed() {
			return alreadyParsed.toArray(new String[0]);
		}
		public String getParsingIssueReport() {
			String report = "Parsing issue report:\n";
			for (String t: alreadyParsed) {
				report += "Successfully parsed test case\n---------\n" + t + "---------\n";
			}
			report += "Now parsing\n---------\n";
			report += partiallyParsed + "\n";
			report += "Issue with statement: " + unparsable + "\n" ;
			Set<Throwable> done = new HashSet<>();
			report += getMessage();
			done.add(this);
			Throwable exc = getCause();
			while (exc != null && !done.contains(exc)) {
				done.add(exc);
				report += ", due to " + exc.getClass().getSimpleName() + " " + exc.getMessage();
				exc = exc.getCause();
			}
			report += "\n";
			return report;
		}
	}

	public static void main(String[] args) {
		// Extract classpath entries from args[0] and test-case paths from the subsequent args[i>0]
		String classpathEntries = args[0]; // path-separator separated list
		String[] entries = classpathEntries.split(File.pathSeparator);
		URL[] urls = new URL[entries.length];
		for (int i = 0; i < entries.length; ++i) { 
			String entry = entries[i];
			if (!entry.endsWith("jar") && entry.endsWith(File.separator)) {
				entry += File.separator; //URL folders must be terminated with file separator
			}
			try {
				urls[i] = new URL("file:" + entry);
			} catch (MalformedURLException e) {
				throw new RuntimeException(e);
			}
		}
		
		JUnitToEvosuiteImporter importer = new JUnitToEvosuiteImporter(new URLClassLoader(urls, ClassLoader.getSystemClassLoader()));

		for (String pathToTestClass: args) {
			try {
				importer.importTestCases(pathToTestClass);
				List<TestCase> testCases = importer.getTestCases();
				for (TestCase t: testCases) {
					System.out.println(t.toCode());
				}
				importer.clearTestCases();
			} catch (TestImportException e) {
				System.err.println("Importing issue with " + pathToTestClass + " : " + e.getMessage() + "\n" + e.getParsingIssueReport());
				e.printStackTrace();
			} catch (IOException e) {
				System.err.println("I/O issue with " + pathToTestClass);
			} 
		}
	}

	private class JUnitTestVisitor extends VoidVisitorAdapter<JUnitTestVisitorContext> {

		/**
		 * Visitor methods
		 * 
		 * Main methods to import a method-declaration that defines a test case, 
		 * a variable declaration within a test case, an assignment statement
		 * within a test case, a method call expression within a test case
		 * 
		 * */

		@Override
		public void visit(MethodDeclaration md, JUnitTestVisitorContext context) {
			if (md.isAnnotationPresent("Test")) {
				testBuilder = new TestCaseBuilder();
				context.enable();
				context.setTracker(new HashMap<>());
				super.visit(md, context);
				context.disable();
				testCases.add(testBuilder.getDefaultTestCase());
			} 
		}

		@Override
		public void visit(VariableDeclarator vd, JUnitTestVisitorContext context) {
			if (!context.isEnabled()) {
				return;
			}
			VariableReference vr;
			if (!vd.getInitializer().isPresent()) {
				String type = vd.getType().toString();
				if (type.equals("int")) {
					vr = testBuilder.appendIntPrimitive(0);
				} else if (type.equals("boolean")) {
					vr = testBuilder.appendBooleanPrimitive(false);
				} else if (type.equals("char")) {
					vr = testBuilder.appendCharPrimitive('\0');
				} else if (type.equals("String")) {
					vr = testBuilder.appendStringPrimitive(null);
				} else if (type.equals("float")) {
					vr = testBuilder.appendFloatPrimitive(0.0f);
				} else if (type.equals("double")) {
					vr = testBuilder.appendDoublePrimitive(0.0);
				} else if (type.equals("byte")) {
					vr = testBuilder.appendBytePrimitive((byte) 0);
				} else {
					throw new TestImportException(vd, "Unhandled type: " + type);
				}
			} else {
				Expression declaredValue = vd.getInitializer().get();
				boolean visitEnabled = false;
				try {
					context.enableExpressionVisit(declaredValue, vd.getType().resolve());
					visitEnabled = true;
					declaredValue.accept(this, context);
					vr = context.consumeNewlyAddedReference();
				} catch (Exception e) {
					throw new TestImportException(vd, "Issue while parsing declared value", e);
				} finally {
					if (visitEnabled) {
						context.disableExpressionVisit(declaredValue);				
					}
				}
			} 	
			String varName = vd.getNameAsString();
			context.getTracker().put(varName, vr);
		}

		@Override
		public void visit(AssignExpr ae, JUnitTestVisitorContext context) {
			if (!context.isEnabled()) {
				return;
			}
			//1. Data about the target (left value) of the assignment
			Expression target = ae.getTarget();
			VariableReference vrReceiver;
			Field vrReceiverField = null;
			List<Integer> vrReceiverArrayIndices = null;
			if (target.isNameExpr()) {
				vrReceiver = null; // It is simpler to add a new variable in the evosuite test case
			} else if (target.isFieldAccessExpr()) {
				FieldAccessExpr fieldAccessExpr = target.asFieldAccessExpr();
				String receiverName = fieldAccessReceiverName(fieldAccessExpr, context);
				if (receiverName == null) { //static access
					vrReceiver = null;
				} else {
					vrReceiver = context.getTracker().get(receiverName);
				} 
				try {
					vrReceiverField = getField(fieldAccessExpr);
				} catch (Exception e) {
					throw new TestImportException(ae, "Unknown assignment target", e);
				}
			} else if (target.isArrayAccessExpr()) {
				Expression arrayRoot = target;
				vrReceiverArrayIndices = new ArrayList<>();
				while (arrayRoot.isArrayAccessExpr()) { //e.g. array[i][j][k]...
					ArrayAccessExpr arrayAccessExpr = arrayRoot.asArrayAccessExpr();
					vrReceiverArrayIndices.add(0, Integer.parseInt(arrayAccessExpr.getIndex().toString()));
					arrayRoot = arrayAccessExpr.getName(); //outer array root
				}
				vrReceiver = (ArrayReference) //safety check, as this must be casted again later on (step 3) 
						context.getTracker().get(arrayRoot.toString());
			} else {
				throw new TestImportException(ae, "Unknown assignment target");
			}

			//2. Data about the value (right value) of the assignment
			Expression value = ae.getValue();
			VariableReference vrValue;
			if (value.isNameExpr()) {
				vrValue = context.getTracker().get(value.asNameExpr().getNameAsString());
			} else { //we handle all other expressions by defining a new variable in test case 
				boolean visitEnabled = false;
				try {
					context.enableExpressionVisit(target, target.calculateResolvedType());
					visitEnabled = true;
					ae.getValue().accept(this, context);
					vrValue = context.consumeNewlyAddedReference();
				} catch (Exception e) {
					throw new TestImportException(ae, "Issue while parsing assignment value", e);
				} finally {
					if (visitEnabled) {
						context.disableExpressionVisit(target);				
					}
				}
			}

			//3. Update variable-name references, or use the proper Evosuite-builder's appendAssigment method
			if (vrReceiver == null && vrReceiverField == null) {//target is nameExpr
				//after assignment, at future references, this variable name refers to assigned value 
				context.getTracker().put(target.asNameExpr().getNameAsString(), vrValue);
			} else if (vrReceiverField != null) {//target is fieldExpr
				//we append a new statement assigning the field to the value, no variable-name update needed 
				testBuilder.appendAssignment(vrReceiver, vrReceiverField, vrValue);		
			} else { //vrReceiver == null && vrReceiverArrayIndices != null  ////target arrayExpr
				//we append a new statement assigning the array item to the value, no variable-name update needed 
				if (vrReceiverArrayIndices.size() > 1) {
					testBuilder.appendAssignment((ArrayReference) vrReceiver, vrReceiverArrayIndices, vrValue);
				} else {
					testBuilder.appendAssignment((ArrayReference) vrReceiver, vrReceiverArrayIndices.get(0), vrValue);
				}
			}	
		}

		@Override
		public void visit(MethodCallExpr mce, JUnitTestVisitorContext context) {
			if (!context.isEnabled()) {
				return;
			}
			Method method = null;
			List<VariableReference> parametersVr = new ArrayList<>();
			List<Class<?>> paramTypes = new ArrayList<>();
			ResolvedMethodLikeDeclaration resolved = mce.resolve();
			NodeList<Expression> parameters = mce.getArguments();
			try {
				extractDataOfParameters(resolved, parameters, context, parametersVr, paramTypes);
			} catch (TestImportException e) {
				throw new TestImportException(mce, e.getMessage(), e.getCause());
			}
			try {
				String qualifiedName = resolved.declaringType().getQualifiedName();
				Class<?> clazz = classLoaderForSUT.loadClass(qualifiedName);
				method = clazz.getMethod(mce.getNameAsString(), paramTypes.toArray(new Class<?>[0]));
			} catch (ClassNotFoundException | NoSuchMethodException | SecurityException e) {
				throw new TestImportException(mce, "Cannot load declaring class for the called method", e);
			}

			VariableReference vrReceiver = null; //null in static invocation, i.e., if scope not present
			Optional<Expression> scopeOpt = mce.getScope();
			if (scopeOpt.isPresent()) {
				Expression scope = scopeOpt.get();
				if (scope.isNameExpr()) {
					String variableName = scope.asNameExpr().getNameAsString();
					vrReceiver = context.getTracker().get(variableName);
				} else {
					super.visit(mce, context);
					vrReceiver = context.consumeNewlyAddedReference();
				}
			}

			VariableReference vr = testBuilder.appendMethod(vrReceiver, method, parametersVr.toArray(new VariableReference[0]));
			if (context.isExpressionVisitEnabled()) {
				context.setNewlyAddedReference(vr);
			}
		}

		@Override
		public void visit(ObjectCreationExpr oc, JUnitTestVisitorContext context) {
			if (!context.isEnabled()) {
				return;
			}
			if (!oc.getType().getNameAsString().equals("File")) {
				Constructor<?> constructor = null;
				List<Class<?>> paramTypes = new ArrayList<>();
				List<VariableReference> parametersVr = new ArrayList<>();
				ResolvedMethodLikeDeclaration resolvedConstructor = oc.resolve();
				NodeList<Expression> parameters = oc.getArguments();
				try {
					extractDataOfParameters(resolvedConstructor, parameters, context, parametersVr, paramTypes);
				} catch (TestImportException e) {
					throw new TestImportException(oc, e.getMessage(), e.getCause());
				}
				try {
					String qualifiedName = resolvedConstructor.declaringType().getQualifiedName();
					Class<?> clazz = classLoaderForSUT.loadClass(qualifiedName);
					constructor = clazz.getDeclaredConstructor(paramTypes.toArray(new Class<?>[0]));
				} catch (ClassNotFoundException | NoSuchMethodException | SecurityException e) {
					throw new TestImportException(oc, "Cannot load declared class of called constructor", e);
				}
				VariableReference vr = testBuilder.appendConstructor(constructor, parametersVr.toArray(new VariableReference[0]));
				if (context.isExpressionVisitEnabled()) {
					context.setNewlyAddedReference(vr);
				}
			} else {
				if (oc.getArguments().isEmpty()) {
					throw new TestImportException(oc, "File constructor: Missing file name");
				}
				Expression arg = oc.getArgument(0);
				String filePath;			
				if (arg.isStringLiteralExpr()) {
					StringLiteralExpr strExpr = arg.asStringLiteralExpr();
					filePath = strExpr.getValue();
				} else if (arg.isNameExpr()) {
					VariableReference vrFile = context.getTracker()
							.get(arg.asNameExpr().getNameAsString());
					TestCase testCase = testBuilder.getDefaultTestCase();
					Statement stmt = testCase.getStatement(vrFile.getStPosition());
					if (stmt instanceof PrimitiveStatement<?> && ((PrimitiveStatement<?>) stmt).getValue() instanceof String) {
						filePath = (String) ((PrimitiveStatement<?>) stmt).getValue();
					} else {
						throw new TestImportException(oc, "File constructor: File name refer to non-string value: " + stmt);
					}
				} else {
					throw new TestImportException(oc, "File constructor: File name refer to unhandled value: " + arg);				
				}
				EvoSuiteFile file = new EvoSuiteFile(filePath);
				VariableReference vr = testBuilder.appendFileNamePrimitive(file);
				if (context.isExpressionVisitEnabled()) {
					context.setNewlyAddedReference(vr);
				}
			}
		}

		private void extractDataOfParameters(ResolvedMethodLikeDeclaration resolvedCall, NodeList<Expression> parameters, JUnitTestVisitorContext context, 
				/* put results in: */ List<VariableReference> parametersVr, List<Class<?>> paramTypes) {
			int i = 0;
			for (Expression param : parameters) {
				ResolvedType paramType = resolvedCall.getParam(i).getType();
				Class<?> clazzParam;
				try {
					clazzParam = mapType(paramType, classLoaderForSUT);
				} catch (ClassNotFoundException e) {
					throw new TestImportException(null, "Cannot load class " + paramType + " for call" + resolvedCall, e);
				}
				paramTypes.add(clazzParam);

				VariableReference vr = null;
				if (param != null && param.isCastExpr() ) {
					param = param.asCastExpr().getExpression();
				}
				if (param == null) {
					vr = testBuilder.appendNull(clazzParam);
				} else if (param.isNameExpr()) {
					vr = context.getTracker().get(param.asNameExpr().getNameAsString());
				} else {
					boolean visitEnabled = false;
					try {
						context.enableExpressionVisit(param, paramType);
						visitEnabled = true;
						param.accept(this, context);
						vr = context.consumeNewlyAddedReference();
					} catch (TestImportException e) {
						throw e;
					} catch (Exception e) {
						throw new TestImportException(null, "Unknown issue while parsing parameter [" + param + "] for call " + resolvedCall, e);
					} finally {
						if (visitEnabled) {
							context.disableExpressionVisit(param);
						}
					}
				}
				parametersVr.add(vr);
				i++;
			}
		}

		/**
		 * Visitor methods for expression decomposition.
		 * -----
		 * These methods are enabled while processing expressions on the right-hand side 
		 * of declarations/assignments and within method-call arguments inside test-case 
		 * statements. They recursively break down complex expressions into intermediate 
		 * variable assignments, allowing for replacing inline expressions with direct 
		 * variable references. All them terminate by releasing in the context the reference 
		 * to the variable to be referenced in place of the original expressions.
		 * */

		@Override
		public void visit(ClassExpr cla, JUnitTestVisitorContext context) {
			if (!context.isExpressionVisitEnabled()) {
				return;
			}
			try {
				String qualifiedName = cla.asClassExpr().getType().resolve().describe();
				Class<?> clazz = classLoaderForSUT.loadClass(qualifiedName);
				VariableReference vr = testBuilder.appendClassPrimitive(clazz);
				context.setNewlyAddedReference(vr);
			} catch (ClassNotFoundException e) {
				throw new TestImportException(cla, "class not found", e);
			}
		}

		@Override
		public void visit(BooleanLiteralExpr n, JUnitTestVisitorContext context) {
			if (!context.isExpressionVisitEnabled()) {
				return;
			}
			boolean boolValue = Boolean.parseBoolean(n.toString());
			VariableReference vr = testBuilder.appendBooleanPrimitive(boolValue);
			context.setNewlyAddedReference(vr);
		}

		@Override
		public void visit(CharLiteralExpr n, JUnitTestVisitorContext context) {
			if (!context.isExpressionVisitEnabled()) {
				return;
			}
			char charValue = n.toString().charAt(1);
			VariableReference vr = testBuilder.appendCharPrimitive(charValue);
			context.setNewlyAddedReference(vr);
		}

		@Override
		public void visit(IntegerLiteralExpr n, JUnitTestVisitorContext context) {
			if (!context.isExpressionVisitEnabled()) {
				return;
			}
			VariableReference vr;
			int sign = context.getSignMultiplier();
			switch (n.calculateResolvedType().asPrimitive()) {
			case SHORT:
				short shortValue = (short) (sign * Short.parseShort(n.toString()));
				vr = testBuilder.appendShortPrimitive(shortValue);
				break;
			case BYTE:
				byte byteValue = (byte) (sign * Byte.parseByte(n.toString()));
				vr = testBuilder.appendBytePrimitive(byteValue);
				break;
			case INT:
				int intValue = sign * Integer.parseInt(n.toString());
				vr = testBuilder.appendIntPrimitive(intValue);
				break;
			default: 
				throw new TestImportException(n, "Wrong type of int literal");
			}		
			context.setNewlyAddedReference(vr);
		}

		@Override
		public void visit(LongLiteralExpr n, JUnitTestVisitorContext context) {
			if (!context.isExpressionVisitEnabled()) {
				return;
			}
			String exprToString = n.toString();
			int sign = context.getSignMultiplier();
			long longValue = (long) sign * Long.parseLong(exprToString.substring(0, exprToString.length() - 1));
			VariableReference vr = testBuilder.appendLongPrimitive(longValue);
			context.setNewlyAddedReference(vr);
		}

		@Override
		public void visit(DoubleLiteralExpr n, JUnitTestVisitorContext context) {
			if (!context.isExpressionVisitEnabled()) {
				return;
			}
			VariableReference vr;
			int sign = context.getSignMultiplier();
			switch (n.calculateResolvedType().asPrimitive()) {
			case FLOAT:
				float floatValue = (float) (sign * Float.parseFloat(n.toString()));
				vr = testBuilder.appendFloatPrimitive(floatValue);
				break;
			case DOUBLE:
				double doubleValue = sign * Double.parseDouble(n.toString());
				vr = testBuilder.appendDoublePrimitive(doubleValue);
				break;
			default:
				throw new TestImportException(n, "Wrong type of double literal");
			}
			context.setNewlyAddedReference(vr);
		}

		@Override
		public void visit(StringLiteralExpr str, JUnitTestVisitorContext context) {
			if (!context.isExpressionVisitEnabled()) {
				return;
			}
			String exprToString = str.toString();
			VariableReference vr = testBuilder
					.appendStringPrimitive(exprToString.substring(1, exprToString.length() - 1));
			context.setNewlyAddedReference(vr);
		}

		@Override
		public void visit(NullLiteralExpr n, JUnitTestVisitorContext context) {
			if (!context.isExpressionVisitEnabled()) {
				return;
			}
			java.lang.reflect.Type typeForEvoSuite = null;
			try {
				ResolvedType type = context.getTypeInCaseOfNullLiterals();
				typeForEvoSuite = mapType(type, classLoaderForSUT);
			} catch (Throwable e) {
				typeForEvoSuite = Object.class; // fallback
			}
			VariableReference vr = testBuilder.appendNull(typeForEvoSuite);
			context.setNewlyAddedReference(vr);
		}

		@Override
		public void visit(BinaryExpr be, JUnitTestVisitorContext context) {
			if (!context.isExpressionVisitEnabled()) {
				return;
			}

			be.getLeft().accept(this, context);
			VariableReference varLeft = context.consumeNewlyAddedReference();
			be.getRight().accept(this, context);
			VariableReference varRight = context.consumeNewlyAddedReference();

			//TODO: TestCaseBuilder misses a method for appendPrimitiveExpression
			DefaultTestCase testCase = testBuilder.getDefaultTestCase();
			Class<?> expressionType;
			try {
				ResolvedType type = be.calculateResolvedType();
				expressionType = mapType(type, classLoaderForSUT);
			} catch (ClassNotFoundException e) {
				throw new TestImportException(be, "class not found", e);
			}
			VariableReference varRef0 = new VariableReferenceImpl(testCase, expressionType);
			Operator evosuiteOp = Operator.toOperator(be.getOperator().asString());
			PrimitiveExpression exp = new PrimitiveExpression(testCase, varRef0, varLeft, evosuiteOp, varRight);
			VariableReference varRef = testCase.addStatement(exp, varRight.getStPosition() + 1);

			testBuilder = new TestCaseBuilder(testCase, varRef.getStPosition() + 1);
			context.setNewlyAddedReference(varRef);
		}

		@Override
		public void visit(FieldAccessExpr fae, JUnitTestVisitorContext context) {
			if (!context.isExpressionVisitEnabled()) {
				return;
			}
			ResolvedType expressionType = fae.calculateResolvedType();
			if (expressionType.isReferenceType() && 
					expressionType.asReferenceType().getTypeDeclaration().isPresent() &&
					expressionType.asReferenceType().getTypeDeclaration().get().isEnum()) {
				String enumClassName = expressionType.asReferenceType().getQualifiedName();
				String enumPackageName = expressionType.asReferenceType().getTypeDeclaration().get().getPackageName();
				String enumConstantName = fae.getNameAsString();
				String binaryName;
				if (enumPackageName == null || enumPackageName.isEmpty()) {
					binaryName = enumClassName.replace('.', '$');
				} else {
					String afterPkg = enumClassName.substring(enumPackageName.length() + 1);
					binaryName = enumPackageName + "." + afterPkg.replace('.', '$');
				}
				try {
					Class<?> enumClass = classLoaderForSUT.loadClass(binaryName);
					Enum<?> enumValue = Enum.valueOf((Class<Enum>) enumClass, enumConstantName);
					VariableReference vr = testBuilder.appendEnumPrimitive(enumValue);
					context.setNewlyAddedReference(vr);
				} catch (ClassNotFoundException e) {
					throw new TestImportException(fae, "class not found", e);
				}
			} else {
				String receiverName = fieldAccessReceiverName(fae, context);
				Field javaField;
				try {
					javaField = getField(fae);
				} catch (Exception e) {
					throw new TestImportException(fae, "issue while accessing field", e);
				}
				if (receiverName == null) { //static access
					VariableReference vr = testBuilder.appendStaticFieldStmt(javaField);
					context.setNewlyAddedReference(vr);
				} else {
					VariableReference vrReceiver = context.getTracker().get(receiverName);
					VariableReference vr = testBuilder.appendFieldStmt(vrReceiver, javaField);
					context.setNewlyAddedReference(vr);
				}
			}
		}

		@Override
		public void visit(CastExpr ce, JUnitTestVisitorContext context) {
			if (!context.isExpressionVisitEnabled()) {
				return;
			}
			ce.getExpression().accept(this, context);
			VariableReference vr = context.consumeNewlyAddedReference();

			//TODO: TestCaseBuilder misses a method for appendPrimitiveCastingExpression
			DefaultTestCase testCase = testBuilder.getDefaultTestCase();
			Class<?> expressionType;
			try {
				ResolvedType type = ce.calculateResolvedType();
				expressionType = mapType(type, classLoaderForSUT);
			} catch (ClassNotFoundException e) {
				throw new TestImportException(ce, "class not found", e);
			}
			VariableReference varRef0;
			PrimitiveCastingExpression exp;
			varRef0 = new VariableReferenceImpl(testCase, expressionType);
			exp = new PrimitiveCastingExpression(testCase, varRef0, vr, expressionType);
			VariableReference varRef = testCase.addStatement(exp, vr.getStPosition() + 1);

			testBuilder = new TestCaseBuilder(testCase, varRef.getStPosition() + 1);
			context.setNewlyAddedReference(varRef);
		}

		@Override
		public void visit(UnaryExpr ue, JUnitTestVisitorContext context) {
			if (!context.isExpressionVisitEnabled()) {
				return;
			}
			if (ue.getOperator() == UnaryExpr.Operator.MINUS) { //unary-MINUS 
				context.invertSignMultiplier();
			} 
			super.visit(ue, context);
		}

		@Override
		public void visit(ArrayCreationExpr ace, JUnitTestVisitorContext context) {
			if (!context.isEnabled()) {
				return;
			}
			ResolvedType resolvedType = ace.getElementType().resolve();
			java.lang.reflect.Type typeForEvoSuite = null;
			try {
				typeForEvoSuite = mapType(resolvedType, classLoaderForSUT);
			} catch (ClassNotFoundException e) {
				typeForEvoSuite = Object.class; // fallback
			}
			NodeList<ArrayCreationLevel> levels = ace.getLevels();
			int[] lengths = new int[levels.size()];
			for (int i = 0; i < levels.size(); i++) {
				typeForEvoSuite = java.lang.reflect.Array.newInstance((Class<?>) typeForEvoSuite, 0).getClass();
				lengths[i] = levels.get(i).getDimension().get().asIntegerLiteralExpr().asInt();
			}
			ArrayReference ar = testBuilder.appendArrayStmt(typeForEvoSuite, lengths);
			context.setNewlyAddedReference(ar);
		}

		/**
		 * Visitor methods for partially handled or non-handled constructs.
		 * -----
		 * These methods prevent visiting some parts of the code (e.g., catch
		 * blocks) which Evosuite does not handle in test execution, or 
		 * reorganize the visiting order of the code structures for the test
		 * code to be imported properly.
		 * */

		@Override
		public void visit(TryStmt n, JUnitTestVisitorContext arg) {
			// Evosuite does not handle catch blocks
			// 		n.getCatchClauses().forEach(p -> p.accept(this, arg));
			// Importing tests requires us to visit finally block after the try block
			//		n.getFinallyBlock().ifPresent(l -> l.accept(this, arg));
			n.getResources().forEach(p -> p.accept(this, arg));
			n.getTryBlock().accept(this, arg);
			n.getFinallyBlock().ifPresent(l -> l.accept(this, arg));
			//n.getComment().ifPresent(l -> l.accept(this, arg));
		}

	    @Override
	    public void visit(UnparsableStmt stmt, JUnitTestVisitorContext arg) {
			throw new TestImportException(stmt, "Unparsable statement");
	    }

		/**
		 * Visitor's utility methods
		* */
		
		public Class<?> mapType(ResolvedType resolvedType, ClassLoader classLoaderForSUT) throws ClassNotFoundException {
			if (resolvedType.isPrimitive()) {
				return mapPrimitive(resolvedType.asPrimitive());
			} else if (resolvedType.isArray()) {
				return mapArray(resolvedType.asArrayType(), classLoaderForSUT);
			} else if (resolvedType.isReferenceType()) {
				return mapReference(resolvedType.asReferenceType(), classLoaderForSUT);
			} else if (resolvedType.isTypeVariable()) {
				// Generics type variables — fallback: Object
				return Object.class;
			} else if (resolvedType.isWildcard()) {
				// Wildcard — fallback: Object
				return Object.class;
			} else {
				throw new UnsupportedOperationException("Type not supported: " + resolvedType.describe());
			}
		}

		private Class<?> mapPrimitive(ResolvedPrimitiveType primitiveType) {
			switch (primitiveType.getBoxTypeQName()) {
			case "java.lang.Boolean":
				return boolean.class;
			case "java.lang.Byte":
				return byte.class;
			case "java.lang.Character":
				return char.class;
			case "java.lang.Double":
				return double.class;
			case "java.lang.Float":
				return float.class;
			case "java.lang.Integer":
				return int.class;
			case "java.lang.Long":
				return long.class;
			case "java.lang.Short":
				return short.class;
			default:
				throw new IllegalArgumentException("Unknown primitive: " + primitiveType.describe());
			}
		}

		private Class<?> mapArray(ResolvedArrayType arrayType, ClassLoader classLoaderForSUT) throws ClassNotFoundException {
			Type componentType = mapType(arrayType.getComponentType(), classLoaderForSUT);
			if (componentType instanceof Class) {
				return Array.newInstance((Class<?>) componentType, 0).getClass();
			} else {
				// Evosuite handles only classes: fallback Object[].class
				return Object[].class;
			}
		}

		private Class<?> mapReference(ResolvedReferenceType referenceType, ClassLoader classLoaderForSUT) throws ClassNotFoundException {
			String qualifiedName = referenceType.getQualifiedName();
			return classLoaderForSUT.loadClass(qualifiedName);
		}

		private Field getField(FieldAccessExpr fieldAccessExpr) throws Exception {
			ResolvedFieldDeclaration resolvedFieldDecl = fieldAccessExpr.resolve().asField();
			try {
				Class<?> declaringClass = classLoaderForSUT.loadClass(resolvedFieldDecl.declaringType().getQualifiedName());
				Field javaField = declaringClass.getField(resolvedFieldDecl.getName());
				return javaField;
			} catch (ClassNotFoundException | NoSuchFieldException| SecurityException e) {
				LoggingUtils.getEvoLogger().info("\n\n* Issue while importing test case: " + e + " ::: " + Arrays.toString(e.getStackTrace()));
				throw e;
			}
		}

		private String fieldAccessReceiverName(FieldAccessExpr fieldAccessExpr, JUnitTestVisitorContext context) {
			if (fieldAccessExpr.resolve().asField().isStatic()) {
				return null;
			}
			Expression scope = fieldAccessExpr.getScope();
			String variableName;
			if (scope.isNameExpr()) {
				variableName = scope.asNameExpr().getNameAsString();
			} else {
				throw new IllegalArgumentException("Unsupported field-access expression: " + fieldAccessExpr);
			}
			/*else if (scope.isFieldAccessExpr()) {
			variableName = scope.asFieldAccessExpr().toString();
		} else {
			variableName = scope.toString(); // fallback
		}*/
			return variableName;
		}

	}
}
