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
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.TestGenerationContext;
import org.evosuite.runtime.testdata.EvoSuiteFile;
import org.evosuite.symbolic.TestCaseBuilder;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.PrimitiveExpression.Operator;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.LoggingUtils;

public class ImportingTestVisitor extends VoidVisitorAdapter<ImportingTestVisitorContext> {

	private static ImportingTestVisitor _I = null;
	
	public static ImportingTestVisitor _I() {
		if (_I == null) {
			_I = new ImportingTestVisitor();
		}
		return _I;
	}
	
	private class ImportingTestException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		public ImportingTestException(String message) {
			super(message);
		}
		public ImportingTestException(Throwable cause) {
			super(cause);
		}
		public ImportingTestException(String message, Throwable cause) {
			super(message, cause);
		}
	}
	
	private ImportingTestVisitor() { 
		// Parser configuration
		ParserConfiguration parserConfiguration = new ParserConfiguration();
		CombinedTypeSolver typeSolver = new CombinedTypeSolver();
		typeSolver.add(new ClassLoaderTypeSolver(TestGenerationContext.getInstance().getClassLoaderForSUT()));
		parserConfiguration.setSymbolResolver(new JavaSymbolSolver(typeSolver));
		StaticJavaParser.setConfiguration(parserConfiguration);
	}
	
	public List<TestCase> getTestCases(String testClassPath) throws IOException {
		CompilationUnit cu = StaticJavaParser.parse(Files.newInputStream(Paths.get(testClassPath)));
		ImportingTestVisitorContext context = new ImportingTestVisitorContext();
		visit(cu, context);
		return context.getTestCases();
	}
	
	/**
	 * Visitor methods
	 * 
	 * Main methods to import a method-declaration that defines a test case, 
	 * a variable declaration within a test case, an assignment statement
	 * within a test case, a method call expression within a test case
	 * 
	 * */
	
	@Override
	public void visit(MethodDeclaration md, ImportingTestVisitorContext context) {
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
	public void visit(VariableDeclarator vd, ImportingTestVisitorContext context) {
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
				throw new ImportingTestException("Unhandled type: " + type);
			}
		} else {
			Expression declaredValue = vd.getInitializer().get();
			try {
				context.enableExpressionVisit(declaredValue, vd.getType().resolve());
				declaredValue.accept(this, context);
				vr = context.consumeNewlyAddedReference();
			} catch (Exception e) {
				throw new ImportingTestException("Issue while parsing declared value: " + vd, e);
			} finally {
				context.disableExpressionVisit(declaredValue);				
			}
		} 	
		String varName = vd.getNameAsString();
		context.getTracker().put(varName, vr);
	}

	@Override
	public void visit(AssignExpr ae, ImportingTestVisitorContext context) {
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
				throw new ImportingTestException("Unknown assignment target: " + ae, e);
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
			throw new ImportingTestException("Unknown assignment target: " + ae);
		}

		//2. Data about the value (right value) of the assignment
		Expression value = ae.getValue();
		VariableReference vrValue;
		if (value.isNameExpr()) {
			vrValue = context.getTracker().get(value.asNameExpr().getNameAsString());
		} else { //we handle all other expressions by defining a new variable in test case 
			try {
				context.enableExpressionVisit(target, target.calculateResolvedType());
				ae.getValue().accept(this, context);
				vrValue = context.consumeNewlyAddedReference();
			} catch (Exception e) {
				throw new ImportingTestException("Issue while parsing assignment value: " + ae, e);
			} finally {
				context.disableExpressionVisit(target);				
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
	public void visit(MethodCallExpr mce, ImportingTestVisitorContext context) {
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
			throw new ImportingTestException("Cannot load class of method call: " + mce, e);
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
		
		VariableReference vr = context.getBuilder().appendMethod(vrReceiver, method, parametersVr.toArray(new VariableReference[0]));
		if (context.isExpressionVisitEnabled()) {
			context.setNewlyAddedReference(vr);
		}
	}

	@Override
	public void visit(ObjectCreationExpr oc, ImportingTestVisitorContext context) {
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
				throw new ImportingTestException("Cannot load class of constructor: " + oc, e);
			}
			VariableReference vr = context.getBuilder().appendConstructor(constructor, parametersVr.toArray(new VariableReference[0]));
			if (context.isExpressionVisitEnabled()) {
				context.setNewlyAddedReference(vr);
			}
		} else {
			if (oc.getArguments().isEmpty()) {
				throw new ImportingTestException("File constructor: Missing file name: " + oc);
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
					throw new ImportingTestException("File constructor: " + oc + " - File name refer to non-string value: " + stmt);
				}
			} else {
				throw new ImportingTestException("File constructor: " + oc + " - File name refer to unhandled value: " + arg);				
			}
			EvoSuiteFile file = new EvoSuiteFile(filePath);
			VariableReference vr = context.getBuilder().appendFileNamePrimitive(file);
			if (context.isExpressionVisitEnabled()) {
				context.setNewlyAddedReference(vr);
			}
		}
	}
	
	private void extractDataOfParameters(ResolvedMethodLikeDeclaration resolvedCall, NodeList<Expression> parameters, ImportingTestVisitorContext context, 
			/* put results in: */ List<VariableReference> parametersVr, List<Class<?>> paramTypes) {
		int i = 0;
		for (Expression param : parameters) {
			ResolvedType paramType = resolvedCall.getParam(i).getType();
			Class<?> clazzParam;
			try {
				clazzParam = ResolvedTypeToReflectTypeConverter.toReflectType(paramType);
			} catch (ClassNotFoundException e) {
				throw new ImportingTestException("Cannot load class " + paramType + " of constructor param" + resolvedCall, e);
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
					context.enableExpressionVisit(param, paramType);
					param.accept(this, context);
					vr = context.consumeNewlyAddedReference();
				} catch (Exception e) {
					throw new ImportingTestException("Issue while parsing parameter's value [" + param + "] of call " + resolvedCall, e);
				} finally {
					context.disableExpressionVisit(param);
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
	public void visit(ClassExpr cla, ImportingTestVisitorContext context) {
		if (!context.isExpressionVisitEnabled()) {
			return;
		}
		try {
			String qualifiedName = cla.asClassExpr().getType().resolve().describe();
			Class<?> clazz = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(qualifiedName);
			VariableReference vr = context.getBuilder().appendClassPrimitive(clazz);
			context.setNewlyAddedReference(vr);
		} catch (ClassNotFoundException e) {
			LoggingUtils.getEvoLogger().info("\n\n* Issue while importing test case: " + e + " ::: " + Arrays.toString(e.getStackTrace()));
			throw new ImportingTestException(e);
		}
	}

	@Override
	public void visit(BooleanLiteralExpr n, ImportingTestVisitorContext context) {
		if (!context.isExpressionVisitEnabled()) {
			return;
		}
		boolean boolValue = Boolean.parseBoolean(n.toString());
		VariableReference vr = context.getBuilder().appendBooleanPrimitive(boolValue);
		context.setNewlyAddedReference(vr);
	}

	@Override
	public void visit(CharLiteralExpr n, ImportingTestVisitorContext context) {
		if (!context.isExpressionVisitEnabled()) {
			return;
		}
		char charValue = n.toString().charAt(1);
		VariableReference vr = context.getBuilder().appendCharPrimitive(charValue);
		context.setNewlyAddedReference(vr);
	}

	@Override
	public void visit(IntegerLiteralExpr n, ImportingTestVisitorContext context) {
		if (!context.isExpressionVisitEnabled()) {
			return;
		}
		VariableReference vr;
		int sign = context.getSignMultiplier();
		switch (n.calculateResolvedType().asPrimitive()) {
		case SHORT:
			short shortValue = (short) (sign * Short.parseShort(n.toString()));
			vr = context.getBuilder().appendShortPrimitive(shortValue);
			break;
		case BYTE:
			byte byteValue = (byte) (sign * Byte.parseByte(n.toString()));
			vr = context.getBuilder().appendBytePrimitive(byteValue);
			break;
		case INT:
			int intValue = sign * Integer.parseInt(n.toString());
			vr = context.getBuilder().appendIntPrimitive(intValue);
			break;
		default: 
			throw new ImportingTestException("Wrong type of int literal " + n);
		}		
		context.setNewlyAddedReference(vr);
	}

	@Override
	public void visit(LongLiteralExpr n, ImportingTestVisitorContext context) {
		if (!context.isExpressionVisitEnabled()) {
			return;
		}
		String exprToString = n.toString();
		int sign = context.getSignMultiplier();
		long longValue = (long) sign * Long.parseLong(exprToString.substring(0, exprToString.length() - 1));
		VariableReference vr = context.getBuilder().appendLongPrimitive(longValue);
		context.setNewlyAddedReference(vr);
	}

	@Override
	public void visit(DoubleLiteralExpr n, ImportingTestVisitorContext context) {
		if (!context.isExpressionVisitEnabled()) {
			return;
		}
		VariableReference vr;
		int sign = context.getSignMultiplier();
		switch (n.calculateResolvedType().asPrimitive()) {
		case FLOAT:
			float floatValue = (float) (sign * Float.parseFloat(n.toString()));
			vr = context.getBuilder().appendFloatPrimitive(floatValue);
			break;
		case DOUBLE:
			double doubleValue = sign * Double.parseDouble(n.toString());
			vr = context.getBuilder().appendDoublePrimitive(doubleValue);
			break;
		default:
			throw new ImportingTestException("Wrong type of double literal " + n);
		}
		context.setNewlyAddedReference(vr);
	}
	
	@Override
	public void visit(StringLiteralExpr str, ImportingTestVisitorContext context) {
		if (!context.isExpressionVisitEnabled()) {
			return;
		}
		String exprToString = str.toString();
		VariableReference vr = context.getBuilder()
				.appendStringPrimitive(exprToString.substring(1, exprToString.length() - 1));
		context.setNewlyAddedReference(vr);
	}

	@Override
	public void visit(NullLiteralExpr n, ImportingTestVisitorContext context) {
		if (!context.isExpressionVisitEnabled()) {
			return;
		}
		java.lang.reflect.Type typeForEvoSuite = null;
		try {
			ResolvedType type = context.getTypeInCaseOfNullLiterals();
			typeForEvoSuite = ResolvedTypeToReflectTypeConverter.toReflectType(type);
		} catch (Throwable e) {
			typeForEvoSuite = Object.class; // fallback
		}
		VariableReference vr = context.getBuilder().appendNull(typeForEvoSuite);
		context.setNewlyAddedReference(vr);
	}
	
	@Override
	public void visit(BinaryExpr be, ImportingTestVisitorContext context) {
		if (!context.isExpressionVisitEnabled()) {
			return;
		}

		be.getLeft().accept(this, context);
		VariableReference varLeft = context.consumeNewlyAddedReference();
		be.getRight().accept(this, context);
		VariableReference varRight = context.consumeNewlyAddedReference();
		
		//TODO: TestCaseBuilder misses a method for appendPrimitiveExpression
		DefaultTestCase testCase = context.getBuilder().getDefaultTestCase();
		Class<?> expressionType;
		try {
			ResolvedType type = be.calculateResolvedType();
			expressionType = ResolvedTypeToReflectTypeConverter.toReflectType(type);
		} catch (ClassNotFoundException e) {
			throw new ImportingTestException(e);
		}
		VariableReference varRef0 = new VariableReferenceImpl(testCase, expressionType);
		Operator evosuiteOp = Operator.toOperator(be.getOperator().asString());
		PrimitiveExpression exp = new PrimitiveExpression(testCase, varRef0, varLeft, evosuiteOp, varRight);
        VariableReference varRef = testCase.addStatement(exp, varRight.getStPosition() + 1);
		
        context.setBuilder(new TestCaseBuilder(testCase, varRef.getStPosition() + 1));
		context.setNewlyAddedReference(varRef);
	}

	@Override
	public void visit(FieldAccessExpr fae, ImportingTestVisitorContext context) {
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
				Class<?> enumClass = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(binaryName);
				Enum<?> enumValue = Enum.valueOf((Class<Enum>) enumClass, enumConstantName);
				VariableReference vr = context.getBuilder().appendEnumPrimitive(enumValue);
				context.setNewlyAddedReference(vr);
			} catch (ClassNotFoundException e) {
				LoggingUtils.getEvoLogger().info("\n\n* Issue while importing test case: " + e + " ::: " + Arrays.toString(e.getStackTrace()));
				throw new ImportingTestException(e);
			}
		} else {
			String receiverName = fieldAccessReceiverName(fae, context);
			Field javaField;
			try {
				javaField = getField(fae);
			} catch (Exception e) {
				throw new ImportingTestException(e);
			}
			if (receiverName == null) { //static access
				VariableReference vr = context.getBuilder().appendStaticFieldStmt(javaField);
				context.setNewlyAddedReference(vr);
			} else {
				VariableReference vrReceiver = context.getTracker().get(receiverName);
				VariableReference vr = context.getBuilder().appendFieldStmt(vrReceiver, javaField);
				context.setNewlyAddedReference(vr);
			}
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

	private String fieldAccessReceiverName(FieldAccessExpr fieldAccessExpr, ImportingTestVisitorContext context) {
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
	
	@Override
	public void visit(CastExpr ce, ImportingTestVisitorContext context) {
		if (!context.isExpressionVisitEnabled()) {
			return;
		}
		ce.getExpression().accept(this, context);
		VariableReference vr = context.consumeNewlyAddedReference();

		//TODO: TestCaseBuilder misses a method for appendPrimitiveCastingExpression
		DefaultTestCase testCase = context.getBuilder().getDefaultTestCase();
		Class<?> expressionType;
		try {
			ResolvedType type = ce.calculateResolvedType();
			expressionType = ResolvedTypeToReflectTypeConverter.toReflectType(type);
		} catch (ClassNotFoundException e) {
			throw new ImportingTestException(e);
		}
		VariableReference varRef0;
		PrimitiveCastingExpression exp;
		varRef0 = new VariableReferenceImpl(testCase, expressionType);
		exp = new PrimitiveCastingExpression(testCase, varRef0, vr, expressionType);
		VariableReference varRef = testCase.addStatement(exp, vr.getStPosition() + 1);
		
		context.setBuilder(new TestCaseBuilder(testCase, varRef.getStPosition() + 1));
		context.setNewlyAddedReference(varRef);
	}

	@Override
	public void visit(UnaryExpr ue, ImportingTestVisitorContext context) {
		if (!context.isExpressionVisitEnabled()) {
			return;
		}
		if (ue.getOperator() == UnaryExpr.Operator.MINUS) { //unary-MINUS 
			context.invertSignMultiplier();
		} 
		super.visit(ue, context);
	}
	
	@Override
	public void visit(ArrayCreationExpr ace, ImportingTestVisitorContext context) {
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
		context.setNewlyAddedReference(ar);
	}
	
}
