package de.is24.deadcode4j.analyzer;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Sets;
import de.is24.deadcode4j.AnalysisContext;
import de.is24.deadcode4j.analyzer.javassist.ClassPoolAccessor;
import japa.parser.ast.CompilationUnit;
import japa.parser.ast.ImportDeclaration;
import japa.parser.ast.PackageDeclaration;
import japa.parser.ast.body.*;
import japa.parser.ast.expr.*;
import japa.parser.ast.stmt.BlockStmt;
import japa.parser.ast.stmt.CatchClause;
import japa.parser.ast.stmt.ForStmt;
import japa.parser.ast.stmt.ForeachStmt;
import japa.parser.ast.visitor.GenericVisitorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

import static com.google.common.base.Predicates.and;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.Iterables.*;
import static com.google.common.collect.Lists.newLinkedList;
import static com.google.common.collect.Sets.newHashSet;
import static de.is24.deadcode4j.Utils.emptyIfNull;
import static de.is24.deadcode4j.analyzer.javassist.ClassPoolAccessor.classPoolAccessorFor;
import static java.lang.Math.max;

public class ReferenceToConstantsAnalyzer extends JavaFileAnalyzer {

    @Nonnull
    private static String getFirstElement(@Nonnull FieldAccessExpr fieldAccessExpr) {
        Expression scope = fieldAccessExpr.getScope();
        if (NameExpr.class.isInstance(scope)) {
            return NameExpr.class.cast(scope).getName();
        }
        if (FieldAccessExpr.class.isInstance(scope)) {
            return getFirstElement(FieldAccessExpr.class.cast(scope));
        }
        throw new RuntimeException("Should not have reached this point!");
    }

    @Override
    protected void analyzeCompilationUnit(@Nonnull AnalysisContext AnalysisContext, @Nonnull CompilationUnit compilationUnit) {
        compilationUnit.accept(new CompilationUnitVisitor(AnalysisContext), null);
    }

    private static class CompilationUnitVisitor extends LocalVariableRecordingVisitor<Analysis, Analysis> {

        private final Logger logger = LoggerFactory.getLogger(getClass());
        private final ClassPoolAccessor classPoolAccessor;
        private final AnalysisContext AnalysisContext;

        public CompilationUnitVisitor(AnalysisContext AnalysisContext) {
            this.classPoolAccessor = classPoolAccessorFor(AnalysisContext);
            this.AnalysisContext = AnalysisContext;
        }

        @Override
        public Analysis visit(CompilationUnit n, Analysis arg) {
            Analysis rootAnalysis = new Analysis(n.getPackage(), n.getImports());
            super.visit(n, rootAnalysis);
            return null;
        }

        @Override
        public Analysis visit(AnnotationDeclaration n, Analysis arg) {
            String name = n.getName();
            Analysis nestedAnalysis = new Analysis(arg, name);
            super.visit(n, nestedAnalysis);
            resolveFieldReferences(nestedAnalysis);
            resolveNameReferences(nestedAnalysis);
            return null;
        }

        @Override
        public Analysis visit(ClassOrInterfaceDeclaration n, Analysis arg) {
            String name = n.getName();
            Analysis nestedAnalysis = new Analysis(arg, name);
            super.visit(n, nestedAnalysis);
            resolveFieldReferences(nestedAnalysis);
            resolveNameReferences(nestedAnalysis);
            return null;
        }

        @Override
        public Analysis visit(EnumDeclaration n, Analysis arg) {
            String name = n.getName();
            Analysis nestedAnalysis = new Analysis(arg, name);
            super.visit(n, nestedAnalysis);
            resolveFieldReferences(nestedAnalysis);
            resolveNameReferences(nestedAnalysis);
            return null;
        }

        @Override
        public Analysis visit(EnumConstantDeclaration n, Analysis arg) {
            arg.addFieldName(n.getName());
            return super.visit(n, arg);
        }

        @Override
        public Analysis visit(FieldDeclaration n, Analysis arg) {
            for (VariableDeclarator variableDeclarator : n.getVariables()) {
                arg.addFieldName(variableDeclarator.getId().getName());
            }
            return super.visit(n, arg);
        }

        @Override
        public Analysis visit(MarkerAnnotationExpr n, Analysis arg) {
            // performance
            return null;
        }

        @Override
        public Analysis visit(NormalAnnotationExpr n, Analysis arg) {
            // performance
            for (final MemberValuePair m : emptyIfNull(n.getPairs())) {
                m.accept(this, arg);
            }
            return null;
        }

        @Override
        public Analysis visit(SingleMemberAnnotationExpr n, Analysis arg) {
            // performance
            return n.getMemberValue().accept(this, arg);
        }

        @Override
        public Analysis visit(FieldAccessExpr n, Analysis analysis) {
            if (isTargetOfAnAssignment(n) || isScopeOfAMethodCall(n) || isScopeOfThisExpression(n)) {
                return null;
            }
            if (!isRegularFieldAccessExpr(n)) {
                return super.visit(n, analysis);
            }
            // FQ beats all
            // then local variables & fields
            // now imports
            // then package access
            // then asterisk imports
            // finally java.lang
            if (FieldAccessExpr.class.isInstance(n.getScope())) {
                FieldAccessExpr nestedFieldAccessExpr = FieldAccessExpr.class.cast(n.getScope());
                if (isFullyQualifiedReference(nestedFieldAccessExpr, analysis)) {
                    return null;
                }
                if (aLocalVariableExists(getFirstElement(nestedFieldAccessExpr))) {
                    return null;
                }
                analysis.addFieldReference(nestedFieldAccessExpr);
            } else if (NameExpr.class.isInstance(n.getScope())) {
                String typeName = NameExpr.class.cast(n.getScope()).getName();
                if (aLocalVariableExists(typeName))
                    return null;

                analysis.addFieldReference(n);
            }
            return null;
        }

        /**
         * This is not entirely correct: while we want to filter calls like
         * <code>org.slf4j.LoggerFactory.getLogger("foo")</code>, we want to analyze
         * <code>foo.bar.FOO.substring(1)</code>.
         */
        private boolean isScopeOfAMethodCall(Expression expression) {
            return MethodCallExpr.class.isInstance(expression.getParentNode())
                    && expression == MethodCallExpr.class.cast(expression.getParentNode()).getScope();
        }

        private boolean isRegularFieldAccessExpr(FieldAccessExpr fieldAccessExpr) {
            Expression scope = fieldAccessExpr.getScope();
            return NameExpr.class.isInstance(scope) ||
                    FieldAccessExpr.class.isInstance(scope)
                            && isRegularFieldAccessExpr(FieldAccessExpr.class.cast(scope));
        }

        private boolean isFullyQualifiedReference(FieldAccessExpr fieldAccessExpr, Analysis analysis) {
            Optional<String> resolvedClass = resolveClass(fieldAccessExpr.toString());
            if (resolvedClass.isPresent()) {
                AnalysisContext.addDependencies(analysis.getTypeName(), resolvedClass.get());
                return true;
            }
            return FieldAccessExpr.class.isInstance(fieldAccessExpr.getScope())
                    && isFullyQualifiedReference(FieldAccessExpr.class.cast(fieldAccessExpr.getScope()), analysis);
        }

        private Optional<String> resolveClass(String qualifier) {
            return this.classPoolAccessor.resolveClass(qualifier);
        }

        @Override
        public Analysis visit(NameExpr n, Analysis analysis) {
            if (isTargetOfAnAssignment(n) || isScopeOfAMethodCall(n) || isScopeOfThisExpression(n)) {
                return null;
            }
            String namedReference = n.getName();
            if (aLocalVariableExists(namedReference)) {
                return null;
            }
            analysis.addNameReference(namedReference);
            return null;
        }

        private boolean isScopeOfThisExpression(Expression n) {
            return ThisExpr.class.isInstance(n.getParentNode());
        }

        private boolean isTargetOfAnAssignment(Expression n) {
            return AssignExpr.class.isInstance(n.getParentNode()) && n == AssignExpr.class.cast(n.getParentNode()).getTarget();
        }

        private void resolveFieldReferences(Analysis analysis) {
            for (FieldAccessExpr fieldAccessExpr : analysis.getFieldReferences()) {
                if (analysis.isFieldDefined(getFirstElement(fieldAccessExpr))
                        || refersToInnerType(fieldAccessExpr, analysis)
                        || refersToImport(fieldAccessExpr, analysis)
                        || refersToPackageType(fieldAccessExpr, analysis)
                        || refersToAsteriskImport(fieldAccessExpr, analysis)
                        || refersToJavaLang(fieldAccessExpr, analysis)
                        || refersToDefaultPackage(fieldAccessExpr, analysis)) {
                    continue;
                }
                logger.debug("Could not resolve reference [{}] defined within [{}].", fieldAccessExpr.toString(), analysis.getTypeName());
            }
        }

        private boolean refersToInnerType(@Nonnull FieldAccessExpr fieldAccessExpr, @Nonnull Analysis analysis) {
            return refersToClass(fieldAccessExpr, analysis, analysis.getParentTypeName() + "$");
        }

        private boolean refersToImport(FieldAccessExpr fieldAccessExpr, Analysis analysis) {
            String firstElement = getFirstElement(fieldAccessExpr);
            String anImport = analysis.getImport(firstElement);
            if (anImport != null) {
                return
                        refersToClass(fieldAccessExpr, analysis, anImport.substring(0, max(0, anImport.lastIndexOf('.') + 1)));
            }
            anImport = analysis.getStaticImport(firstElement);
            return anImport != null &&
                    refersToClass(fieldAccessExpr, analysis, anImport + ".");
        }

        private boolean refersToPackageType(FieldAccessExpr fieldAccessExpr, Analysis analysis) {
            String packagePrefix = analysis.packageName != null ? analysis.packageName + "." : "";
            return refersToClass(fieldAccessExpr, analysis, packagePrefix);
        }

        private boolean refersToAsteriskImport(FieldAccessExpr fieldAccessExpr, Analysis analysis) {
            for (String asteriskImport : analysis.getAsteriskImports()) {
                if (refersToClass(fieldAccessExpr, analysis, asteriskImport + "."))
                    return true;
            }
            return false;
        }

        private boolean refersToJavaLang(FieldAccessExpr fieldAccessExpr, Analysis analysis) {
            return refersToClass(fieldAccessExpr, analysis, "java.lang.");
        }

        private boolean refersToDefaultPackage(FieldAccessExpr fieldAccessExpr, Analysis analysis) {
            return analysis.packageName != null && refersToClass(fieldAccessExpr, analysis, "");
        }

        private boolean refersToClass(@Nonnull FieldAccessExpr fieldAccessExpr, @Nonnull Analysis analysis, @Nonnull String qualifierPrefix) {
            Optional<String> resolvedClass = resolveClass(qualifierPrefix + fieldAccessExpr.toString());
            if (resolvedClass.isPresent()) {
                AnalysisContext.addDependencies(analysis.getTypeName(), resolvedClass.get());
                return true;
            }
            if (FieldAccessExpr.class.isInstance(fieldAccessExpr.getScope())) {
                return refersToClass(FieldAccessExpr.class.cast(fieldAccessExpr.getScope()), analysis, qualifierPrefix);
            }

            resolvedClass = resolveClass(qualifierPrefix + NameExpr.class.cast(fieldAccessExpr.getScope()).getName());
            if (resolvedClass.isPresent()) {
                AnalysisContext.addDependencies(analysis.getTypeName(), resolvedClass.get());
                return true;
            }
            return false;
        }

        private void resolveNameReferences(Analysis analysis) {
            for (String referenceName : analysis.getNameReferences()) {
                if (analysis.isFieldDefined(referenceName)) {
                    continue;
                }
                String staticImport = analysis.getStaticImport(referenceName);
                if (staticImport != null) {
                    // TODO this should probably be resolved
                    AnalysisContext.addDependencies(analysis.getTypeName(), staticImport);
                    continue;
                }
                // TODO handle asterisk static imports
                logger.debug("Could not resolve name reference [{}] defined within [{}].", referenceName, analysis.getTypeName());
            }
        }

    }

    private static class LocalVariableRecordingVisitor<R, A> extends GenericVisitorAdapter<R, A> {

        @Nonnull
        private final Deque<Set<String>> localVariables = newLinkedList();

        @Override
        public R visit(ConstructorDeclaration n, A arg) {
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
            return null;
        }

        @Override
        public R visit(MethodDeclaration n, A arg) {
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
            return null;
        }

        @Override
        public R visit(CatchClause n, A arg) {
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
            return null;
        }

        @Override
        public R visit(BlockStmt n, A arg) {
            this.localVariables.addLast(Sets.<String>newHashSet());
            try {
                return super.visit(n, arg);
            } finally {
                this.localVariables.removeLast();
            }
        }

        @Override
        public R visit(ForeachStmt n, A arg) {
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
            return null;
        }

        @Override
        public R visit(ForStmt n, A arg) {
            this.localVariables.addLast(Sets.<String>newHashSet());
            try {
                return super.visit(n, arg);
            } finally {
                this.localVariables.removeLast();
            }
        }

        @Override
        public R visit(VariableDeclarationExpr n, A arg) {
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
            return null;
        }

        protected final boolean aLocalVariableExists(@Nonnull String name) {
            return contains(concat(this.localVariables), name);
        }

    }

    private static class Analysis {

        public final String packageName;
        private final Analysis parent;
        private final String typeName;
        private final List<ImportDeclaration> imports;
        private final Set<String> fieldNames = newHashSet();
        private final Set<FieldAccessExpr> fieldReferences = newHashSet();
        private final Set<String> nameReferences = newHashSet(); // reduce duplicate calls to one

        public Analysis(Analysis arg, String typeName) {
            this.parent = arg;
            this.typeName = typeName;
            this.packageName = arg.packageName;
            this.imports = arg.imports;
        }

        public Analysis(PackageDeclaration packageName, List<ImportDeclaration> imports) {
            this.packageName = packageName == null ? null : packageName.getName().toString();
            this.imports = imports != null ? imports : Collections.<ImportDeclaration>emptyList();
            this.parent = null;
            this.typeName = null;
        }

        private static Predicate<? super ImportDeclaration> isAsterisk() {
            return new Predicate<ImportDeclaration>() {
                @Override
                public boolean apply(@Nullable ImportDeclaration input) {
                    return input != null && input.isAsterisk();
                }
            };
        }

        private static Predicate<? super ImportDeclaration> isStatic() {
            return new Predicate<ImportDeclaration>() {
                @Override
                public boolean apply(@Nullable ImportDeclaration input) {
                    return input != null && input.isStatic();
                }
            };
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
                    if (input.isStatic()) {
                        name = QualifiedNameExpr.class.cast(name).getQualifier();
                    }
                    return name.toString();
                }
            };
        }

        public void addFieldName(String name) {
            this.fieldNames.add(name);
        }

        public boolean isFieldDefined(String referenceName) {
            return this.fieldNames.contains(referenceName) || hasParent() && this.parent.isFieldDefined(referenceName);
        }

        public void addFieldReference(FieldAccessExpr fieldAccessExpression) {
            this.fieldReferences.add(fieldAccessExpression);
        }

        public void addNameReference(String namedReference) {
            this.nameReferences.add(namedReference);
        }

        public Iterable<FieldAccessExpr> getFieldReferences() {
            return this.fieldReferences;
        }

        public Iterable<String> getNameReferences() {
            return this.nameReferences;
        }

        public String getTypeName() {
            boolean isNested = false;
            StringBuilder buffy = new StringBuilder();
            if (hasParent()) {
                String parentTypeName = parent.getTypeName();
                if (parentTypeName != null) {
                    buffy.append(parentTypeName);
                    isNested = true;
                }
            }
            if (this.typeName != null) {
                if (isNested) {
                    buffy.append("$");
                } else {
                    buffy.append(this.packageName).append('.');
                }
                buffy.append(this.typeName);
            }

            return buffy.length() > 0 ? buffy.toString() : null;
        }

        public String getParentTypeName() {
            String fullTypeName = getTypeName();
            if (fullTypeName == null)
                return null;
            int beginNestedType = fullTypeName.lastIndexOf("$");
            if (beginNestedType < 0)
                return fullTypeName;
            return fullTypeName.substring(0, beginNestedType);
        }

        @SuppressWarnings("unchecked")
        public String getImport(String typeName) {
            return getOnlyElement(transform(filter(this.imports, and(refersTo(typeName), not(isAsterisk()), not(isStatic()))), toImportedType()), null);
        }

        @SuppressWarnings("unchecked")
        public String getStaticImport(String referenceName) {
            return getOnlyElement(transform(filter(this.imports, and(refersTo(referenceName), not(isAsterisk()), isStatic())), toImportedType()), null);
        }

        public Iterable<String> getAsteriskImports() {
            return transform(filter(this.imports, and(isAsterisk(), not(isStatic()))), toImportedType());
        }

        private boolean hasParent() {
            return this.parent != null;
        }
    }

}
