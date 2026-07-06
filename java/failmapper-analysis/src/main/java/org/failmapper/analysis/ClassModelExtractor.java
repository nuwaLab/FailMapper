package org.failmapper.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;

import org.failmapper.core.model.ClassModel;
import org.failmapper.core.model.ConstructorModel;
import org.failmapper.core.model.FieldModel;
import org.failmapper.core.model.MethodModel;
import org.failmapper.core.model.ParameterModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds {@link ClassModel} instances from a parsed {@link CompilationUnit}.
 *
 * <p>Produces one model per top-level or nested type declaration, in source
 * order (each outer type immediately precedes its nested types, depth-first).
 * Nested types get binary-ish dotted FQNs ({@code pkg.Outer.Inner}).
 *
 * <p>Conventions:
 * <ul>
 *   <li>{@code kind} is one of {@code class}, {@code interface}, {@code enum},
 *       {@code record}, {@code annotation}.</li>
 *   <li>For interfaces, the {@code extends} list (super-interfaces) is reported
 *       as {@code interfaces} and {@code superclass} is null.</li>
 *   <li>Types, initializers, and thrown exceptions are recorded as written in
 *       source (generics preserved); varargs parameters are rendered with
 *       {@code ...}.</li>
 *   <li>Records with no explicit constructor get an empty constructor list:
 *       the canonical constructor is implicit. Compact canonical constructors
 *       are likewise omitted because they declare no parameter list of their own.</li>
 * </ul>
 */
public final class ClassModelExtractor {

    /**
     * Extracts a model for every top-level and nested type declaration in the
     * compilation unit, preserving source declaration order.
     */
    public List<ClassModel> extractAll(CompilationUnit cu, String sourcePath) {
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        List<String> imports = extractImports(cu);
        List<ClassModel> models = new ArrayList<>();
        for (TypeDeclaration<?> type : cu.getTypes()) {
            collect(type, packageName, packageName, imports, sourcePath, models);
        }
        return models;
    }

    /**
     * Extracts the model of the first top-level type declaration, if any.
     */
    public Optional<ClassModel> extractPrimary(CompilationUnit cu, String sourcePath) {
        List<ClassModel> all = extractAll(cu, sourcePath);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        String firstTopLevelFqn = cu.getTypes().isEmpty()
                ? all.get(0).fqn()
                : qualify(packageName, cu.getType(0).getNameAsString());
        return all.stream().filter(m -> m.fqn().equals(firstTopLevelFqn)).findFirst()
                .or(() -> Optional.of(all.get(0)));
    }

    private void collect(TypeDeclaration<?> type,
                         String packageName,
                         String qualifier,
                         List<String> imports,
                         String sourcePath,
                         List<ClassModel> out) {
        String fqn = qualify(qualifier, type.getNameAsString());
        out.add(extractType(type, packageName, fqn, imports, sourcePath));
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                collect(nested, packageName, fqn, imports, sourcePath, out);
            }
        }
    }

    private ClassModel extractType(TypeDeclaration<?> type,
                                   String packageName,
                                   String fqn,
                                   List<String> imports,
                                   String sourcePath) {
        String kind = kindOf(type);
        boolean isAbstract = type instanceof ClassOrInterfaceDeclaration coid
                && !coid.isInterface()
                && coid.isAbstract();
        String superclass = superclassOf(type);
        List<String> interfaces = interfacesOf(type);

        List<FieldModel> fields = new ArrayList<>();
        List<ConstructorModel> constructors = new ArrayList<>();
        List<MethodModel> methods = new ArrayList<>();
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration field) {
                fields.addAll(extractFields(field));
            } else if (member instanceof ConstructorDeclaration constructor) {
                constructors.add(extractConstructor(constructor));
            } else if (member instanceof MethodDeclaration method) {
                methods.add(extractMethod(method));
            }
        }

        return new ClassModel(
                fqn,
                packageName,
                type.getNameAsString(),
                kind,
                isAbstract,
                superclass,
                interfaces,
                fields,
                constructors,
                methods,
                imports,
                sourcePath);
    }

    private static String kindOf(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration coid) {
            return coid.isInterface() ? "interface" : "class";
        }
        if (type instanceof EnumDeclaration) {
            return "enum";
        }
        if (type instanceof RecordDeclaration) {
            return "record";
        }
        if (type instanceof AnnotationDeclaration) {
            return "annotation";
        }
        return "class";
    }

    private static String superclassOf(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration coid && !coid.isInterface()) {
            return coid.getExtendedTypes().isNonEmpty()
                    ? coid.getExtendedTypes().get(0).asString()
                    : null;
        }
        return null;
    }

    private static List<String> interfacesOf(TypeDeclaration<?> type) {
        List<ClassOrInterfaceType> declared;
        if (type instanceof ClassOrInterfaceDeclaration coid) {
            declared = coid.isInterface() ? coid.getExtendedTypes() : coid.getImplementedTypes();
        } else if (type instanceof EnumDeclaration ed) {
            declared = ed.getImplementedTypes();
        } else if (type instanceof RecordDeclaration rd) {
            declared = rd.getImplementedTypes();
        } else {
            declared = List.of();
        }
        return declared.stream().map(ClassOrInterfaceType::asString).toList();
    }

    private static List<FieldModel> extractFields(FieldDeclaration field) {
        List<String> modifiers = modifiersOf(field);
        List<FieldModel> models = new ArrayList<>();
        for (VariableDeclarator variable : field.getVariables()) {
            models.add(new FieldModel(
                    variable.getNameAsString(),
                    variable.getType().asString(),
                    modifiers,
                    variable.getInitializer().map(Expression::toString).orElse(null)));
        }
        return models;
    }

    private static ConstructorModel extractConstructor(ConstructorDeclaration constructor) {
        return new ConstructorModel(
                extractParameters(constructor.getParameters()),
                modifiersOf(constructor));
    }

    private MethodModel extractMethod(MethodDeclaration method) {
        return new MethodModel(
                method.getNameAsString(),
                extractParameters(method.getParameters()),
                method.getType().asString(),
                modifiersOf(method),
                method.getThrownExceptions().stream().map(ReferenceType::asString).toList(),
                isOverride(method),
                method.getRange().map(r -> r.begin.line).orElse(0),
                method.getRange().map(r -> r.end.line).orElse(0));
    }

    private static List<ParameterModel> extractParameters(List<Parameter> parameters) {
        return parameters.stream()
                .map(p -> new ParameterModel(
                        p.getNameAsString(),
                        p.getType().asString() + (p.isVarArgs() ? "..." : "")))
                .toList();
    }

    private static List<String> modifiersOf(NodeWithModifiers<?> node) {
        return node.getModifiers().stream()
                .map(Modifier::getKeyword)
                .map(Modifier.Keyword::asString)
                .toList();
    }

    /**
     * A method is an override if it carries {@code @Override}, or — best effort —
     * if the symbol solver finds a non-private, non-static method with the same
     * name and parameter types in any ancestor of the declaring type. Any symbol
     * resolution failure silently falls back to the annotation-only answer.
     */
    private boolean isOverride(MethodDeclaration method) {
        if (method.getAnnotationByName("Override").isPresent()) {
            return true;
        }
        try {
            ResolvedMethodDeclaration resolved = method.resolve();
            ResolvedReferenceTypeDeclaration declaring = resolved.declaringType();
            for (ResolvedReferenceType ancestor : declaring.getAllAncestors()) {
                for (MethodUsage candidate : ancestor.getDeclaredMethods()) {
                    if (overrides(resolved, candidate)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Unresolvable symbols (missing classpath, unsupported constructs):
            // fall back to the annotation-only answer.
        }
        return false;
    }

    private static boolean overrides(ResolvedMethodDeclaration method, MethodUsage candidate) {
        try {
            ResolvedMethodDeclaration candidateDecl = candidate.getDeclaration();
            if (candidateDecl.isStatic()
                    || candidateDecl.accessSpecifier() == com.github.javaparser.ast.AccessSpecifier.PRIVATE) {
                return false;
            }
            if (!method.getName().equals(candidate.getName())
                    || method.getNumberOfParams() != candidate.getNoParams()) {
                return false;
            }
            for (int i = 0; i < method.getNumberOfParams(); i++) {
                if (!method.getParam(i).getType().describe().equals(candidate.getParamType(i).describe())) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> extractImports(CompilationUnit cu) {
        return cu.getImports().stream()
                .map(ClassModelExtractor::importString)
                .toList();
    }

    private static String importString(ImportDeclaration importDeclaration) {
        StringBuilder sb = new StringBuilder();
        if (importDeclaration.isStatic()) {
            sb.append("static ");
        }
        sb.append(importDeclaration.getNameAsString());
        if (importDeclaration.isAsterisk()) {
            sb.append(".*");
        }
        return sb.toString();
    }

    private static String qualify(String qualifier, String simpleName) {
        return qualifier.isEmpty() ? simpleName : qualifier + "." + simpleName;
    }
}
