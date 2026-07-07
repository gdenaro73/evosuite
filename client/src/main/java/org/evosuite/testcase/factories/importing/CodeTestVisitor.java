package org.evosuite.testcase.factories.importing;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;

import org.evosuite.testcase.TestCase;
import org.evosuite.TestGenerationContext;
import org.evosuite.runtime.testdata.EvoSuiteFile;
import org.evosuite.symbolic.TestCaseBuilder;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.LoggingUtils;

public class CodeTestVisitor extends VoidVisitorAdapter<VisitorContext> {

	private static CodeTestVisitor _I = null;
	
	public static CodeTestVisitor _I() {
		if (_I == null) {
			_I = new CodeTestVisitor();
		}
		return _I;
	}
	
	private CodeTestVisitor() { 
		// Parser configuration
		ParserConfiguration parserConfiguration = new ParserConfiguration();
		CombinedTypeSolver typeSolver = new CombinedTypeSolver();
		typeSolver.add(new ClassLoaderTypeSolver(TestGenerationContext.getInstance().getClassLoaderForSUT()));
		parserConfiguration.setSymbolResolver(new JavaSymbolSolver(typeSolver));
		StaticJavaParser.setConfiguration(parserConfiguration);
	}
	
	public List<TestCase> getTestCases(String testClassPath) throws IOException {
		CompilationUnit cu = StaticJavaParser.parse(Files.newInputStream(Paths.get(testClassPath)));
		VisitorContext context = new VisitorContext();
		visit(cu, context);
		return context.getTestCases();
	}
		
	private VariableReference appendPrimitiveStmt(Expression expr, ResolvedType exprType, VisitorContext context) throws Exception { 
		int signMultiplier = 1;
		while (expr.isEnclosedExpr() || expr.isCastExpr() ||
				expr.isUnaryExpr() && expr.asUnaryExpr().getOperator() == UnaryExpr.Operator.MINUS) {
			if (expr.isCastExpr()) {
				expr = expr.asCastExpr().getExpression();
			} else if (expr.isEnclosedExpr()) {	
				expr = expr.asEnclosedExpr().getInner();
			} else { //unary-MINUS 
				expr = expr.asUnaryExpr().getExpression();
				signMultiplier *= -1;
			}
		}

		if (expr.isLongLiteralExpr()) {
			String exprToString = expr.toString();
			long longValue = (long) signMultiplier * Long.parseLong(exprToString.substring(0, exprToString.length() - 1));
			VariableReference vr = context.getBuilder().appendLongPrimitive(longValue);
			return vr;
		} else if (expr.isIntegerLiteralExpr()) {
			VariableReference vr;
			if (!exprType.isPrimitive()) {
				throw new IllegalArgumentException("Wrong type [" + exprType + "] of literal [" + expr);
			}
			switch (exprType.asPrimitive()) {
			case SHORT:
				short shortValue = (short) (signMultiplier * Short.parseShort(expr.toString()));
				vr = context.getBuilder().appendShortPrimitive(shortValue);
				break;
			case BYTE:
				byte byteValue = (byte) (signMultiplier * Byte.parseByte(expr.toString()));
				vr = context.getBuilder().appendBytePrimitive(byteValue);
				break;
			case INT:
				int intValue = signMultiplier * Integer.parseInt(expr.toString());
				vr = context.getBuilder().appendIntPrimitive(intValue);
				break;
			default: 
				throw new IllegalArgumentException("Wrong type [" + exprType + "] of literal [" + expr);
			}
			return vr;
		} else if (expr.isBooleanLiteralExpr()) {
			boolean boolValue = Boolean.parseBoolean(expr.toString());
			VariableReference vr = context.getBuilder().appendBooleanPrimitive(boolValue);
			return vr;
		} else if (expr.isDoubleLiteralExpr()) {
			VariableReference vr;
			if (!exprType.isPrimitive()) {
				throw new IllegalArgumentException("Wrong type [" + exprType + "] of literal [" + expr);
			}
			switch (exprType.asPrimitive()) {
			case FLOAT:
				float floatValue = (float) (signMultiplier * Float.parseFloat(expr.toString()));
				vr = context.getBuilder().appendFloatPrimitive(floatValue);
				break;
			case DOUBLE:
				double doubleValue = signMultiplier * Double.parseDouble(expr.toString());
				vr = context.getBuilder().appendDoublePrimitive(doubleValue);
				break;
			default:
				throw new IllegalArgumentException("Wrong type [" + exprType + "] of literal [" + expr);
			}
			return vr;
		} else if (expr.isCharLiteralExpr()) {
			char charValue = expr.toString().charAt(1);
			VariableReference vr = context.getBuilder().appendCharPrimitive(charValue);
			return vr;
		} else if (expr.isStringLiteralExpr()) {
			String exprToString = expr.toString();
			VariableReference vr = context.getBuilder()
					.appendStringPrimitive(exprToString.substring(1, exprToString.length() - 1));
			return vr;
		} if (expr.isClassExpr()) {
			try {
				String qualifiedName = expr.asClassExpr().getType().resolve().describe();
				Class<?> clazz = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(qualifiedName);
				VariableReference vr = context.getBuilder().appendClassPrimitive(clazz);
				return vr;
			} catch (ClassNotFoundException e) {
				LoggingUtils.getEvoLogger().info("\n\n* Issue while importing test case: " + e + " ::: " + Arrays.toString(e.getStackTrace()));
				throw e;
			}
		} else if (expr.isNullLiteralExpr()) {
			java.lang.reflect.Type typeForEvoSuite = null;
			try {
				typeForEvoSuite = ResolvedTypeToReflectTypeConverter.toReflectType(exprType);
			} catch (ClassNotFoundException e) {
				typeForEvoSuite = Object.class; // fallback
			}
			VariableReference vr = context.getBuilder().appendNull(typeForEvoSuite);
			return vr;
		} else if (expr.isFieldAccessExpr()) {
			if (exprType.isReferenceType() && exprType.asReferenceType().getTypeDeclaration().get().isEnum()) {
				String enumClassName = exprType.asReferenceType().getQualifiedName();
				String enumPackageName = exprType.asReferenceType().getTypeDeclaration().get().getPackageName();
				String enumConstantName = expr.asFieldAccessExpr().getNameAsString();
				String binaryName;
				if (enumPackageName == null || enumPackageName.isEmpty()) {
					binaryName = enumClassName.replace('.', '$');
				} else {
					String afterPkg = enumClassName.substring(enumPackageName.length() + 1);
					binaryName = enumPackageName + "." + afterPkg.replace('.', '$');
				}
				try {
					Class<?> enumClass = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(binaryName);
					Enum<?> enumValue = Enum.valueOf((Class<Enum>) enumClass, enumConstantName);
					VariableReference vr = context.getBuilder().appendEnumPrimitive(enumValue);
					return vr;
				} catch (ClassNotFoundException e) {
					LoggingUtils.getEvoLogger().info("\n\n* Issue while importing test case: " + e + " ::: " + Arrays.toString(e.getStackTrace()));
					throw e;
				}
			} else {
				FieldAccessExpr fieldAccessExpr = expr.asFieldAccessExpr();
				Field javaField = getField(fieldAccessExpr);
				String receiverName = fieldAccessReceiverName(fieldAccessExpr, context);
				if (receiverName == null) { //static access
					VariableReference vr = context.getBuilder().appendStaticFieldStmt(javaField);
					return vr;
				} else {
					VariableReference vrReceiver = context.getTracker().get(receiverName);
					VariableReference vr = context.getBuilder().appendFieldStmt(vrReceiver, javaField);
					return vr;
				}
			}
		} else if (expr.isArrayCreationExpr()) {
			visit(expr.asArrayCreationExpr(), context);
			return context.popNewlyAddedReference();
		} else if (expr.isObjectCreationExpr()) {
			visit(expr.asObjectCreationExpr(), context);
			return context.popNewlyAddedReference();
		} else if (expr.isMethodCallExpr()) {
			visit(expr.asMethodCallExpr(), context);
			return context.popNewlyAddedReference();
		} else {
			LoggingUtils.getEvoLogger().info("\n\n* Issue while importing test case: Unhandled expression: " + expr);
			throw new IllegalArgumentException("Issue while importing test case: Unknown expression: " + expr);
		}
	}

	private Field getField(FieldAccessExpr fieldAccessExpr) throws Exception {
		ResolvedFieldDeclaration resolvedFieldDecl = fieldAccessExpr.resolve().asField();
		try {
			Class<?> declaringClass = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(resolvedFieldDecl.declaringType().getQualifiedName());
			Field javaField = declaringClass.getField(resolvedFieldDecl.getName());
			return javaField;
		} catch (ClassNotFoundException | NoSuchFieldException| SecurityException e) {
			LoggingUtils.getEvoLogger().info("\n\n* Issue while importing test case: " + e + " ::: " + Arrays.toString(e.getStackTrace()));
			throw e;
		}
	}

	private String fieldAccessReceiverName(FieldAccessExpr fieldAccessExpr, VisitorContext context) {
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

	/**
	 * Visitor methods
	 * 
	 * */
	
	@Override
	public void visit(MethodDeclaration md, VisitorContext context) {
		if (md.isAnnotationPresent("Test")) {
			context.enable();
			context.setBuilder(new TestCaseBuilder());
			context.setTracker(new HashMap<>());
			super.visit(md, context);
			context.add(context.getBuilder().getDefaultTestCase());
			context.disable();
		} 
	}

	@Override
	public void visit(VariableDeclarator vd, VisitorContext context) {
		if (!context.isEnabled()) {
			return;
		}
		VariableReference vr;
		if (!vd.getInitializer().isPresent()) {
			String type = vd.getType().toString();
			if (type.equals("int")) {
				vr = context.getBuilder().appendIntPrimitive(0);
			} else if (type.equals("boolean")) {
				vr = context.getBuilder().appendBooleanPrimitive(false);
			} else if (type.equals("char")) {
				vr = context.getBuilder().appendCharPrimitive('\0');
			} else if (type.equals("String")) {
				vr = context.getBuilder().appendStringPrimitive(null);
			} else if (type.equals("float")) {
				vr = context.getBuilder().appendFloatPrimitive(0.0f);
			} else if (type.equals("double")) {
				vr = context.getBuilder().appendDoublePrimitive(0.0);
			} else if (type.equals("byte")) {
				vr = context.getBuilder().appendBytePrimitive((byte) 0);
			} else {
				throw new IllegalArgumentException("Unhandled type: " + type);
			}
		} else {
			try {
				Expression expr = vd.getInitializer().get();
				vr = appendPrimitiveStmt(expr, vd.getType().resolve(), context);
			} catch (Exception e) {
				throw new IllegalArgumentException(e);
			}
		} 	
		String varName = vd.getNameAsString();
		context.getTracker().put(varName, vr);
	}

	@Override
	public void visit(ArrayCreationExpr ace, VisitorContext context) {
		if (!context.isEnabled()) {
			return;
		}
		ResolvedType resolvedType = ace.getElementType().resolve();
		java.lang.reflect.Type typeForEvoSuite = null;
		try {
			typeForEvoSuite = ResolvedTypeToReflectTypeConverter.toReflectType(resolvedType);
		} catch (ClassNotFoundException e) {
			typeForEvoSuite = Object.class; // fallback
		}
		NodeList<ArrayCreationLevel> levels = ace.getLevels();
		int[] lengths = new int[levels.size()];
		for (int i = 0; i < levels.size(); i++) {
			typeForEvoSuite = java.lang.reflect.Array.newInstance((Class<?>) typeForEvoSuite, 0).getClass();
			lengths[i] = levels.get(i).getDimension().get().asIntegerLiteralExpr().asInt();
		}
		ArrayReference ar = context.getBuilder().appendArrayStmt(typeForEvoSuite, lengths);
		context.pushNewlyAddedReference(ar);
	}

	@Override
	public void visit(AssignExpr ae, VisitorContext context) {
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
				throw new IllegalArgumentException("Unknown assignment target: " + ae, e);
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
			throw new IllegalArgumentException("Unknown assignment target: " + ae);
		}

		//2. Data about the value (right value) of the assignment
		Expression value = ae.getValue();
		VariableReference vrValue;
		if (value.isNameExpr()) {
			vrValue = context.getTracker().get(value.asNameExpr().getNameAsString());
		} else { //we handle all other expressions by defining a new variable in test case 
			try {
				vrValue = appendPrimitiveStmt(ae.getValue(), target.calculateResolvedType(), context);
			} catch (Exception e) {
				throw new IllegalArgumentException("Issue while parsing assignment value: " + ae, e);
			}
		}
		
		//3. Update variable-name references, or use the proper Evosuite-builder's appendAssigment method
		if (vrReceiver == null && vrReceiverField == null) {//target is nameExpr
			//after assignment, at future references, this variable name refers to assigned value 
			context.getTracker().put(target.asNameExpr().getNameAsString(), vrValue);
		} else if (vrReceiverField != null) {//target is fieldExpr
			//we append a new statement assigning the field to the value, no variable-name update needed 
			context.getBuilder().appendAssignment(vrReceiver, vrReceiverField, vrValue);		
		} else { //vrReceiver == null && vrReceiverArrayIndices != null  ////target arrayExpr
			//we append a new statement assigning the array item to the value, no variable-name update needed 
			if (vrReceiverArrayIndices.size() > 1) {
				context.getBuilder().appendAssignment((ArrayReference) vrReceiver, vrReceiverArrayIndices, vrValue);
			} else {
				context.getBuilder().appendAssignment((ArrayReference) vrReceiver, vrReceiverArrayIndices.get(0), vrValue);
			}
		}	
	}

	@Override
	public void visit(ObjectCreationExpr oc, VisitorContext context) {
		if (!context.isEnabled()) {
			return;
		}
		if (!oc.getType().getNameAsString().equals("File")) {
			Constructor<?> constructor = null;
			List<Class<?>> paramTypes = new ArrayList<>();
			List<VariableReference> parametersVr = new ArrayList<>();
			ResolvedMethodLikeDeclaration resolvedConstructor = oc.resolve();
			NodeList<Expression> parameters = oc.getArguments();
			extractDataOfParameters(resolvedConstructor, parameters, context, parametersVr, paramTypes);
			try {
				String qualifiedName = resolvedConstructor.declaringType().getQualifiedName();
				Class<?> clazz = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(qualifiedName);
				constructor = clazz.getDeclaredConstructor(paramTypes.toArray(new Class<?>[0]));
			} catch (ClassNotFoundException | NoSuchMethodException | SecurityException e) {
				throw new IllegalArgumentException("Cannot load class of constructor: " + oc, e);
			}
			VariableReference vr = context.getBuilder().appendConstructor(constructor, parametersVr.toArray(new VariableReference[0]));
			context.pushNewlyAddedReference(vr);
		} else {
			if (oc.getArguments().isEmpty()) {
				throw new IllegalArgumentException("File constructor: Missing file name: " + oc);
			}
			Expression arg = oc.getArgument(0);
			String filePath;			
			if (arg.isStringLiteralExpr()) {
				StringLiteralExpr strExpr = arg.asStringLiteralExpr();
				filePath = strExpr.getValue();
			} else if (arg.isNameExpr()) {
				VariableReference vrFile = context.getTracker()
						.get(arg.asNameExpr().getNameAsString());
				TestCase testCase = context.getBuilder().getDefaultTestCase();
				Statement stmt = testCase.getStatement(vrFile.getStPosition());
				if (stmt instanceof PrimitiveStatement<?> && ((PrimitiveStatement<?>) stmt).getValue() instanceof String) {
					filePath = (String) ((PrimitiveStatement<?>) stmt).getValue();
				} else {
					throw new IllegalArgumentException("File constructor: " + oc + " - File name refer to non-string value: " + stmt);
				}
			} else {
				throw new IllegalArgumentException("File constructor: " + oc + " - File name refer to unhandled value: " + arg);				
			}
			EvoSuiteFile file = new EvoSuiteFile(filePath);
			VariableReference vr = context.getBuilder().appendFileNamePrimitive(file);
			context.pushNewlyAddedReference(vr);
		}
	}

	@Override
	public void visit(MethodCallExpr mce, VisitorContext context) {
		if (!context.isEnabled()) {
			return;
		}
		Method method = null;
		List<VariableReference> parametersVr = new ArrayList<>();
		List<Class<?>> paramTypes = new ArrayList<>();
		ResolvedMethodLikeDeclaration resolved = mce.resolve();
		NodeList<Expression> parameters = mce.getArguments();
		extractDataOfParameters(resolved, parameters, context, parametersVr, paramTypes);
		try {
			String qualifiedName = resolved.declaringType().getQualifiedName();
			Class<?> clazz = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(qualifiedName);
			method = clazz.getMethod(mce.getNameAsString(), paramTypes.toArray(new Class<?>[0]));
		} catch (ClassNotFoundException | NoSuchMethodException | SecurityException e) {
			throw new IllegalArgumentException("Cannot load class of method call: " + mce, e);
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
				vrReceiver = context.popNewlyAddedReference();
			}
		}
		
		VariableReference vr = context.getBuilder().appendMethod(vrReceiver, method, parametersVr.toArray(new VariableReference[0]));
		context.pushNewlyAddedReference(vr);
	}

	private void extractDataOfParameters(ResolvedMethodLikeDeclaration resolvedCall, NodeList<Expression> parameters, VisitorContext context, 
			/* put results in: */ List<VariableReference> parametersVr, List<Class<?>> paramTypes) {
		int i = 0;
		for (Expression param : parameters) {
			ResolvedType paramType = resolvedCall.getParam(i).getType();
			Class<?> clazzParam;
			try {
				clazzParam = ResolvedTypeToReflectTypeConverter.toReflectType(paramType);
			} catch (ClassNotFoundException e) {
				throw new IllegalArgumentException("Cannot load class " + paramType + " of constructor param" + resolvedCall, e);
			}
			paramTypes.add(clazzParam);

			VariableReference vr = null;
			if (param != null && param.isCastExpr() ) {
				param = param.asCastExpr().getExpression();
			}
			if (param == null) {
				vr = context.getBuilder().appendNull(clazzParam);
			} else if (param.isNameExpr()) {
				vr = context.getTracker().get(param.asNameExpr().getNameAsString());
			} else {
				try {
					vr = appendPrimitiveStmt(param, paramType, context);
				} catch (Exception e) {
					throw new IllegalArgumentException("Issue with parameter " + param + " of call " + resolvedCall, e);
				}
			}
			parametersVr.add(vr);
			i++;
		}
	}

}
