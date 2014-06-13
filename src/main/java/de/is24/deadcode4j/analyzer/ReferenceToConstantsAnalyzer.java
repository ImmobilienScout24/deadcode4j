package de.is24.deadcode4j.analyzer;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Sets;
import de.is24.deadcode4j.AnalysisContext;
import de.is24.deadcode4j.analyzer.javassist.ClassPoolAccessor;
import de.is24.javaparser.FixedVoidVisitorAdapter;
import japa.parser.ast.CompilationUnit;
import japa.parser.ast.ImportDeclaration;
import japa.parser.ast.Node;
import japa.parser.ast.body.*;
import japa.parser.ast.expr.*;
import japa.parser.ast.stmt.*;
import japa.parser.ast.type.ClassOrInterfaceType;
import javassist.CtClass;
import javassist.CtField;
import javassist.Modifier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import static com.google.common.base.Predicates.and;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.Iterables.*;
import static com.google.common.collect.Lists.newLinkedList;
import static com.google.common.collect.Sets.newHashSet;
import static de.is24.deadcode4j.Utils.emptyIfNull;
import static de.is24.deadcode4j.analyzer.javassist.ClassPoolAccessor.classPoolAccessorFor;
import static de.is24.deadcode4j.analyzer.javassist.CtClasses.*;
import static de.is24.javaparser.ImportDeclarations.isAsterisk;
import static de.is24.javaparser.ImportDeclarations.isStatic;
import static de.is24.javaparser.Nodes.getTypeName;
import static java.lang.Math.max;
import static java.util.Collections.singleton;

public class ReferenceToConstantsAnalyzer extends JavaFileAnalyzer {

    @Nonnull
    private static String getFirstElement(@Nonnull FieldAccessExpr fieldAccessExpr) {
        return getFirstNode(fieldAccessExpr).getName();
    }

    @Nonnull
    private static NameExpr getFirstNode(@Nonnull FieldAccessExpr fieldAccessExpr) {
        Expression scope = fieldAccessExpr.getScope();
        if (NameExpr.class.isInstance(scope)) {
            return NameExpr.class.cast(scope);
        } else if (FieldAccessExpr.class.isInstance(scope)) {
            return getFirstNode(FieldAccessExpr.class.cast(scope));
        }
        throw new RuntimeException("Should not have reached this point!");
    }

    private static boolean isRegularFieldAccessExpr(@Nonnull FieldAccessExpr fieldAccessExpr) {
        Expression scope = fieldAccessExpr.getScope();
        if (NameExpr.class.isInstance(scope)) {
            return true;
        } else if (FieldAccessExpr.class.isInstance(scope)) {
            return isRegularFieldAccessExpr(FieldAccessExpr.class.cast(scope));
        }
        return false;
    }

    /**
     * This is not entirely correct: while we want to filter calls like
     * <code>org.slf4j.LoggerFactory.getLogger("foo")</code>, we want to analyze
     * <code>foo.bar.FOO.substring(1)</code>.
     */
    private static boolean isScopeOfAMethodCall(@Nonnull Expression expression) {
        return MethodCallExpr.class.isInstance(expression.getParentNode())
                && expression == MethodCallExpr.class.cast(expression.getParentNode()).getScope();
    }

    private static boolean isScopeOfThisExpression(@Nonnull Expression expression) {
        return ThisExpr.class.isInstance(expression.getParentNode());
    }

    private static boolean isTargetOfAnAssignment(@Nonnull Expression expression) {
        return AssignExpr.class.isInstance(expression.getParentNode())
                && expression == AssignExpr.class.cast(expression.getParentNode()).getTarget();
    }

    private static Predicate<? super ImportDeclaration> refersTo(final String name) {
        return new Predicate<ImportDeclaration>() {
            @Override
            public boolean apply(@Nullable ImportDeclaration input) {
                return input != null && input.getName().getName().equals(name);
            }
        };
    }

    private static Function<? super ImportDeclaration, ? extends String> toImportedType() {
        return new Function<ImportDeclaration, String>() {
            @Nullable
            @Override
            public String apply(@Nullable ImportDeclaration input) {
                if (input == null)
                    return null;
                NameExpr name = input.getName();
                if (input.isStatic() && !input.isAsterisk()) {
                    name = QualifiedNameExpr.class.cast(name).getQualifier();
                }
                return name.toString();
            }
        };
    }

    private static boolean isConstant(CtField ctField) {
        return Modifier.isStatic(ctField.getModifiers()) && Modifier.isFinal(ctField.getModifiers());
    }

    @Override
    protected void analyzeCompilationUnit(@Nonnull final AnalysisContext analysisContext,
                                          @Nonnull final CompilationUnit compilationUnit) {
        compilationUnit.accept(new LocalVariableRecordingVisitor<Void>() {
            private final ClassPoolAccessor classPoolAccessor = classPoolAccessorFor(analysisContext);

            @Override
            public void visit(FieldAccessExpr n, Void arg) {
                if (isTargetOfAnAssignment(n) || isScopeOfAMethodCall(n) || isScopeOfThisExpression(n)) {
                    return;
                }
                if (!isRegularFieldAccessExpr(n)) {
                    super.visit(n, arg);
                    return;
                }
                if (aLocalVariableExists(getFirstElement(n))) {
                    return;
                }
                if (FieldAccessExpr.class.isInstance(n.getScope())) {
                    FieldAccessExpr nestedFieldAccessExpr = FieldAccessExpr.class.cast(n.getScope());
                    if (isFullyQualifiedReference(nestedFieldAccessExpr)) { // fq beats all
                        return;
                    }
                    resolveFieldReference(n);
                } else if (NameExpr.class.isInstance(n.getScope())) {
                    resolveFieldReference(n);
                }
            }

            @Override
            public void visit(NameExpr n, Void arg) {
                if (isTargetOfAnAssignment(n) || isScopeOfAMethodCall(n) || isScopeOfThisExpression(n)) {
                    return;
                }
                resolveNameReference(n);
            }

            private boolean isFullyQualifiedReference(FieldAccessExpr fieldAccessExpr) {
                Optional<String> resolvedClass = resolveClass(fieldAccessExpr.toString());
                if (resolvedClass.isPresent()) {
                    analysisContext.addDependencies(getTypeName(fieldAccessExpr), resolvedClass.get());
                    return true;
                }
                return FieldAccessExpr.class.isInstance(fieldAccessExpr.getScope())
                        && isFullyQualifiedReference(FieldAccessExpr.class.cast(fieldAccessExpr.getScope()));
            }

            private Optional<String> resolveClass(String qualifier) {
                return this.classPoolAccessor.resolveClass(qualifier);
            }

            private void resolveFieldReference(FieldAccessExpr fieldAccessExpr) {
                if (!(refersToInnerType(fieldAccessExpr)
                        || refersToInheritedField(fieldAccessExpr)
                        || refersToImport(fieldAccessExpr)
                        || refersToPackageType(fieldAccessExpr)
                        || refersToAsteriskImport(fieldAccessExpr)
                        || refersToJavaLang(fieldAccessExpr))) {
                    logger.debug("Could not resolve reference [{}] found within [{}].", fieldAccessExpr, getTypeName(fieldAccessExpr));
                }
            }

            private boolean refersToInnerType(@Nonnull FieldAccessExpr fieldAccessExpr) {
                NameExpr firstQualifier = getFirstNode(fieldAccessExpr);
                Node loopNode = fieldAccessExpr;
                for (; ; ) {
                    if (TypeDeclaration.class.isInstance(loopNode)) {
                        TypeDeclaration typeDeclaration = TypeDeclaration.class.cast(loopNode);
                        if (resolveInnerReference(firstQualifier, singleton(typeDeclaration))) {
                            return true;
                        }
                        if (resolveInnerReference(firstQualifier, typeDeclaration.getMembers())) {
                            return true;
                        }
                    } else if (CompilationUnit.class.isInstance(loopNode)
                            && resolveInnerReference(firstQualifier, CompilationUnit.class.cast(loopNode).getTypes())) {
                        return true;
                    }
                    loopNode = loopNode.getParentNode();
                    if (loopNode == null) {
                        break;
                    }
                }

                return false;
            }

            private boolean resolveInnerReference(@Nonnull NameExpr firstQualifier,
                                                  @Nullable Iterable<? extends BodyDeclaration> bodyDeclarations) {
                for (TypeDeclaration typeDeclaration : emptyIfNull(bodyDeclarations).filter(TypeDeclaration.class)) {
                    if (firstQualifier.getName().equals(typeDeclaration.getName())) {
                        String referencedClass = resolveReferencedType(firstQualifier, typeDeclaration);
                        analysisContext.addDependencies(getTypeName(firstQualifier), referencedClass);
                        return true;
                    }
                }
                return false;
            }

            @Nonnull
            private String resolveReferencedType(@Nonnull NameExpr firstQualifier,
                                                 @Nonnull TypeDeclaration typeDeclaration) {
                if (FieldAccessExpr.class.isInstance(firstQualifier.getParentNode())) {
                    NameExpr nextQualifier = FieldAccessExpr.class.cast(firstQualifier.getParentNode()).getFieldExpr();
                    for (TypeDeclaration nextTypeDeclaration : emptyIfNull(typeDeclaration.getMembers()).filter(TypeDeclaration.class)) {
                        if (nextQualifier.getName().equals(nextTypeDeclaration.getName())) {
                            return resolveReferencedType(nextQualifier, nextTypeDeclaration);
                        }
                    }
                }

                return getTypeName(typeDeclaration);
            }

            private boolean refersToInheritedField(FieldAccessExpr fieldAccessExpr) {
                CtClass referencingClazz = getCtClass(classPoolAccessor.getClassPool(), getTypeName(fieldAccessExpr));
                if (referencingClazz == null) {
                    return false;
                }
                for (CtClass declaringClazz : getDeclaringClassesOf(referencingClazz)) {
                    if (refersToInheritedField(referencingClazz, declaringClazz, fieldAccessExpr)) {
                        return true;
                    }
                }
                return false;
            }

            private boolean refersToInheritedField(@Nonnull final CtClass referencingClazz,
                                                   @Nullable CtClass clazz,
                                                   @Nonnull final FieldAccessExpr fieldAccessExpr) {
                if (clazz == null || isJavaLangObject(clazz)) {
                    return false;
                }
                for (CtField ctField : clazz.getDeclaredFields()) {
                    if (ctField.getName().equals(getFirstElement(fieldAccessExpr)) && ctField.visibleFrom(referencingClazz)) {
                        if (isConstant(ctField)) {
                            analysisContext.addDependencies(referencingClazz.getName(), clazz.getName());
                        }
                        return true;
                    }
                }
                for (CtClass nestedClazz : getNestedClassesOf(clazz)) {
                    if (nestedClazz.getName().substring(clazz.getName().length() + 1).equals(getFirstElement(fieldAccessExpr))) {
                        if (refersToClass(fieldAccessExpr, clazz.getName() + "$")) {
                            return true;
                        } else {
                            logger.warn("Could not resolve inherited type [{}] found within [{}]!",
                                    nestedClazz.getName(), getTypeName(fieldAccessExpr));
                            return false;
                        }
                    }
                }
                if (refersToInheritedField(referencingClazz, getSuperclassOf(clazz), fieldAccessExpr)) {
                    return true;
                }
                for (CtClass interfaceClazz : getInterfacesOf(clazz)) {
                    if (refersToInheritedField(referencingClazz, interfaceClazz, fieldAccessExpr)) {
                        return true;
                    }
                }
                return false;
            }

            private boolean refersToImport(FieldAccessExpr fieldAccessExpr) {
                String firstElement = getFirstElement(fieldAccessExpr);
                String anImport = getImport(firstElement);
                if (anImport != null) {
                    return
                            refersToClass(fieldAccessExpr, anImport.substring(0, max(0, anImport.lastIndexOf('.') + 1)));
                }
                anImport = getStaticImport(firstElement);
                return anImport != null &&
                        refersToClass(fieldAccessExpr, anImport + ".");
            }

            private boolean refersToPackageType(FieldAccessExpr fieldAccessExpr) {
                String packagePrefix = compilationUnit.getPackage() == null
                        ? "" : compilationUnit.getPackage().getName().toString() + ".";
                return refersToClass(fieldAccessExpr, packagePrefix);
            }

            private boolean refersToAsteriskImport(FieldAccessExpr fieldAccessExpr) {
                for (String asteriskImport : getAsteriskImports()) {
                    if (refersToClass(fieldAccessExpr, asteriskImport + "."))
                        return true;
                }
                return false;
            }

            private boolean refersToJavaLang(FieldAccessExpr fieldAccessExpr) {
                return refersToClass(fieldAccessExpr, "java.lang.");
            }

            private boolean refersToClass(@Nonnull FieldAccessExpr fieldAccessExpr, @Nonnull String qualifierPrefix) {
                // skip last qualifier, as it refers to a field, not a class
                return refersToClass(fieldAccessExpr.getScope(), qualifierPrefix);
            }

            private boolean refersToClass(@Nonnull Expression expression, @Nonnull String qualifierPrefix) {
                if (NameExpr.class.isInstance(expression)) {
                    Optional<String> resolvedClass = resolveClass(qualifierPrefix + NameExpr.class.cast(expression).getName());
                    if (resolvedClass.isPresent()) {
                        analysisContext.addDependencies(getTypeName(expression), resolvedClass.get());
                        return true;
                    }
                    return false;
                }
                Optional<String> resolvedClass = resolveClass(qualifierPrefix + expression.toString());
                if (resolvedClass.isPresent()) {
                    analysisContext.addDependencies(getTypeName(expression), resolvedClass.get());
                    return true;
                }
                return refersToClass(FieldAccessExpr.class.cast(expression).getScope(), qualifierPrefix);
            }

            private void resolveNameReference(NameExpr reference) {
                if (aLocalVariableExists(reference.getName())
                        || refersToInheritedField(reference)
                        || refersToStaticImport(reference)
                        || refersToAsteriskStaticImport(reference)) {
                    return;
                }
                logger.debug("Could not resolve name reference [{}] found within [{}].",
                        reference, getTypeName(reference));
            }

            private boolean refersToInheritedField(NameExpr reference) {
                CtClass referencingClazz = getCtClass(classPoolAccessor.getClassPool(), getTypeName(reference));
                if (referencingClazz == null) {
                    return false;
                }
                for (CtClass declaringClazz : getDeclaringClassesOf(referencingClazz)) {
                    if (refersToInheritedField(referencingClazz, declaringClazz, reference)) {
                        return true;
                    }
                }
                return false;
            }

            private boolean refersToInheritedField(@Nonnull final CtClass referencingClazz,
                                                   @Nullable CtClass clazz,
                                                   @Nonnull final NameExpr reference) {
                if (clazz == null || isJavaLangObject(clazz)) {
                    return false;
                }
                for (CtField ctField : clazz.getDeclaredFields()) {
                    if (ctField.getName().equals(reference.getName()) && ctField.visibleFrom(referencingClazz)) {
                        if (isConstant(ctField)) {
                            analysisContext.addDependencies(referencingClazz.getName(), clazz.getName());
                        }
                        return true;
                    }
                }
                if (refersToInheritedField(referencingClazz, getSuperclassOf(clazz), reference)) {
                    return true;
                }
                for (CtClass interfaceClazz : getInterfacesOf(clazz)) {
                    if (refersToInheritedField(referencingClazz, interfaceClazz, reference)) {
                        return true;
                    }
                }
                return false;
            }

            private boolean refersToStaticImport(NameExpr reference) {
                String referenceName = reference.getName();
                String staticImport = getStaticImport(referenceName);
                if (staticImport == null) {
                    return false;
                }
                String typeName = getTypeName(reference);
                Optional<String> resolvedClass = resolveClass(staticImport);
                if (resolvedClass.isPresent()) {
                    analysisContext.addDependencies(typeName, resolvedClass.get());
                } else {
                    logger.warn("Could not resolve static import [{}.{}] found within [{}]!",
                            staticImport, referenceName, typeName);
                }
                return true;
            }

            private boolean refersToAsteriskStaticImport(NameExpr reference) {
                CtClass referencingClazz = getCtClass(classPoolAccessor.getClassPool(), getTypeName(reference));
                if (referencingClazz == null) {
                    return false;
                }
                for (String asteriskImport : getStaticAsteriskImports()) {
                    Optional<String> resolvedClass = resolveClass(asteriskImport);
                    if (!resolvedClass.isPresent()) {
                        String typeName = getTypeName(reference);
                        logger.warn("Could not resolve static import [{}.*] found within [{}]!",
                                asteriskImport, typeName);
                        continue;
                    }
                    CtClass potentialTarget = getCtClass(classPoolAccessor.getClassPool(), resolvedClass.get());
                    if (refersToInheritedField(referencingClazz, potentialTarget, reference)) {
                        return true;
                    }
                }

                return false;
            }

            @Nullable
            @SuppressWarnings("unchecked")
            private String getImport(String typeName) {
                return getOnlyElement(transform(filter(emptyIfNull(compilationUnit.getImports()),
                        and(refersTo(typeName), not(isAsterisk()), not(isStatic()))), toImportedType()), null);
            }

            @Nullable
            @SuppressWarnings("unchecked")
            private String getStaticImport(String referenceName) {
                return getOnlyElement(transform(filter(emptyIfNull(compilationUnit.getImports()),
                        and(refersTo(referenceName), not(isAsterisk()), isStatic())), toImportedType()), null);
            }

            @Nonnull
            private Iterable<String> getAsteriskImports() {
                return transform(filter(emptyIfNull(compilationUnit.getImports()),
                        and(isAsterisk(), not(isStatic()))), toImportedType());
            }

            @Nonnull
            private Iterable<String> getStaticAsteriskImports() {
                return transform(filter(emptyIfNull(compilationUnit.getImports()),
                        and(isAsterisk(), isStatic())), toImportedType());
            }

            @Override
            public void visit(ClassOrInterfaceType n, Void arg) {
                // performance
            }

            @Override
            public void visit(CompilationUnit n, Void arg) {
                // performance
                for (final TypeDeclaration typeDeclaration : emptyIfNull(n.getTypes())) {
                    typeDeclaration.accept(this, arg);
                }
            }

            @Override
            public void visit(MarkerAnnotationExpr n, Void arg) {
                // performance
            }

            @Override
            public void visit(NormalAnnotationExpr n, Void arg) {
                // performance
                for (final MemberValuePair m : emptyIfNull(n.getPairs())) {
                    m.accept(this, arg);
                }
            }

            @Override
            public void visit(SingleMemberAnnotationExpr n, Void arg) {
                // performance
                n.getMemberValue().accept(this, arg);
            }

        }, null);
    }

    private static class LocalVariableRecordingVisitor<A> extends FixedVoidVisitorAdapter<A> {

        @Nonnull
        private final Deque<Set<String>> localVariables = newLinkedList();

        @Override
        public void visit(ClassOrInterfaceDeclaration n, A arg) {
            HashSet<String> fields = newHashSet();
            this.localVariables.addLast(fields);
            try {
                addFieldVariables(n, fields);
                super.visit(n, arg);
            } finally {
                this.localVariables.removeLast();
            }
        }

        @Override
        public void visit(EnumDeclaration n, A arg) {
            HashSet<String> fieldsAndEnums = newHashSet();
            this.localVariables.addLast(fieldsAndEnums);
            try {
                for (EnumConstantDeclaration enumConstantDeclaration : emptyIfNull(n.getEntries())) {
                    fieldsAndEnums.add(enumConstantDeclaration.getName());
                }
                addFieldVariables(n, fieldsAndEnums);
                super.visit(n, arg);
            } finally {
                this.localVariables.removeLast();
            }
        }

        @Override
        public void visit(ObjectCreationExpr n, A arg) {
            HashSet<String> fields = newHashSet();
            this.localVariables.addLast(fields);
            try {
                addFieldVariables(n.getAnonymousClassBody(), fields);
                super.visit(n, arg);
            } finally {
                this.localVariables.removeLast();
            }
        }

        @Override
        public void visit(ConstructorDeclaration n, A arg) {
            HashSet<String> blockVariables = newHashSet();
            this.localVariables.addLast(blockVariables);
            try {
                for (Parameter parameter : emptyIfNull(n.getParameters())) {
                    blockVariables.add(parameter.getId().getName());
                }
                for (AnnotationExpr annotationExpr : emptyIfNull(n.getAnnotations())) {
                    annotationExpr.accept(this, arg);
                }
                BlockStmt body = n.getBlock();
                if (body != null) {
                    visit(body, arg);
                }
            } finally {
                this.localVariables.removeLast();
            }
        }

        @Override
        public void visit(MethodDeclaration n, A arg) {
            HashSet<String> blockVariables = newHashSet();
            this.localVariables.addLast(blockVariables);
            try {
                for (Parameter parameter : emptyIfNull(n.getParameters())) {
                    blockVariables.add(parameter.getId().getName());
                }
                for (AnnotationExpr annotationExpr : emptyIfNull(n.getAnnotations())) {
                    annotationExpr.accept(this, arg);
                }
                BlockStmt body = n.getBody();
                if (body != null) {
                    visit(body, arg);
                }
            } finally {
                this.localVariables.removeLast();
            }
        }

        @Override
        public void visit(CatchClause n, A arg) {
            MultiTypeParameter multiTypeParameter = n.getExcept();
            HashSet<String> blockVariables = newHashSet();
            this.localVariables.addLast(blockVariables);
            try {
                blockVariables.add(multiTypeParameter.getId().getName());
                for (AnnotationExpr annotationExpr : emptyIfNull(multiTypeParameter.getAnnotations())) {
                    annotationExpr.accept(this, arg);
                }
                BlockStmt body = n.getCatchBlock();
                if (body != null) {
                    visit(body, arg);
                }
            } finally {
                this.localVariables.removeLast();
            }
        }

        @Override
        public void visit(BlockStmt n, A arg) {
            this.localVariables.addLast(Sets.<String>newHashSet());
            try {
                super.visit(n, arg);
            } finally {
                this.localVariables.removeLast();
            }
        }

        @Override
        public void visit(ForeachStmt n, A arg) {
            HashSet<String> blockVariables = newHashSet();
            this.localVariables.addLast(blockVariables);
            try {
                for (VariableDeclarator variableDeclarator : emptyIfNull(n.getVariable().getVars())) {
                    blockVariables.add(variableDeclarator.getId().getName());
                }
                n.getIterable().accept(this, arg);
                n.getBody().accept(this, arg);
            } finally {
                this.localVariables.removeLast();
            }
        }

        @Override
        public void visit(ForStmt n, A arg) {
            this.localVariables.addLast(Sets.<String>newHashSet());
            try {
                super.visit(n, arg);
            } finally {
                this.localVariables.removeLast();
            }
        }

        @Override
        public void visit(TryStmt n, A arg) {
            HashSet<String> blockVariables = newHashSet();
            this.localVariables.addLast(blockVariables);
            try {
                for (VariableDeclarationExpr variableDeclarationExpr : emptyIfNull(n.getResources())) {
                    for (VariableDeclarator variableDeclarator : variableDeclarationExpr.getVars()) {
                        blockVariables.add(variableDeclarator.getId().getName());
                    }
                }
                super.visit(n, arg);
            } finally {
                this.localVariables.removeLast();
            }
        }

        @Override
        public void visit(VariableDeclarationExpr n, A arg) {
            for (AnnotationExpr annotationExpr : emptyIfNull(n.getAnnotations())) {
                annotationExpr.accept(this, arg);
            }
            n.getType().accept(this, arg);
            Set<String> blockVariables = this.localVariables.getLast();
            for (VariableDeclarator variableDeclarator : n.getVars()) {
                Expression expr = variableDeclarator.getInit();
                if (expr != null) {
                    expr.accept(this, arg);
                }
                blockVariables.add(variableDeclarator.getId().getName());
            }
        }

        protected final boolean aLocalVariableExists(@Nonnull String name) {
            return contains(concat(this.localVariables), name);
        }

        private void addFieldVariables(@Nonnull TypeDeclaration typeDeclaration, @Nonnull Set<String> variables) {
            addFieldVariables(typeDeclaration.getMembers(), variables);
        }

        private void addFieldVariables(@Nullable Iterable<? extends BodyDeclaration> declarations, @Nonnull Set<String> variables) {
            for (BodyDeclaration bodyDeclaration : emptyIfNull(declarations)) {
                if (FieldDeclaration.class.isInstance(bodyDeclaration)) {
                    for (VariableDeclarator variableDeclarator : FieldDeclaration.class.cast(bodyDeclaration).getVariables()) {
                        variables.add(variableDeclarator.getId().getName());
                    }
                }
            }
        }

    }

}
